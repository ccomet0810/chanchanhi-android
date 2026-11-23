package dev.ccomet.chanchanhi04.privacy

/** 온라인 AI로 보내기 전에 휴대폰에서 가리는 개인정보 종류입니다. */
enum class PiiKind(val token: String) {
    NAME("이름"), PHONE("전화번호"), EMAIL("이메일"), RESIDENT_ID("주민번호"), CARD("카드번호"),
    ACCOUNT("계좌번호"), ADDRESS("주소"), MONEY("금액"), IDENTIFIER("번호"),
}

/** [start, end) 범위의 개인정보 후보입니다. */
data class PiiSpan(val start: Int, val end: Int, val kind: PiiKind)

/** 여러 문장을 한 번에 검사해 각 문장의 후보를 돌려줍니다. 결과 크기는 입력과 같아야 합니다. */
fun interface PiiDetector {
    fun detect(texts: List<String>): List<List<PiiSpan>>
}

/**
 * 레이어 1: 결정적 규칙. 같은 입력에는 항상 같은 결과를 내며 화면 문구가 판단을 바꿀 수 없습니다.
 * 모델 기반 감지가 놓쳐도 이 규칙에 걸리는 형식은 반드시 가립니다.
 */
object PiiRules : PiiDetector {
    private const val SURNAMES = "김이박최정강조윤장임한오서신권황안송류유전홍고문양손배백허남심노하곽성차주우구민진나지엄채원천방공현함변염여추도소석선설마길연위표명기반왕금옥육인맹제모탁국어은편용예경봉사부"
    private val compoundSurnames = listOf("남궁", "황보", "제갈", "선우", "독고", "사공", "서문")
    private val surnameClass = "[$SURNAMES]"

    private val patterns: List<Pair<Regex, PiiKind>> = listOf(
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to PiiKind.EMAIL,
        // 주민·외국인등록번호: 생년월일 6자리 + 성별 1~8 + 6자리
        Regex("(?<!\\d)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\s?[-–]?\\s?[1-8]\\d{6}(?!\\d)") to PiiKind.RESIDENT_ID,
        Regex("(?<!\\d)(?:\\d{4}[ -]?){3}\\d{4}(?!\\d)") to PiiKind.CARD,
        Regex("(?<!\\d)3[47]\\d{2}[ -]?\\d{6}[ -]?\\d{5}(?!\\d)") to PiiKind.CARD,
        // 휴대전화·지역번호·대표번호
        Regex("(?<![\\d+])(?:\\+82[- ]?|0)1[016789][- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)") to PiiKind.PHONE,
        Regex("(?<![\\d+])(?:\\+82[- ]?|0)(?:2|[3-6][1-5])[- .)]?\\d{3,4}[- .]?\\d{4}(?!\\d)") to PiiKind.PHONE,
        Regex("(?<!\\d)1[5-9]\\d{2}[- ]\\d{4}(?!\\d)") to PiiKind.PHONE,
        // 하이픈으로 묶인 10~14자리 숫자는 계좌번호로 본다. 날짜(8자리)는 제외된다.
        Regex("(?<!\\d)\\d{2,6}-\\d{2,6}-\\d{2,7}(?:-\\d{1,3})?(?!\\d)") to PiiKind.ACCOUNT,
        Regex("(?<!\\d)\\d{10,}(?!\\d)") to PiiKind.IDENTIFIER,
        // 도로명·지번 주소와 동·호수
        Regex("(?:[가-힣]{2,}(?:특별시|광역시|특별자치시|특별자치도|시|도)\\s+)?[가-힣]{1,}(?:시|군|구)\\s+(?:[가-힣]{1,}(?:구|읍|면)\\s+)?[가-힣0-9]{1,}(?:로|길|동|리|가)\\s*\\d{1,5}(?:-\\d{1,5})?(?:번길\\s*\\d{1,5})?(?:\\s*,?\\s*\\d{1,4}동)?(?:\\s*\\d{1,5}호)?") to PiiKind.ADDRESS,
        Regex("(?<![가-힣0-9])\\d{1,4}동\\s?\\d{1,5}호") to PiiKind.ADDRESS,
        Regex("(?<![\\d.,])\\d{1,3}(?:,\\d{3})+\\s?원|(?<![\\d.,])\\d+(?:\\.\\d+)?\\s?(?:만|억|천)\\s?원|(?<![\\d.,])\\d{4,}\\s?원") to PiiKind.MONEY,
    )

    // "김민수님", "이영희 씨", "박지훈에게/한테/께" 처럼 호칭·조사 앞에 오는 한국어 이름
    private val honorificName = Regex("(?<![가-힣])((?:${compoundSurnames.joinToString("|")})[가-힣]{1,2}|$surnameClass[가-힣]{1,2})(?=\\s?(?:님|씨|에게|한테|께서|께|선생님|고객님))")
    private val notNames = setOf("고객", "회원", "관리", "사용", "이용", "담당", "선생", "기사", "상담", "대표", "부모", "선배", "후배", "여러분", "이분", "그분", "저분", "어머", "아버", "이모", "고모", "삼촌", "할머", "할아", "주인", "판매", "구매", "배송", "운영",
        // 관계·호칭·지시어는 사람을 특정하지 않으므로 이름으로 보지 않는다.
        "엄마", "오빠", "남편", "손자", "손녀", "조카", "사위", "장모", "장인", "우리", "모두", "이거", "이것", "이쪽", "사장", "부장", "원장",
        "이사", "소장", "반장", "주민", "어르", "전체", "정말", "지금", "오늘", "다른", "친척", "동네", "임원", "직원", "강사", "의사", "약사", "변호")

    override fun detect(texts: List<String>): List<List<PiiSpan>> = texts.map(::spans)

    fun spans(text: String): List<PiiSpan> {
        if (text.isEmpty()) return emptyList()
        val found = mutableListOf<PiiSpan>()
        for ((pattern, kind) in patterns) pattern.findAll(text).forEach {
            // 계좌번호는 숫자 10자리 이상만 본다. 날짜(2026-11-23)·시각·버전 번호는 제외된다.
            if (kind == PiiKind.ACCOUNT && it.value.count(Char::isDigit) < 10) return@forEach
            found += PiiSpan(it.range.first, it.range.last + 1, kind)
        }
        honorificName.findAll(text).forEach { m ->
            val name = m.groups[1]!!
            if (notNames.none { name.value.startsWith(it) }) found += PiiSpan(name.range.first, name.range.last + 1, PiiKind.NAME)
        }
        return merge(found)
    }

    /** 이 문자열이 사람 이름처럼 보이는지(연락처·대화 상대 칸에 쓴다). */
    fun looksLikePersonName(value: String): Boolean {
        val v = value.trim()
        if (Regex("^(?:${compoundSurnames.joinToString("|")})[가-힣]{1,2}$|^$surnameClass[가-힣]{1,3}$").matches(v)) return notNames.none { v.startsWith(it) }
        // 영문 이름 "Minsu Kim"
        return Regex("^[A-Z][a-z]{1,15}(?: [A-Z][a-z]{1,15}){1,2}$").matches(v)
    }

    /** 겹치는 후보는 더 넓은 범위 하나로 합친다. 종류는 먼저 시작한 쪽을 따른다. */
    fun merge(spans: List<PiiSpan>): List<PiiSpan> {
        if (spans.size < 2) return spans
        val sorted = spans.sortedWith(compareBy<PiiSpan> { it.start }.thenByDescending { it.end })
        val out = mutableListOf<PiiSpan>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.start < last.end) { if (s.end > last.end) out[out.lastIndex] = last.copy(end = s.end) }
            else out += s
        }
        return out
    }
}

/** 여러 감지기의 결과를 합칩니다. */
class CombinedPiiDetector(private val detectors: List<PiiDetector>) : PiiDetector {
    override fun detect(texts: List<String>): List<List<PiiSpan>> {
        val all = detectors.map { d -> d.detect(texts).also { check(it.size == texts.size) { "detector size mismatch" } } }
        return texts.indices.map { i -> PiiRules.merge(all.flatMap { it[i] }) }
    }
}
