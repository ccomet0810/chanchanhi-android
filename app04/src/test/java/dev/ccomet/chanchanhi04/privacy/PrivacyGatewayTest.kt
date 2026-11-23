package dev.ccomet.chanchanhi04.privacy

import com.example.aplassistmvp0.accessibility.NodeBounds
import dev.ccomet.chanchanhi04.agent.*
import org.junit.Assert.*
import org.junit.Test

class PrivacyGatewayTest {
    private fun node(id: Int, label: String, resource: String? = null, clickable: Boolean = true, editable: Boolean = false, value: String? = null) =
        Element(id, "0/$id", resource, "TextView", label, NodeBounds(0, id * 100, 1080, id * 100 + 90), true, true, clickable, editable, false,
            null, null, null, null, value)
    private fun screen(pkg: String, vararg nodes: Element) = Observation(7, pkg, 3, 1, 1, 1080, 2340, true, nodes.toList())
    private fun request(goal: String, screen: Observation, answers: List<UserAnswer> = emptyList(), image: ScreenImage? = null) =
        AgentRequest(goal, emptyList(), screen, emptyList(), 0, image, userAnswers = answers)
    private fun json(r: AgentRequest) = AgentJson.request(r).toString()

    @Test fun rulesFindKoreanIdentifiers() {
        fun kinds(s: String) = PiiRules.spans(s).map { it.kind }
        assertEquals(listOf(PiiKind.PHONE), kinds("010-1234-5678로 연락"))
        assertEquals(listOf(PiiKind.PHONE), kinds("02-123-4567"))
        assertEquals(listOf(PiiKind.RESIDENT_ID), kinds("900101-1234567"))
        assertEquals(listOf(PiiKind.CARD), kinds("카드 4111 1111 1111 1111"))
        assertEquals(listOf(PiiKind.ACCOUNT), kinds("계좌 110-123-456789"))
        assertEquals(listOf(PiiKind.EMAIL), kinds("mail a.b@example.com"))
        assertEquals(listOf(PiiKind.ADDRESS), kinds("서울특별시 강남구 테헤란로 152"))
        assertEquals(listOf(PiiKind.ADDRESS), kinds("101동 1203호"))
        assertEquals(listOf(PiiKind.MONEY), kinds("잔액 1,234,000원"))
        assertEquals(listOf(PiiKind.NAME), kinds("김민수님께 보내기"))
        assertEquals(listOf(PiiKind.NAME), kinds("박지훈한테 문자"))
    }

    @Test fun ordinaryUiTextIsNotMasked() {
        for (s in listOf("알람 7:30", "2026-11-23", "엄마한테 문자 보내기", "고객님 환영합니다", "선생님께 전화", "설정", "Wi-Fi", "배터리 85%", "모두에게 공유"))
            assertTrue(s, PiiRules.spans(s).isEmpty())
    }

    @Test fun sameOriginalBecomesSameTokenAcrossGoalScreenAndAnswers() {
        val vault = PseudonymVault()
        val r = request("김민수님께 010-2222-3333 번호로 문자 보내줘",
            screen("com.samsung.android.messaging", node(1, "김민수", "com.samsung.android.messaging:id/contact_name"), node(2, "새 메시지")),
            answers = listOf(UserAnswer("누구에게 보낼까요?", "김민수")))
        val out = PrivacyGateway().protect(r, vault).request
        val name = vault.tokenFor(PiiKind.NAME, "김민수")
        assertEquals("[이름1]", name)
        assertTrue(out.goal, out.goal.startsWith("$name 님께".replace(" ", "")))
        assertTrue(out.goal.contains("[전화번호1]"))
        assertEquals(name, out.screen.elements.first { it.id == 1 }.label)
        assertEquals(name, out.userAnswers.single().answer)
        val encoded = json(out)
        assertFalse(encoded.contains("김민수")); assertFalse(encoded.contains("2222"))
        PrivacyGateway.assertClean(encoded)
    }

    @Test fun restoreTurnsTokensBackOnlyOnThePhone() {
        val vault = PseudonymVault()
        val token = vault.tokenFor(PiiKind.NAME, "이영희")
        assertEquals("이영희 이름을 눌러주세요.", vault.restore("$token 이름을 눌러주세요."))
        assertEquals("[이름9] 그대로", vault.restore("[이름9] 그대로"))
    }

    @Test fun messageBodiesAreReplacedByLengthOnly() {
        val body = "내일 저녁 7시에 병원 앞에서 만나요. 약 꼭 챙겨오세요 그리고 우산도"
        val r = request("답장하기", screen("com.kakao.talk", node(1, body, "com.kakao.talk:id/message", clickable = false), node(2, "전송")))
        val result = PrivacyGateway().protect(r, PseudonymVault())
        val label = result.request.screen.elements.first { it.id == 1 }.label
        assertEquals(ScreenPrivacyPolicy.messagePlaceholder(body.length), label)
        assertEquals("전송", result.request.screen.elements.first { it.id == 2 }.label)
        assertEquals(setOf(1), result.privateNodes[7])
        assertEquals(1, result.report.hiddenMessages)
    }

    @Test fun sensitiveAppsDropImagesAndMaskNumbers() {
        val image = ScreenImage(screen("com.kbstar.kbbank"), "AAAA", 540, 1170)
        val r = request("이체하기", screen("com.kbstar.kbbank", node(1, "잔액 1,234,000원"), node(2, "인증번호 482913")), image = image)
        val result = PrivacyGateway().protect(r, PseudonymVault())
        assertNull(result.request.image)
        assertTrue(result.report.imageDropped)
        val labels = result.request.screen.elements.map { it.label }
        assertFalse(labels.toString(), labels.any { Regex("\\d{4,}").containsMatchIn(it) || it.contains("1,234,000") })
        assertTrue(ScreenPrivacyPolicy.isSensitiveApp("viva.republica.toss"))
        assertFalse(ScreenPrivacyPolicy.isSensitiveApp("com.android.settings"))
    }

    @Test fun moneyIsKeptInOrdinaryApps() {
        val r = request("가장 싼 상품 찾기", screen("com.coupang.mobile", node(1, "12,900원")))
        assertEquals("12,900원", PrivacyGateway().protect(r, PseudonymVault()).request.screen.elements.single().label)
    }

    @Test fun modelDetectorFindingsAreMergedAndFailureBlocksTheRequest() {
        val addressModel = PiiDetector { texts -> texts.map { t -> t.indexOf("판교역로").takeIf { it >= 0 }?.let { listOf(PiiSpan(it, t.length, PiiKind.ADDRESS)) } ?: emptyList() } }
        val r = request("주소 확인", screen("com.example.shop", node(1, "판교역로 235 에이치스퀘어")))
        val out = PrivacyGateway(addressModel).protect(r, PseudonymVault()).request
        assertEquals("[주소1]", out.screen.elements.single().label)
        val broken = PiiDetector { error("model crashed") }
        val blocked = runCatching { PrivacyGateway(broken).protect(r, PseudonymVault()) }.exceptionOrNull()
        assertTrue(blocked is PrivacyBlocked)
    }

    @Test fun residualCheckFailsClosed() {
        val blocked = runCatching { PrivacyGateway.assertClean("""{"goal":"010-9999-8888"}""") }.exceptionOrNull()
        assertTrue(blocked is PrivacyBlocked)
        PrivacyGateway.assertClean("""{"goal":"[전화번호1]","screen":{"nodes":[{"bounds":[0,1012,345,6789]}]}}""")
    }

    @Test fun historyAndTypedValuesAreProtected() {
        val prior = screen("com.samsung.android.messaging", node(1, "받는 사람", editable = true, value = "010-3333-4444"))
        val call = ToolCall("c1", Tool.GUIDE, "010-3333-4444 입력 확인", instruction = "번호 010-3333-4444를 확인하세요")
        val r = AgentRequest("문자 보내기", emptyList(), prior, listOf(Exchange(call, ToolResult("c1", "ok", "입력됨", prior))), 0, null)
        val out = PrivacyGateway().protect(r, PseudonymVault())
        assertFalse(json(out.request).contains("3333"))
        assertTrue(1 in out.privateNodes.getValue(7))
    }

    /** 400개 노드(현재 관찰 최대치)에서 규칙·토큰 레이어 시간. 실제 기기 수치는 아니며 JVM 기준선이다. */
    @Test fun ruleLayersStayFastOnAFullScreen() {
        val nodes = (1..400).map { node(it, if (it % 10 == 0) "김민수님 010-1234-${1000 + it}" else "설정 항목 $it 번째 메뉴") }
        val r = request("김민수님께 문자", screen("com.samsung.android.messaging", *nodes.toTypedArray()))
        val gateway = PrivacyGateway()
        repeat(5) { gateway.protect(r, PseudonymVault()) } // warm-up
        val times = (1..20).map { gateway.protect(r, PseudonymVault()).report.totalMs }.sorted()
        val median = times[times.size / 2]; val p95 = times[(times.size * 95) / 100 - 1]
        println("privacy gateway (400 nodes, JVM): median=${"%.1f".format(median)}ms p95=${"%.1f".format(p95)}ms")
        assertTrue("median $median ms", median < 200)
    }
}
