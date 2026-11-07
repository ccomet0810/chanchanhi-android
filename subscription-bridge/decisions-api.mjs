import { BridgeError } from './protocol.mjs';
import { validVisionInput } from './vision-input.mjs';

const MODEL = 'gpt-6-luna', RATE = .10 / 1_000_000;
const invalid = () => new BridgeError('invalid_decisions_request', 'Decisions 요청 형식을 확인해주세요.', 400);
const isObject = value => value && typeof value === 'object' && !Array.isArray(value);
const day = time => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(time));
export function validateDecisionRequest(value) {
  if (!isObject(value) || Object.keys(value).some(k => !['model','input','questions'].includes(k)) || value.model !== MODEL ||
      !(typeof value.input === 'string' && value.input.length > 0 && value.input.length <= 60_000 || validVisionInput(value.input)) ||
      !Array.isArray(value.questions) || !value.questions.length || value.questions.length > 200) throw invalid();
  const names = new Set();
  for (const q of value.questions) {
    if (!isObject(q) || Object.keys(q).some(k => !['type','name','instructions','choices'].includes(k)) || q.type !== 'choice' ||
        !/^(next|route|app_\d+)$/.test(q.name) || names.has(q.name) || typeof q.instructions !== 'string' || q.instructions.length > 8_000 ||
        !Array.isArray(q.choices) || q.choices.length < 2 || q.choices.length > 255) throw invalid();
    names.add(q.name); const values = new Set();
    for (const c of q.choices) {
      if (!isObject(c) || Object.keys(c).some(k => !['value','description'].includes(k)) ||
          typeof c.value !== 'string' || !/^[a-z][a-z0-9_]{0,100}$/.test(c.value) || values.has(c.value) ||
          (c.description !== undefined && (typeof c.description !== 'string' || c.description.length > 4_000))) throw invalid();
      values.add(c.value);
    }
  }
  if (Array.isArray(value.input) && (value.questions.length !== 1 || value.questions[0].name !== 'next')) throw invalid();
  return structuredClone(value);
}
export function decisionResult(value, request) {
  if (!isObject(value) || typeof value.model !== 'string' || !(value.model === MODEL || value.model.startsWith(MODEL+'-')) ||
      !Array.isArray(value.answers) || value.answers.length !== request.questions.length)
    throw new BridgeError('invalid_decisions_result', 'Decisions 응답의 모델과 항목을 확인하지 못했습니다.', 502);
  const answers = request.questions.map((q,i) => {
    const a = value.answers[i];
    if (a?.name !== q.name || a.type !== 'choice' || !q.choices.some(c => c.value === a.choice))
      throw new BridgeError('invalid_decisions_result', 'Decisions가 제공된 선택값을 반환하지 않았습니다.', 502);
    const result = {name:q.name,type:'choice',choice:a.choice};
    if (typeof a.confidence === 'number' && a.confidence >= 0 && a.confidence <= 1) result.confidence = a.confidence;
    if (Array.isArray(a.probabilities)) result.probabilities = a.probabilities.filter(p =>
      q.choices.some(c => c.value === p.value) && typeof p.probability === 'number' && p.probability >= 0 && p.probability <= 1)
      .slice(0,q.choices.length).map(p=>({value:p.value,probability:p.probability}));
    return result;
  });
  const tokens = value.usage?.input_tokens;
  if (!Number.isSafeInteger(tokens) || tokens < 0 || tokens > 1_000_000)
    throw new BridgeError('invalid_decisions_usage', 'Decisions 사용량을 확인하지 못했습니다.', 502);
  return {model:value.model,answers,usage:{input_tokens:tokens,output_tokens:0},billingMode:'paid_api'};
}
async function jsonResponse(response, signal) {
  if (response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json' || !response.body)
    throw new BridgeError('invalid_decisions_result','Decisions 응답 형식을 확인하지 못했습니다.',502);
  let text = '', bytes = 0; const decoder = new TextDecoder('utf-8',{fatal:true});
  for await (const chunk of response.body) {
    signal.throwIfAborted(); bytes += chunk.length;
    if (bytes > 262_144) throw new BridgeError('response_too_large','Decisions 응답이 너무 큽니다.',502);
    text += decoder.decode(chunk,{stream:true});
  }
  try { return JSON.parse(text+decoder.decode()); }
  catch { throw new BridgeError('invalid_decisions_result','Decisions 응답 형식을 확인하지 못했습니다.',502); }
}
export function createDecisionApi({getKey,getUsage,saveUsage,fetcher=fetch,now=()=>Date.now(),dailyLimitUsd=.50,queueTimeoutMs=4000}) {
  if (!Number.isFinite(dailyLimitUsd) || dailyLimitUsd <= 0 || dailyLimitUsd > .50) throw Error('Invalid Decisions budget');
  if (!Number.isFinite(queueTimeoutMs) || queueTimeoutMs <= 0 || queueTimeoutMs > 5000) throw Error('Invalid queue timeout');
  let active = false, blocked = false;
  const waiting = [];
  function acquire(signal) {
    signal.throwIfAborted();
    if (!active) { active = true; return Promise.resolve(); }
    if (waiting.length >= 4) throw new BridgeError('guide_busy','다른 안내 요청이 많습니다. 잠시 뒤 다시 요청해주세요.',429);
    return new Promise((resolve,reject) => {
      let timer;
      const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort',cancel); };
      const remove = () => { const index=waiting.indexOf(ticket); if(index>=0) waiting.splice(index,1); };
      const cancel = () => { remove();cleanup();reject(signal.reason ?? new DOMException('Cancelled','AbortError')); };
      const ticket = {signal,grant:()=>{cleanup();resolve();}};
      timer=setTimeout(()=>{remove();cleanup();reject(new BridgeError('guide_busy','다른 안내를 처리 중입니다. 잠시 뒤 다시 요청해주세요.',429));},queueTimeoutMs);
      signal.addEventListener('abort',cancel,{once:true});waiting.push(ticket);
      if(signal.aborted)cancel();
    });
  }
  function release() {
    const next=waiting.shift();
    if(next) next.grant();
    else active=false;
  }
  function ledger() {
    const value = getUsage();
    if (value != null && (!/^\d{4}-\d{2}-\d{2}$/.test(value.day) || !Number.isFinite(value.spentUsd) || value.spentUsd < 0 ||
        !Number.isSafeInteger(value.requests) || value.requests < 0)) throw new BridgeError('invalid_ledger','서버 사용량 기록을 확인해주세요.',503);
    return value?.day === day(now()) ? {...value} : {day:day(now()),spentUsd:0,requests:0};
  }
  function status() {
    const usage = ledger(), keyReady = /^sk-[A-Za-z0-9_-]{20,}$/.test(getKey()??'');
    const ready = keyReady && !blocked && usage.spentUsd < dailyLimitUsd;
    return {ready,models:[MODEL],model:MODEL,billingMode:'paid_api',dailyLimitUsd,spentUsd:usage.spentUsd,requests:usage.requests,
      message:!keyReady?'서버의 Decisions API 키를 확인해주세요.':blocked?'서버의 Decisions 인증·사용량을 확인해주세요.':
        usage.spentUsd >= dailyLimitUsd?'오늘의 서버 Decisions 한도 $0.50에 도달했습니다.':'Decisions 서버 연결 준비 완료 · 하루 $0.50 한도'};
  }
  async function execute(value, controller) {
    const request = validateDecisionRequest(value);
    if (!status().ready) throw new BridgeError('not_ready',status().message,409);
    const signal = AbortSignal.any([controller.signal,AbortSignal.timeout(30_000)]);
    await acquire(signal);
    try {
      signal.throwIfAborted();
      if (!status().ready) throw new BridgeError('not_ready',status().message,409);
      const baseline = ledger(), body = JSON.stringify(request), reservedUsd = (Buffer.byteLength(body)+16_384)*RATE;
      if (baseline.spentUsd+reservedUsd > dailyLimitUsd) throw new BridgeError('budget_limit','오늘 남은 서버 Decisions 한도로 이 요청을 처리할 수 없습니다.',429);
      const reserved = {...baseline,spentUsd:baseline.spentUsd+reservedUsd,requests:baseline.requests+1};
      // 저장에 실패하면 과금 호출을 보내지 않는다. 전송 후 결과가 불명확하면 예약 비용을 유지한다.
      await saveUsage(reserved); signal.throwIfAborted();
      const began = performance.now();
      const response = await fetcher('https://api.openai.com/v1/decisions',{method:'POST',redirect:'error',signal,
        headers:{Authorization:'Bearer '+getKey(),'Content-Type':'application/json',Accept:'application/json'},body});
      if (!response.ok) {
        await response.body?.cancel().catch(()=>{});
        if ([401,403].includes(response.status)) blocked = true;
        throw new BridgeError('decisions_upstream',response.status===429?'Decisions 잔액·사용 한도를 확인해주세요. 자동 전환하지 않습니다.':
          'Decisions API 요청을 완료하지 못했습니다. 자동 재시도하지 않습니다.',response.status===429?429:503);
      }
      const raw = await jsonResponse(response,signal), upstreamMs = Math.round(performance.now()-began), tokens = raw.usage?.input_tokens;
      if (!Number.isSafeInteger(tokens) || tokens < 0 || tokens > 1_000_000) throw new BridgeError('invalid_decisions_usage','Decisions 사용량을 확인하지 못했습니다.',502);
      const costUsd = tokens*RATE;
      try { await saveUsage({...reserved,spentUsd:baseline.spentUsd+costUsd}); } catch { blocked = true; throw new BridgeError('usage_storage','서버 비용 기록 저장을 확인해주세요.',503); }
      if (costUsd > reservedUsd || baseline.spentUsd+costUsd > dailyLimitUsd) { blocked = true; throw new BridgeError('budget_limit','예상 사용량을 초과해 추가 Decisions 요청을 중단했습니다.',429); }
      const result = decisionResult(raw,request); signal.throwIfAborted();
      return {...result,apl:{upstreamMs,costUsd,billingMode:'paid_api',transport:'raspberry_pi'}};
    } finally { release(); }
  }
  return {status,execute};
}
