package com.example.aplassistmvp0.guide

import com.example.aplassistmvp0.accessibility.AccessibilityNodeModel
import com.example.aplassistmvp0.state.ScreenSnapshot
import java.text.Normalizer
import java.util.Locale

/** 공개된 현재 화면에서만 팝업 닫기·정확한 메뉴 이름·스크롤 다음 단계를 찾는다. 앱별 좌표는 없다. */
internal object NavigationDecisionPolicy {
    private val popupMarkers = listOf("오늘하루보지않기", "오늘보지않기", "오늘은그만보기", "오늘하루그만보기", "오늘그만보기",
        "다시보지않기", "다시보지않음", "하루동안표시하지않음", "오늘하루표시하지않음",
        "dontshowagain", "donotshowagain", "notshowtoday")
    private val closeNames = setOf("닫기", "팝업닫기", "창닫기", "close", "closepopup", "dismiss", "x", "×", "✕", "✖")
    private val genericNames = setOf("화면목록", "스크롤영역", "자세히보기", "더보기", "오늘하루보지않기")
    private val closeNegated=Regex("닫(?:기|지)\\s*(?:말|않|마)|닫으면\\s*안|(?:don't|do not)\\s+close",RegexOption.IGNORE_CASE)

    fun beforeModel(goal: String, screen: ScreenSnapshot, progress: GuideProgressContext = GuideProgressContext()): GuideDecision? {
        if (DirectDecisionPolicy.sensitiveGoal(goal)) return null
        if (dismissiblePopup(screen, goal) && !closeNegated.containsMatchIn(goal)) {
            val close = GuidePolicy.candidates(screen).filter { it.clickable &&
                names(it).any { name -> compact(name) in closeNames } }
                .singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }
            return if (close != null) GuideDecision("highlight", close.id,
                "팝업의 닫기 버튼을 눌러주세요.", "dismiss_popup")
            else GuideDecision("manual", null,
                "팝업의 닫기 또는 X 버튼을 눌러주세요.", "dismiss_popup")
        }
        messageEntry(goal,screen)?.let { return it }
        if (messageTask(goal) && GuidePolicy.contextNodes(screen).any {
                compact(GuidePolicy.label(it)) in setOf("대화멤버선택", "받는사람선택", "수신자선택") ||
                    Regex("\\d+개검색됨").containsMatchIn(compact(GuidePolicy.label(it)))
            } && GuidePolicy.contextNodes(screen).any {
                com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) && it.hasInput == true &&
                    Regex("연락처.*검색|받는\\s*사람|수신자|recipient", RegexOption.IGNORE_CASE).containsMatchIn(GuidePolicy.label(it))
            } && GuidePolicy.contextNodes(screen).none {
                com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) &&
                    Regex("메시지.*(?:내용|입력)|본문|message body", RegexOption.IGNORE_CASE).containsMatchIn(GuidePolicy.label(it))
            }) {
            val recipient = GuidePolicy.candidates(screen).filter {
                com.example.aplassistmvp0.accessibility.RecipientResultBinding.isSafeMatch(it)
            }.singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }
            if (recipient != null) return GuideDecision("highlight", recipient.id,
                "검색된 받는 사람을 눌러주세요.")
            return GuideDecision("manual", null, "검색 결과에서 받는 사람을 선택해주세요.", "wait_recipient")
        }
        afterVerifiedInput(goal, screen, progress)?.let { return GuideDecision("highlight", it.id,
            "${GuidePolicy.label(it).take(80)} 항목을 눌러주세요.") }
        retryAfterObservedFailure(goal,screen,progress)?.let { return GuideDecision("highlight",it.id,
            "연결 오류 안내가 보여요. ${GuidePolicy.label(it).take(80)} 버튼을 눌러 다시 불러와주세요.") }
        knownInputStep(goal,screen,progress)?.let { return if(GuidePolicy.label(it)=="입력란")
            GuideDecision("highlight",it.id,"검색 입력란에 검색어를 입력해주세요.",operation="fill_input")
            else GuideDecision("highlight",it.id,"${GuidePolicy.label(it).take(80)} 입력란을 확인해주세요.") }
        knownSearchEntry(goal,screen)?.let { return GuideDecision("highlight",it.id,
            "${GuidePolicy.label(it).take(80)} 항목을 눌러 검색어 입력칸을 열어주세요.") }
        return exactTarget(goal, screen)?.takeUnless {
            // A shorter page title matching the entry we just tapped is context for
            // arrival, not another mandatory tap. Let the model ask for confirmation.
            progress.previousAction=="tap" && compact(progress.previousTarget).contains(compact(GuidePolicy.label(it)))
        }?.let { GuideDecision("highlight", it.id,
            "${GuidePolicy.label(it).take(80)} 항목을 눌러주세요.") }
    }

    private fun messageTask(goal:String)=Regex("메시지|문자|메일|message|sms|mail",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
        Regex("보내|보네|작성|전송|send|compose",RegexOption.IGNORE_CASE).containsMatchIn(goal)

    /** A named compose entry is useful even with a nonblocking recommendation banner.
     * An observed type chooser needs the user's preference, never a guessed default. */
    private fun messageEntry(goal:String,screen:ScreenSnapshot):GuideDecision? {
        if(!messageTask(goal) || ambiguousLiteralRequest(goal)) return null
        val controls=GuidePolicy.candidates(screen).filter { it.clickable }
        val types=controls.filter { compact(GuidePolicy.label(it)) in setOf("11대화","그룹채팅","단체문자") }
        if(types.size>=2) {
            val oneToOne = types.singleOrNull { compact(GuidePolicy.label(it)) == "11대화" }
            if (oneToOne != null && MessageRecipientPolicy.singleRecipient(goal) &&
                GuidePolicy.resolveDecisionNode(screen, oneToOne.id) === oneToOne)
                return GuideDecision("highlight", oneToOne.id, "1:1 대화 버튼을 눌러주세요.")
            val explicit=types.filter { compact(goal).contains(compact(GuidePolicy.label(it))) }.singleOrNull()
            if(explicit!=null && GuidePolicy.resolveDecisionNode(screen,explicit.id)===explicit)
                return GuideDecision("highlight",explicit.id,"${GuidePolicy.label(explicit)} 항목을 눌러주세요.")
            return GuideDecision("manual",null,"어떤 방식으로 보내시겠어요? ${types.joinToString(", ") { "‘${GuidePolicy.label(it)}’" }} 중에서 골라주세요.","wait_choice")
        }
        if(hasPopup(screen)) return null
        val compose=controls.filter { compact(GuidePolicy.label(it)) in
            setOf("새메시지작성","새문자작성","메시지작성","문자작성","새메시지","newmessage","composemessage") }
            .singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it } ?: return null
        return GuideDecision("highlight",compose.id,"${GuidePolicy.label(compose)} 항목을 눌러주세요.")
    }

    private fun ambiguousLiteralRequest(goal: String) =
        Regex("말고|제외|아니|하지\\s*마|않|열지|누르지|선택하지|고치지|입력하지|취소|닫|뒤로|돌아|don't|do not|instead of|except|cancel|go back",RegexOption.IGNORE_CASE)
            .containsMatchIn(goal)

    /** Retry is a prerequisite only when the current screen explicitly reports a failure.
     * Never select logout/reset or keep locally repeating the same retry. */
    private fun retryAfterObservedFailure(goal:String, screen:ScreenSnapshot, progress:GuideProgressContext):AccessibilityNodeModel? {
        if(ambiguousLiteralRequest(goal) || Regex("로그아웃|종료|나가기|logout|exit",RegexOption.IGNORE_CASE).containsMatchIn(goal) ||
            !Regex("보고|열|이용|진행|계속|다시|재시도|retry|open|continue",RegexOption.IGNORE_CASE).containsMatchIn(goal)) return null
        val context=GuidePolicy.contextNodes(screen)
        if(context.any { it.className.orEmpty().contains("ProgressBar",true) }) return null
        if(context.none { Regex("(?:연결|불러오|접속|통신).{0,20}(?:못|실패|오류|끊)|오류가.{0,8}발생|문제가.{0,8}발생|connection failed|unable to load|offline",RegexOption.IGNORE_CASE)
                .containsMatchIn(GuidePolicy.label(it)) }) return null
        val retry=GuidePolicy.candidates(screen).filter { it.clickable && compact(GuidePolicy.label(it)) in
            setOf("다시시도","재시도","재접속","다시연결","재연결","retry","reconnect","tryagain") }.singleOrNull() ?: return null
        if(compact(progress.previousTarget)==compact(GuidePolicy.label(retry))) return null
        return retry.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it }
    }

    private fun knownSearchEntry(goal: String, screen: ScreenSnapshot): AccessibilityNodeModel? {
        if(hasPopup(screen) || ambiguousLiteralRequest(goal) ||
            !Regex("검색어|검색.*입력|입력.*검색|search.*input",RegexOption.IGNORE_CASE).containsMatchIn(goal)) return null
        val candidates=GuidePolicy.candidates(screen)
        val editors=candidates.filter { com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) }
        val searchEditors=editors.filter { Regex("검색|search|query",RegexOption.IGNORE_CASE).containsMatchIn(GuidePolicy.label(it)) }
        if(searchEditors.isNotEmpty()) return searchEditors.singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it }
        // An existing editor's submit button is not an entry to another search surface.
        if(editors.isNotEmpty()) return null
        val controls=candidates.filter { it.clickable &&
            !Regex("음성|카메라|이미지|QR|고급|상세|(?<![A-Za-z])AI(?![A-Za-z])|인공지능|voice|camera|lens",RegexOption.IGNORE_CASE)
                .containsMatchIn(GuidePolicy.label(it)) }
        // Explicit input roles take precedence over a separate generic '검색' action.
        val explicit=controls.filter { Regex("검색어.*입력|검색입력란|검색또는(?:주소|url)입력|searchinput",RegexOption.IGNORE_CASE)
            .containsMatchIn(compact(GuidePolicy.label(it))) }
        val selected=if(explicit.isNotEmpty()) explicit.singleOrNull() else controls.filter {
            val label=GuidePolicy.label(it);label.length<=40 && Regex("검색(?:창|입력란|입력칸)?(?:[, ]*버튼)?$").containsMatchIn(label.trim())
        }.singleOrNull()
        return selected?.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it }
    }

    /** 사용자 원문은 모른다. 공개된 입력 역할과 명시한 작성 목적이 맞는 유일한 입력란만 가리킨다. */
    private fun knownInputStep(goal: String, screen: ScreenSnapshot, progress: GuideProgressContext): AccessibilityNodeModel? {
        if (hasPopup(screen) || ambiguousLiteralRequest(goal)) return null
        val inputs=GuidePolicy.candidates(screen).filter { com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) }
        val invalid=inputs.filter { it.contentInvalid==true && compact(GuidePolicy.label(it)).length>=3 &&
            compact(goal).contains(compact(GuidePolicy.label(it))) }
        if (Regex("고치|수정|올바|correct|fix",RegexOption.IGNORE_CASE).containsMatchIn(goal))
            invalid.singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it }?.let { return it }
        val messageTask=messageTask(goal)
        if(messageTask && inputs.size==2 && Regex("보내|전송|send",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
            !Regex("본문만|내용만|입력만|작성만|임시|초안|draft|only",RegexOption.IGNORE_CASE).containsMatchIn(goal)) {
            val recipient=inputs.singleOrNull { Regex("받는\\s*사람|수신자|recipient",RegexOption.IGNORE_CASE).containsMatchIn(GuidePolicy.label(it)) }
            val body=inputs.singleOrNull { Regex("메시지\\s*내용|문자\\s*내용|본문|message.*body",RegexOption.IGNORE_CASE).containsMatchIn(GuidePolicy.label(it)) }
            if(recipient!=null && body!=null && recipient.id!=body.id) {
                val selected=when {
                    recipient.hasInput==false && body.hasInput==false && recipient.contentInvalid!=true ->recipient
                    recipient.hasInput==true && body.hasInput==false && body.contentInvalid!=true &&
                        progress.previousAction=="fill_input" && progress.previousTarget==GuidePolicy.label(recipient) &&
                        progress.outcome=="fill_input 결과 확인됨" ->body
                    else ->null
                }
                selected?.takeIf { GuidePolicy.resolveDecisionNode(screen,it.id)===it }?.let { return it }
            }
        }
        val only=inputs.singleOrNull()?.takeIf { it.hasInput==false && it.contentInvalid!=true } ?: return null
        val label=GuidePolicy.label(only)
        val enteredSearch=progress.previousAction=="tap" && progress.previousTarget.length<=60 &&
            Regex("검색|search",RegexOption.IGNORE_CASE).containsMatchIn(progress.previousTarget) &&
            !Regex("음성|카메라|이미지|QR|AI|voice|camera",RegexOption.IGNORE_CASE).containsMatchIn(progress.previousTarget) &&
            (progress.outcome.startsWith("사용자 조작 감지") || progress.outcome.startsWith("새 창과 다음 조작") ||
                progress.outcome.startsWith("부분 수집·전환 뒤") || progress.outcome.startsWith("검색 진입 뒤 새 입력란 확인"))
        if(label=="입력란" && only.focused==true && enteredSearch &&
            Regex("검색|search",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
            GuidePolicy.contextNodes(screen).none { Regex("로그인|비밀번호|인증|sign.?in|password|verification",RegexOption.IGNORE_CASE)
                .containsMatchIn(GuidePolicy.label(it)) } && GuidePolicy.resolveDecisionNode(screen,only.id)===only) return only
        val phoneTask=Regex("전화\\s*(?:를\\s*)?(?:걸|하|해)|통화|call",RegexOption.IGNORE_CASE).containsMatchIn(goal)
        val roleMatches=messageTask && Regex("받는\\s*사람|수신|메시지\\s*내용|문자\\s*내용|본문|연락처.*(?:검색|입력)|이름.*(?:전화|번호)|recipient|message|body",RegexOption.IGNORE_CASE).containsMatchIn(label) ||
            phoneTask && Regex("전화번호|휴대폰번호|phone",RegexOption.IGNORE_CASE).containsMatchIn(label)
        return only.takeIf { roleMatches && GuidePolicy.resolveDecisionNode(screen,it.id)===it }
    }

    private fun afterVerifiedInput(goal: String, screen: ScreenSnapshot, progress: GuideProgressContext): AccessibilityNodeModel? {
        if (hasPopup(screen) || ambiguousLiteralRequest(goal) ||
            Regex("입력만|작성만|보기만|입력\\s*(?:칸|화면).*열|draft|only",RegexOption.IGNORE_CASE).containsMatchIn(goal) ||
            progress.previousAction !in setOf("fill_input", "edit_input") ||
            progress.outcome != "${progress.previousAction} 결과 확인됨") return null
        val inputs = GuidePolicy.contextNodes(screen).filter {
            it.enabled && com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it)
        }
        if (inputs.isEmpty() || inputs.none { GuidePolicy.label(it) == progress.previousTarget } ||
            inputs.any { it.hasInput != true || it.contentInvalid == true }) return null
        return GuidePolicy.candidates(screen).filter { it.clickable && it.checkable != true &&
            !it.hasRangeInfo && (compact(GuidePolicy.label(it)) in setOf("다음", "계속", "검색", "next", "continue", "search") ||
                requestedSendOrCall(goal=goal, label=GuidePolicy.label(it), inputs=inputs)) }
            .singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }
    }

    /** 전송/통화는 명시된 목적과 공개된 입력 역할이 맞을 때만 강조한다. 실제 실행은 사용자가 한다. */
    private fun requestedSendOrCall(goal: String, label: String, inputs: List<AccessibilityNodeModel>): Boolean {
        if (Regex("보내지|전송하지|통화하지|전화하지|걸지|하지\\s*말|않|나중|작성만|임시\\s*저장|초안|저장만|draft|don't|do not|without",RegexOption.IGNORE_CASE)
                .containsMatchIn(goal)) return false
        val control=compact(label)
        val names=inputs.map(GuidePolicy::label)
        return when (control) {
            "보내기", "전송", "send" -> Regex("보내|전송|send",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
                Regex("메시지|문자|메일|message|sms|mail",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
                names.any { Regex("메시지|문자|본문|내용|message|body",RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "통화", "전화걸기", "call" -> Regex("전화\\s*(?:를\\s*)?(?:걸|하|해)|통화|call",RegexOption.IGNORE_CASE).containsMatchIn(goal) &&
                names.any { Regex("전화번호|휴대폰번호|phone",RegexOption.IGNORE_CASE).containsMatchIn(it) }
            else -> false
        }
    }

    fun exactTarget(goal: String, screen: ScreenSnapshot): AccessibilityNodeModel? {
        if (DirectDecisionPolicy.sensitiveGoal(goal) || hasPopup(screen)) return null
        // A literal name inside a negated or alternative request is not an instruction to select it.
        if (ambiguousLiteralRequest(goal)) return null
        val request = compact(goal)
        val matches = GuidePolicy.candidates(screen).filter { it.clickable && !it.hasRangeInfo &&
            !DirectDecisionPolicy.sensitiveGoal(GuidePolicy.label(it)) &&
            !com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) }
            .mapNotNull { node ->
                val length = names(node).map(::compact).filter { it.length >= 4 && it !in genericNames && request.contains(it) }
                    .maxOfOrNull { it.length } ?: return@mapNotNull null
                node to length
            }
        val best = matches.maxOfOrNull { it.second } ?: return null
        return matches.filter { it.second == best }.singleOrNull()?.first
            ?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }
    }

    fun afterModelManual(goal: String, screen: ScreenSnapshot): GuideDecision? {
        if (DirectDecisionPolicy.sensitiveGoal(goal)) return null
        if (GuidePolicy.candidates(screen).any { it.hasRangeInfo ||
                Regex("SeekBar|Slider", RegexOption.IGNORE_CASE).containsMatchIn(it.className.orEmpty()) }) return null
        val scrolling = GuidePolicy.candidates(screen).filter { node ->
            node.scrollable && !node.className.orEmpty().contains("Horizontal", ignoreCase = true) &&
                !DirectDecisionPolicy.sensitiveGoal(GuidePolicy.label(node)) &&
                node.bounds.bottom - node.bounds.top > node.bounds.right - node.bounds.left &&
                node.actions.any { it.id in setOf(4096, 16908346) }
        }.singleOrNull()?.takeIf { GuidePolicy.resolveDecisionNode(screen, it.id) === it }
        return scrolling?.let { GuideDecision("highlight", it.id,
            "원하는 메뉴가 아래에 있을 수 있어요. 이 화면을 위로 밀어 아래 항목을 보여주세요. 이어서 찾아드릴게요.", "find_target") }
    }

    fun canContinue(decision: GuideDecision, goal: String, requested: ScreenSnapshot, live: ScreenSnapshot): Boolean =
        GuidePolicy.fingerprint(requested) != GuidePolicy.fingerprint(live) && when (decision.continuation) {
            "dismiss_popup" -> dismissiblePopup(requested, goal) && !dismissiblePopup(live, goal)
            "find_target" -> exactTarget(goal, live) != null
            else -> false
        }

    /** Compose can replace a search entry with its editor in the same window without CLICKED.
     * Observe the new focused field; do not claim the missing click was verified. */
    fun searchInputAfterEntry(goal:String,decision:GuideDecision,requested:ScreenSnapshot,live:ScreenSnapshot):GuideDecision? {
        if(decision.action!="highlight" || decision.operation!="tap" || decision.continuation!=null ||
            requested.truncated || live.truncated || requested.packageName!=live.packageName ||
            live.snapshotId<=requested.snapshotId || live.capturedAtMillis<requested.capturedAtMillis ||
            GuidePolicy.fingerprint(requested)==GuidePolicy.fingerprint(live) ||
            GuidePolicy.contextNodes(requested).any { com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) }) return null
        val entry=requested.allNodes.singleOrNull { it.id==decision.targetNodeId } ?: return null
        if(GuideActionPolicy.target(entry,live)!=null) return null
        val context=GuideProgressContext("tap",GuidePolicy.label(entry),"검색 진입 뒤 새 입력란 확인. 이전 클릭의 성공 여부는 확인되지 않음")
        return beforeModel(goal,live,context)?.takeIf { it.operation=="fill_input" }
    }

    /** A control can change without CLICKED, including inside the same window. Replan; do not infer a successful action. */
    fun transitionedAfterTap(decision: GuideDecision, requested: ScreenSnapshot, live: ScreenSnapshot): Boolean {
        if (decision.action != "highlight" || decision.operation !in setOf(null, "tap") ||
            decision.continuation != null || requested.truncated || live.truncated ||
            requested.packageName != live.packageName ||
            live.snapshotId <= requested.snapshotId || live.capturedAtMillis < requested.capturedAtMillis ||
            GuidePolicy.fingerprint(requested) == GuidePolicy.fingerprint(live)) return false
        val old = requested.allNodes.singleOrNull { it.id == decision.targetNodeId } ?: return false
        if (!old.clickable || old.checkable == true || old.hasRangeInfo ||
            com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(old)) return false
        if (GuideActionPolicy.target(old, live) != null) return false
        val previous = GuidePolicy.contextNodes(requested)
        return GuidePolicy.candidates(live).filter { GuideActionPolicy.option(it, "") }.any { fresh ->
            previous.none { it.className == fresh.className && GuidePolicy.label(it) == GuidePolicy.label(fresh) }
        }
    }

    fun probeAfterMissing(goal: String, screen: ScreenSnapshot): GuideDecision? {
        if (hasPopup(screen)) return if (dismissiblePopup(screen, goal)) beforeModel(goal, screen) else null
        if (GuidePolicy.candidates(screen).any { it.hasRangeInfo ||
                Regex("SeekBar|Slider", RegexOption.IGNORE_CASE).containsMatchIn(it.className.orEmpty()) }) return null
        return ScrollProbePolicy.container(screen)?.takeIf { !DirectDecisionPolicy.sensitiveGoal(goal) }
            ?.let { GuideDecision("probe_scroll", it.id, "화면 밖 항목을 잠시 확인합니다.", "find_target") }
    }

    // 별도 창에서는 탐색·기억 재사용을 막지만, 광고 근거가 없으면 닫기를 지시하지 않는다.
    fun hasPopup(screen: ScreenSnapshot): Boolean = screen.blockingPopup || dismissiblePopup(screen)
    fun dismissiblePopup(screen: ScreenSnapshot, goal: String = ""): Boolean = GuidePolicy.candidates(screen).any { node ->
        names(node).any { name -> popupMarkers.any { compact(name).contains(it) } }
    } || optionalWidgetOffer(screen, goal)

    /** 홈 화면 위젯 설치 제안은 검색 유형 선택창과 다르다. 명시된 제안과 닫기가 함께 있어야 한다. */
    private fun optionalWidgetOffer(screen: ScreenSnapshot, goal: String): Boolean {
        if (!screen.blockingPopup || goal.isBlank() || Regex("위젯|widget",RegexOption.IGNORE_CASE).containsMatchIn(goal)) return false
        val controls = GuidePolicy.candidates(screen)
        if (controls.none { it.clickable && compact(GuidePolicy.label(it)) in closeNames }) return false
        val context = GuidePolicy.contextNodes(screen).joinToString(" ", transform=GuidePolicy::label)
        return Regex("위젯|widget",RegexOption.IGNORE_CASE).containsMatchIn(context) &&
            Regex("홈\\s*화면|바탕\\s*화면|home\\s*screen",RegexOption.IGNORE_CASE).containsMatchIn(context) &&
            Regex("설치|추가|install|add",RegexOption.IGNORE_CASE).containsMatchIn(context)
    }

    fun cancelsProgress(label: String, goal: String, dismissible: Boolean): Boolean {
        val name=compact(label)
        if(name in closeNames && closeNegated.containsMatchIn(goal)) return true
        if(name in setOf("취소","작성취소","cancel") && Regex("취소(?:하)?지\\s*(?:마|말|않)|(?:don't|do not)\\s+cancel",RegexOption.IGNORE_CASE).containsMatchIn(goal)) return true
        if(name=="뒤로" && Regex("뒤로.{0,6}지\\s*(?:마|말|않)|(?:don't|do not)\\s+go\\s+back",RegexOption.IGNORE_CASE).containsMatchIn(goal)) return true
        return !dismissible && name in setOf("취소", "작성취소", "닫기", "cancel", "close", "뒤로") &&
            !Regex("취소|닫|돌아|뒤로|cancel|close", RegexOption.IGNORE_CASE).containsMatchIn(goal)
    }

    /** A model-selected Back can recover from a read-only browsing detour.
     * Forms, incomplete captures and explicit leave warnings retain the cancellation guard.
     * This permits a user instruction only; it never performs the navigation. */
    fun blocksNavigation(label: String, goal: String, screen: ScreenSnapshot,
                         progress: GuideProgressContext = GuideProgressContext()): Boolean {
        if (!cancelsProgress(label, goal, dismissiblePopup(screen, goal))) return false
        if (compact(label) !in setOf("뒤로", "back") || !DirectDecisionPolicy.navigationGoal(goal) ||
            Regex("뒤로.{0,6}지\\s*(?:마|말|않)|(?:don't|do not)\\s+go\\s+back", RegexOption.IGNORE_CASE)
                .containsMatchIn(goal)) return true
        if (screen.truncated || screen.viewport == null || hasPopup(screen) ||
            progress.previousAction in setOf("fill_input", "check", "set_progress", "adjust_range")) return true
        val context = GuidePolicy.contextNodes(screen)
        if (context.size < 2 || screen.allNodes.any {
                com.example.aplassistmvp0.accessibility.AccessibilityNodePrivacy.isEditable(it) ||
                    it.hasInput == true || it.password || it.sensitiveContent || it.checkable == true || it.hasRangeInfo
            }) return true
        return context.any { Regex("저장하지|저장되지|작성\\s*중|입력\\s*중|변경.{0,12}(?:사라|취소|삭제)|나가시겠|결제\\s*진행|unsaved|discard|leave this", RegexOption.IGNORE_CASE)
            .containsMatchIn(GuidePolicy.label(it)) }
    }

    private fun names(node: AccessibilityNodeModel) = listOf(GuidePolicy.label(node)) + node.descendantLabels
    private fun compact(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it in "×✕✖" }
}
