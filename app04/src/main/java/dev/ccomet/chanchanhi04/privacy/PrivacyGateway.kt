package dev.ccomet.chanchanhi04.privacy

import dev.ccomet.chanchanhi04.agent.*

/** 개인정보 확인을 끝내지 못했거나 가리지 못한 값이 남아 요청을 보내지 않은 경우입니다(fail-closed). */
class PrivacyBlocked(message: String, val reason: String) : IllegalStateException(message)

data class ProtectionReport(
    val masked: Map<PiiKind, Int>, val hiddenMessages: Int, val sensitiveApp: Boolean, val imageDropped: Boolean,
    val texts: Int, val detectMs: Double, val totalMs: Double,
)

/**
 * @param privateNodes 스냅샷별로 내용이 가려진 노드 id. 스크린샷에서 같은 영역을 칠할 때 쓴다.
 */
data class ProtectedRequest(val request: AgentRequest, val privateNodes: Map<Long, Set<Int>>, val report: ProtectionReport)

/**
 * 온라인 AI로 나가는 AgentRequest의 복사본만 가립니다. 휴대폰의 원본 관찰·근거 비교에는 손대지 않으므로
 * 노드 id 기반 안내와 Grounding은 그대로 동작합니다.
 *
 * 순서: (1) 화면 구조 규칙 → (2) 규칙+모델 감지 → (3) 일관된 토큰 치환 → (5) 남은 값 재검사.
 * (4) 스크린샷 가림은 이미지 디코딩이 필요해 Android 쪽 [ScreenImageRedactor]가 privateNodes로 수행합니다.
 */
class PrivacyGateway(private val modelDetector: PiiDetector? = null, private val clock: () -> Long = System::nanoTime) {

    fun protect(request: AgentRequest, vault: PseudonymVault): ProtectedRequest {
        val began = clock()
        val sensitive = ScreenPrivacyPolicy.isSensitiveApp(request.screen.packageName)
        val counts = HashMap<PiiKind, Int>()
        var hiddenMessages = 0
        val privateNodes = HashMap<Long, MutableSet<Int>>()

        // (1) 구조 규칙: 대화 본문은 길이만 남기고, 연락처·발신자 칸의 이름은 먼저 토큰으로 등록한다.
        val observations = listOf(request.screen) + request.history.mapNotNull { it.result.observation }
        val messageNodes = HashMap<Long, Set<Int>>()
        for (o in observations) {
            messageNodes[o.id] = o.elements.filter { ScreenPrivacyPolicy.isMessageContent(o.packageName, it) }.map { it.id }.toSet()
            for (e in o.elements) if (ScreenPrivacyPolicy.isPersonField(o.packageName, e) && PiiRules.looksLikePersonName(e.label))
                vault.tokenFor(PiiKind.NAME, e.label.trim())
        }

        // (2) 보낼 모든 문장을 한 번에 감지한다(모델 호출 횟수 최소화).
        val texts = LinkedHashSet<String>()
        fun collect(s: String?) { if (!s.isNullOrBlank()) texts += s }
        collect(request.goal); collect(request.taskContext)
        request.plan.forEach { collect(it.step) }
        request.userAnswers.forEach { collect(it.question); collect(it.answer) }
        for (x in request.history) {
            collect(x.call.summary); collect(x.call.instruction); collect(x.call.question); collect(x.call.expectedChange)
            x.call.options.forEach(::collect); x.call.plan.forEach { collect(it.step) }
            collect(x.result.detail); collect(x.result.answer)
        }
        for (o in observations) for (e in o.elements) if (e.id !in messageNodes.getValue(o.id)) { collect(e.label); collect(e.state); collect(e.value) }
        val list = texts.toList()
        val detectBegan = clock()
        val rules = PiiRules.detect(list)
        val model = modelDetector?.let { d ->
            try { d.detect(list).also { check(it.size == list.size) } }
            catch (e: PrivacyBlocked) { throw e }
            catch (e: Exception) { throw PrivacyBlocked("개인정보 보호 확인을 마치지 못해 이번 요청은 보내지 않았어요. 다시 시도해주세요.", "detector_failed") }
        }
        val detectMs = (clock() - detectBegan) / 1e6
        val spans = HashMap<String, List<PiiSpan>>(list.size)
        list.forEachIndexed { i, s -> spans[s] = PiiRules.merge(rules[i] + (model?.get(i) ?: emptyList())) }
        // 모델이 찾은 이름도 다른 문장(요청 문장 등)에서 같은 토큰이 되도록 먼저 등록한다.
        for ((s, found) in spans) for (sp in found) if (sp.kind == PiiKind.NAME) vault.tokenFor(PiiKind.NAME, s.substring(sp.start, sp.end))

        // (3) 치환
        fun sanitize(text: String?): String? {
            if (text.isNullOrEmpty()) return text
            var out = StringBuilder()
            var cursor = 0
            for (sp in spans[text] ?: PiiRules.spans(text)) {
                if (sp.kind == PiiKind.MONEY && !sensitive) continue
                if (sp.start < cursor) continue
                out.append(text, cursor, sp.start).append(vault.tokenFor(sp.kind, text.substring(sp.start, sp.end)))
                counts.merge(sp.kind, 1, Int::plus)
                cursor = sp.end
            }
            out.append(text, cursor, text.length)
            var result = out.toString()
            // 이미 등록된 원문(연락처 이름 등)이 다른 문장에 다시 나오면 같은 토큰으로 바꾼다.
            for ((original, token) in vault.knownOriginals()) if (result.contains(original)) {
                result = result.replace(original, token); counts.merge(PiiKind.NAME, 1, Int::plus)
            }
            // 금융·인증 앱에서는 4자리 이상 숫자도 가린다(잔액·계좌 일부·인증번호).
            if (sensitive) result = SENSITIVE_DIGITS.replace(result) { m -> counts.merge(PiiKind.IDENTIFIER, 1, Int::plus); vault.tokenFor(PiiKind.IDENTIFIER, m.value) }
            return result
        }

        fun element(o: Observation, e: Element): Element {
            if (e.id in messageNodes.getValue(o.id)) {
                hiddenMessages++; privateNodes.getOrPut(o.id) { HashSet() } += e.id
                return e.copy(label = ScreenPrivacyPolicy.messagePlaceholder(e.label.length), state = null, value = null, display = null)
            }
            val label = sanitize(e.label)!!; val state = sanitize(e.state); val value = sanitize(e.value)
            if (label != e.label || state != e.state || value != e.value || e.editable) privateNodes.getOrPut(o.id) { HashSet() } += e.id
            return e.copy(label = label, state = state, value = value, display = null)
        }
        fun observation(o: Observation) = o.copy(elements = o.elements.map { element(o, it) })
        fun plan(p: List<PlanStep>) = p.map { it.copy(step = sanitize(it.step)!!) }

        val screen = observation(request.screen)
        val history = request.history.map { x ->
            Exchange(x.call.copy(summary = sanitize(x.call.summary)!!, instruction = sanitize(x.call.instruction), question = sanitize(x.call.question),
                expectedChange = sanitize(x.call.expectedChange), options = x.call.options.map { sanitize(it)!! }, plan = plan(x.call.plan)),
                x.result.copy(detail = sanitize(x.result.detail)!!, answer = sanitize(x.result.answer), observation = x.result.observation?.let(::observation)))
        }
        // 금융·인증 앱 화면의 이미지는 가림 여부와 상관없이 보내지 않는다.
        val image = if (sensitive) null else request.image
        val out = request.copy(goal = sanitize(request.goal)!!, plan = plan(request.plan), screen = screen, history = history,
            userAnswers = request.userAnswers.map { UserAnswer(sanitize(it.question)!!, sanitize(it.answer)!!) },
            taskContext = sanitize(request.taskContext), image = image)
        val report = ProtectionReport(counts, hiddenMessages, sensitive, request.image != null && image == null, list.size, detectMs, (clock() - began) / 1e6)
        return ProtectedRequest(out, privateNodes, report)
    }

    companion object {
        private val SENSITIVE_DIGITS = Regex("(?<![\\d\\[가-힣])\\d[\\d,.]{3,}\\d(?![\\d\\]])|(?<![\\d\\[])\\d{4,}(?![\\d\\]])")
        private val STRICT = setOf(PiiKind.EMAIL, PiiKind.RESIDENT_ID, PiiKind.CARD, PiiKind.PHONE)

        /** (5) 보내기 직전 최종 JSON에 형식이 뚜렷한 개인정보가 남아 있으면 보내지 않는다. */
        fun assertClean(encodedContext: String) {
            val left = PiiRules.spans(encodedContext).filter { it.kind in STRICT }
            if (left.isNotEmpty()) throw PrivacyBlocked("가리지 못한 개인정보가 있어 이번 요청은 보내지 않았어요. 현재 화면에서 다시 시도해주세요.",
                "residual_" + left.first().kind.name.lowercase())
        }
    }
}
