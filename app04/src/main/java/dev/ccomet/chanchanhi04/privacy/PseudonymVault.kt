package dev.ccomet.chanchanhi04.privacy

/**
 * 레이어 3: 같은 원문을 한 안내 세션 안에서 항상 같은 토큰([이름1] 등)으로 바꿉니다.
 * 요청 문장의 "엄마"와 연락처 목록의 "엄마"가 같은 토큰이 되므로 AI가 원문 없이도 대상을 고를 수 있습니다.
 * 원문은 휴대폰 메모리에만 있고, AI 답변을 사용자에게 보여주기 전에만 되돌립니다.
 */
class PseudonymVault {
    private val byOriginal = LinkedHashMap<Pair<PiiKind, String>, String>()
    private val byToken = HashMap<String, String>()
    private val counters = HashMap<PiiKind, Int>()

    val size: Int get() = byToken.size

    @Synchronized fun tokenFor(kind: PiiKind, original: String): String {
        val key = kind to normalize(original)
        byOriginal[key]?.let { return it }
        val token = "[${kind.token}${(counters[kind] ?: 0) + 1}]"
        counters[kind] = (counters[kind] ?: 0) + 1
        byOriginal[key] = token; byToken[token] = original.trim()
        return token
    }

    /** 이미 알려진 원문(예: 화면에서 찾은 이름)이 다른 문장에 다시 나오면 같은 토큰으로 바꿉니다. */
    @Synchronized fun knownOriginals(): List<Pair<String, String>> =
        byOriginal.entries.map { (key, token) -> byToken.getValue(token) to token }.filter { it.first.length >= 2 }
            .sortedByDescending { it.first.length }

    /** AI 답변 속 토큰을 원문으로 되돌립니다. 모르는 토큰은 그대로 둡니다. */
    @Synchronized fun restore(text: String?): String? {
        if (text == null || byToken.isEmpty() || !text.contains('[')) return text
        return TOKEN.replace(text) { m -> byToken[m.value] ?: m.value }
    }

    private fun normalize(value: String) = value.trim().replace(Regex("[\\s-]+"), "").lowercase()

    companion object { val TOKEN = Regex("\\[(?:${PiiKind.entries.joinToString("|") { it.token }})\\d+]") }
}
