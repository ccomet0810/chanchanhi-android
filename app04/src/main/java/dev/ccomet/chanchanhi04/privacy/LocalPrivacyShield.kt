package dev.ccomet.chanchanhi04.privacy

import dev.ccomet.chanchanhi04.agent.AgentRequest
import dev.ccomet.chanchanhi04.agent.ToolCall

/**
 * V04Model이 쓰는 휴대폰 로컬 개인정보 보호 계층입니다. 모든 온라인 AI 요청이 이 한 곳을 거칩니다.
 * 레이어 1~3(규칙·감지·토큰)은 [PrivacyGateway], 레이어 4(이미지)는 [ScreenImageRedactor],
 * 레이어 5(최종 재검사)는 [PrivacyGateway.assertClean]과 브리지 서버의 같은 검사가 맡습니다.
 */
class LocalPrivacyShield(
    private val entities: PiiDetector? = MlKitEntityDetector(),
    private val redactorFactory: (PiiDetector) -> ScreenImageRedactor? = { ScreenImageRedactor(it) },
) {
    private val gateway = PrivacyGateway(entities)
    private val imageDetector = CombinedPiiDetector(listOfNotNull(PiiRules, entities))
    private val redactor by lazy { redactorFactory(imageDetector) }
    private var vault = PseudonymVault()
    private var vaultGoal: String? = null

    fun prepare() { (entities as? MlKitEntityDetector)?.prepareAsync() }

    /** 같은 목표(한 안내 세션) 동안 같은 토큰을 쓰고, 새 목표가 시작되면 원문 표를 버린다. */
    @Synchronized fun vaultFor(request: AgentRequest): PseudonymVault {
        if (request.goal != vaultGoal) { vault = PseudonymVault(); vaultGoal = request.goal }
        return vault
    }

    fun protect(request: AgentRequest, vault: PseudonymVault, trace: (String, Any?) -> Unit): AgentRequest {
        val result = gateway.protect(request, vault)
        var out = result.request
        val image = out.image
        var imageReport: RedactionReport? = null
        var imageDropped = result.report.imageDropped
        if (image != null) {
            out = try {
                val (masked, report) = redactor!!.redact(image, result.privateNodes[image.observation.id].orEmpty(), image.observation.privateRegions, vault)
                imageReport = report
                out.copy(image = masked)
            } catch (e: Exception) {
                // 이미지를 확실히 가리지 못하면 이미지 없이 보낸다(접근성 정보만으로 안내).
                imageDropped = true
                out.copy(image = null)
            }
        }
        val r = result.report
        trace("privacy_gateway", mapOf(
            "masked" to r.masked.mapKeys { it.key.name.lowercase() }, "hiddenMessages" to r.hiddenMessages,
            "sensitiveApp" to r.sensitiveApp, "imageDropped" to imageDropped, "texts" to r.texts,
            "detectMs" to Math.round(r.detectMs * 10) / 10.0, "textMs" to Math.round(r.totalMs * 10) / 10.0,
            "imageMs" to imageReport?.let { Math.round(it.ms * 10) / 10.0 }, "imageNodeBoxes" to imageReport?.nodeBoxes,
            "imageOcrLines" to imageReport?.ocrLines, "imageOcrBoxes" to imageReport?.ocrBoxes))
        return out
    }

    fun restore(call: ToolCall, vault: PseudonymVault): ToolCall = call.copy(
        summary = vault.restore(call.summary)!!, instruction = vault.restore(call.instruction), question = vault.restore(call.question),
        expectedChange = vault.restore(call.expectedChange), options = call.options.map { vault.restore(it)!! },
        plan = call.plan.map { it.copy(step = vault.restore(it.step)!!) })
}
