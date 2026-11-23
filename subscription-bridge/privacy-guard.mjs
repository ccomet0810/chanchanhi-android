import { BridgeError } from './protocol.mjs';

// 레이어 5(서버 재검사): 휴대폰이 가렸어야 할, 형식이 뚜렷한 개인정보가 남아 있으면 OpenAI로 전달하지 않는다.
// 앱의 PrivacyGateway.assertClean과 같은 형식만 검사한다(오탐을 줄이기 위해 이름·주소처럼 애매한 형식은 제외).
const RULES = [
  ['email', /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/],
  ['resident_id', /(?<!\d)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\s?[-–]?\s?[1-8]\d{6}(?!\d)/],
  ['card', /(?<!\d)(?:\d{4}[ -]?){3}\d{4}(?!\d)/],
  ['phone', /(?<![\d+])(?:\+82[- ]?|0)1[016789][- .]?\d{3,4}[- .]?\d{4}(?!\d)/],
];

/** 요청 본문 중 사용자·화면 자료(input 텍스트)만 검사한다. instructions와 스키마는 운영자가 만든 고정 문구다. */
export function inputTexts(request) {
  const content = request?.input?.[0]?.content;
  if (typeof content === 'string') return [content];
  if (Array.isArray(content)) return content.filter(part => part?.type === 'input_text' && typeof part.text === 'string').map(part => part.text);
  return [];
}

export function findRawPii(texts) {
  for (const text of texts) for (const [kind, pattern] of RULES) if (pattern.test(text)) return kind;
  return null;
}

export function assertNoRawPii(request) {
  const kind = findRawPii(inputTexts(request));
  // 어떤 값이었는지는 응답·로그에 남기지 않고 종류만 알린다.
  if (kind) throw new BridgeError('privacy_blocked', '가리지 않은 개인정보가 있어 요청을 전달하지 않았습니다. 앱에서 다시 시도해주세요.', 422);
}
