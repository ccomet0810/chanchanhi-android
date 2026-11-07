import { createServer as createHttpsServer } from 'node:https';
import { createServer as createHttpServer } from 'node:http';
import { isIP } from 'node:net';

const REQUEST_LIMIT = 262_144;
const RESPONSE_LIMIT = 262_144;
const API_METHODS = new Map([['/api/status','GET'],['/api/guide','POST'],['/api/model-status','GET'],['/api/model-response','POST'],
  ['/api/decisions-status','GET'],['/api/decisions-response','POST']]);

class ProxyError extends Error {
  constructor(code, message, status) { super(message); this.code = code; this.status = status; }
}

const failure = (code, message, status) => new ProxyError(code, message, status);
const isJson = value => typeof value === 'string' && value.split(';', 1)[0].trim().toLowerCase() === 'application/json';
const isObject = value => Boolean(value && typeof value === 'object' && !Array.isArray(value));
const isText = (value, limit) => typeof value === 'string' && Boolean(value.trim()) && value.length <= limit && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value);

// 모의 fetch가 취소를 무시하더라도 요청 작업 자체는 제한 시간 안에 끝냅니다.
function abortable(operation, signal) {
  return new Promise((resolve, reject) => {
    const cleanup = () => signal.removeEventListener('abort', abort);
    const abort = () => { cleanup(); reject(signal.reason ?? new DOMException('Cancelled', 'AbortError')); };
    operation.then(value => { cleanup(); resolve(value); }, error => { cleanup(); reject(error); });
    if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
  });
}

function readRequest(request, signal, limit) {
  return new Promise((resolve, reject) => {
    const chunks = []; let length = 0;
    const cleanup = () => {
      request.removeListener('data', data); request.removeListener('end', end); request.removeListener('error', error);
      signal.removeEventListener('abort', abort);
    };
    const fail = reason => { cleanup(); request.pause(); reject(reason); };
    const error = () => fail(failure('invalid_body', '요청 본문을 읽지 못했습니다.', 400));
    const abort = () => fail(signal.reason ?? new DOMException('Cancelled', 'AbortError'));
    const data = chunk => {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      length += bytes.length;
      if (length > limit) return fail(failure('body_too_large', '요청이 허용 크기를 초과했습니다.', 413));
      chunks.push(bytes);
    };
    const end = () => { cleanup(); resolve(Buffer.concat(chunks, length)); };
    if (signal.aborted) return abort();
    request.on('data', data); request.once('end', end); request.once('error', error);
    signal.addEventListener('abort', abort, { once: true });
    request.resume();
  });
}

async function readUpstream(response, signal) {
  const rejectBody = async () => {
    await response.body?.cancel().catch(() => {});
    throw failure('invalid_upstream', '서버 응답 형식을 확인하지 못했습니다.', 502);
  };
  if (!isJson(response.headers?.get('content-type')) || !response.body) return rejectBody();
  const declared = response.headers.get('content-length');
  if (declared && (!/^\d+$/.test(declared) || Number(declared) > RESPONSE_LIMIT)) return rejectBody();
  const reader = response.body.getReader(); const chunks = []; let length = 0;
  try {
    for (;;) {
      const { done, value } = await abortable(reader.read(), signal);
      if (done) break;
      length += value.length;
      if (length > RESPONSE_LIMIT) throw failure('invalid_upstream', '서버 응답 형식을 확인하지 못했습니다.', 502);
      chunks.push(Buffer.from(value));
    }
    let value;
    try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks, length))); }
    catch { throw failure('invalid_upstream', '서버 응답 형식을 확인하지 못했습니다.', 502); }
    if (!isObject(value)) throw failure('invalid_upstream', '서버 응답 형식을 확인하지 못했습니다.', 502);
    return value;
  } finally {
    // 응답 원문은 기록하지 않고, 실패·취소 때 남은 본문도 폐기합니다.
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

// 고정 로컬 서버가 돌려준 JSON도 폰 계약의 허용 필드만 새 객체로 복사합니다.
function phoneResponse(path, status, value, requestedModel) {
  if (status >= 400 && status <= 599 && typeof value.error === 'string' && /^[a-z][a-z0-9_]{0,79}$/.test(value.error) && isText(value.message, 500)) {
    return { error: value.error, message: value.message };
  }
  if (status === 200 && ['/api/model-status','/api/decisions-status'].includes(path) && typeof value.ready === 'boolean' &&
      isText(value.message,500) && Array.isArray(value.models) && value.models.length <= 100 && value.models.every(m=>isText(m,200))) {
    return {ready:value.ready,message:value.message,models:value.models,billingMode:path.includes('decisions')?'paid_api':'chatgpt_subscription'};
  }
  if (status === 200 && ['/api/model-response','/api/decisions-response'].includes(path)) {
    const subscription = path === '/api/model-response', model = subscription?requestedModel:'gpt-6-luna';
    const tokens = value.usage;
    if (subscription && !['gpt-6.1-sol','gpt-6.1-terra'].includes(model) ||
        typeof value.model !== 'string' || !(value.model===model || value.model.startsWith(model+'-')) ||
        !tokens || !['input_tokens','output_tokens'].every(k=>Number.isSafeInteger(tokens[k])&&tokens[k]>=0&&tokens[k]<=1_000_000) ||
        !Number.isSafeInteger(value.apl?.upstreamMs) || value.apl.upstreamMs<0 || value.apl.upstreamMs>60_000 ||
        !Number.isFinite(value.apl?.costUsd) || value.apl.costUsd<0 || value.apl.costUsd>.50)
      throw failure('invalid_upstream','서버 응답 형식을 확인하지 못했습니다.',502);
    const base={model:value.model,usage:{input_tokens:tokens.input_tokens,output_tokens:tokens.output_tokens},
      billingMode:subscription?'chatgpt_subscription':'paid_api',apl:{upstreamMs:value.apl.upstreamMs,costUsd:value.apl.costUsd,
        billingMode:subscription?'chatgpt_subscription':'paid_api',transport:'raspberry_pi'}};
    if (subscription && value.status==='completed' && Array.isArray(value.output)) {
      const text=value.output.filter(r=>r.type==='message').flatMap(r=>r.content??[]).filter(p=>p.type==='output_text');
      if (text.length===1 && isText(text[0].text,16_384)) return {...base,status:'completed',
        output:[{type:'message',content:[{type:'output_text',text:text[0].text}]}]};
    }
    if (!subscription && Array.isArray(value.answers) && value.answers.length>0 && value.answers.length<=200 && value.answers.every(a=>
      isText(a.name,200)&&a.type==='choice'&&isText(a.choice,200))) return {...base,answers:value.answers.map(a=>({
        name:a.name,type:'choice',choice:a.choice,...(typeof a.confidence==='number'?{confidence:a.confidence}:{}),
        ...(Array.isArray(a.probabilities)?{probabilities:a.probabilities.filter(p=>typeof p.value==='string'&&Number.isFinite(p.probability)&&p.probability>=0&&p.probability<=1)
          .slice(0,255).map(p=>({value:p.value,probability:p.probability}))}:{} )}))};
    throw failure('invalid_upstream','서버 응답 형식을 확인하지 못했습니다.',502);
  }
  if (status === 200 && path === '/api/status' && typeof value.ready === 'boolean' && isText(value.message, 500) &&
      (value.model === undefined || isText(value.model, 200))) {
    return { ready: value.ready, message: value.message, ...(value.model === undefined ? {} : { model: value.model }) };
  }
  if (status === 200 && path === '/api/guide' && ['highlight', 'manual', 'done'].includes(value.action) && isText(value.message, 300) &&
      (value.action === 'highlight' ? Number.isSafeInteger(value.targetNodeId) && value.targetNodeId >= 0 : value.targetNodeId === null)) {
    return { action: value.action, targetNodeId: value.targetNodeId, message: value.message };
  }
  throw failure('invalid_upstream', '서버 응답 형식을 확인하지 못했습니다.', 502);
}

/**
 * 개인 VM의 HTTPS 폰 전용 입구입니다. 파일·계정·페어링 자료를 읽거나 서버를 자동 실행하지 않습니다.
 * OAuth/UI를 담당하는 별도 브리지는 같은 호스트의 127.0.0.1 포트에만 둡니다.
 */
export function createPersonalApiServer({ tls, publicOrigin, bindHost, port, localPort = 18765, timeoutMs = 35_000,
  maxConcurrent = 32, fetcher = fetch, serverFactory }) {
  let address;
  try { address = new URL(publicOrigin); } catch { throw new TypeError('유효한 개인 HTTPS origin이 필요합니다.'); }
  if (address.protocol !== 'https:' || address.username || address.password || address.pathname !== '/' || address.search || address.hash) throw new TypeError('개인 HTTPS origin에는 경로·인증·쿼리를 넣을 수 없습니다.');
  if (typeof bindHost !== 'string' || !(isIP(bindHost) || bindHost === 'localhost')) throw new TypeError('리스너 bindHost를 IP 또는 localhost로 명시해주세요.');
  const loopbackHttp = tls === undefined || tls === null;
  // 기존 Nginx가 HTTPS를 종료할 때만 HTTP를 사용하며, 공개 HTTP 바인딩은 허용하지 않습니다.
  if (loopbackHttp && bindHost !== '127.0.0.1') throw new TypeError('TLS 없는 gateway는 127.0.0.1에만 바인딩할 수 있습니다.');
  const listenPort = port ?? (loopbackHttp ? 18766 : Number(address.port || 443));
  if (!Number.isInteger(listenPort) || listenPort < 0 || listenPort > 65535) throw new TypeError('유효한 HTTPS 리스너 포트가 필요합니다.');
  if (!Number.isInteger(localPort) || localPort < 1 || localPort > 65535) throw new TypeError('유효한 로컬 브리지 포트가 필요합니다.');
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60_000 || !Number.isInteger(maxConcurrent) || maxConcurrent < 1 || maxConcurrent > 128) throw new TypeError('요청 제한 설정이 올바르지 않습니다.');
  if (!loopbackHttp && (!tls || !((typeof tls.key === 'string' && tls.key) || (Buffer.isBuffer(tls.key) && tls.key.length)) ||
      !((typeof tls.cert === 'string' && tls.cert) || (Buffer.isBuffer(tls.cert) && tls.cert.length)))) throw new TypeError('호출자가 TLS key와 cert를 제공해야 합니다.');
  const origin = address.origin;
  const localOrigin = `http://127.0.0.1:${localPort}`;
  const active = new Set();
  let closing = false;

  function send(response, status, value) {
    if (response.destroyed || response.writableEnded) return;
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff', ...(status >= 400 ? { Connection: 'close' } : {}) });
    response.end(JSON.stringify(value));
  }

  const handler = async (request, response) => {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(new DOMException('Timed out', 'TimeoutError')), timeoutMs);
    const abort = () => controller.abort(new DOMException('Cancelled', 'AbortError'));
    const disconnected = () => { if (!response.writableEnded) abort(); };
    request.once('aborted', abort); response.once('close', disconnected);
    // 연결이 끊긴 뒤 IncomingMessage가 내는 오류도 본문/비밀 출력 없이 처리합니다.
    request.once('error', abort);
    let registered = false;
    try {
      if (closing) throw failure('unavailable', '서버가 종료 중입니다. 잠시 뒤 다시 연결해주세요.', 503);
      if (loopbackHttp ? !['127.0.0.1', '::ffff:127.0.0.1'].includes(request.socket?.remoteAddress) : request.socket?.encrypted !== true) {
        throw failure('https_required', 'HTTPS 또는 서버 내부 연결만 사용할 수 있습니다.', 403);
      }
      if (request.headers.host !== address.host) throw failure('invalid_host', '등록된 개인 서버 주소로 접속해주세요.', 403);
      if (request.headers.origin !== undefined && request.headers.origin !== origin) throw failure('invalid_origin', '허용되지 않은 요청입니다.', 403);
      // raw 경로를 그대로 비교하므로 쿼리·인코딩 우회·절대 URL로 UI/OAuth에 진입할 수 없습니다.
      const method = API_METHODS.get(request.url);
      if (!method) throw failure('not_found', '지원하지 않는 폰 요청입니다.', 404);
      if (request.method !== method) throw failure('method_not_allowed', '지원하지 않는 요청 방식입니다.', 405);
      const authorization = request.headers.authorization;
      if (typeof authorization !== 'string' || !/^Bearer [A-Za-z0-9_-]{1,256}$/.test(authorization)) throw failure('invalid_pairing', '이 폰의 서버 연결을 확인할 수 없습니다.', 401);
      if (active.size >= maxConcurrent) throw failure('busy', '서버가 다른 요청을 처리 중입니다. 잠시 뒤 다시 요청해주세요.', 429);
      active.add(controller); registered = true;
      let body, requestedModel;
      const contentLength = request.headers['content-length'];
      if (contentLength !== undefined && (typeof contentLength !== 'string' || !/^\d+$/.test(contentLength) || !Number.isSafeInteger(Number(contentLength)))) throw failure('invalid_body', '요청 본문 형식이 올바르지 않습니다.', 400);
      if (request.method === 'GET') {
        if (Number(contentLength ?? 0) > 0 || request.headers['transfer-encoding'] !== undefined) throw failure('invalid_body', '상태 조회에는 요청 본문을 사용할 수 없습니다.', 400);
      } else {
        if (!isJson(request.headers['content-type'])) throw failure('invalid_content_type', 'JSON 요청만 처리할 수 있습니다.', 415);
        const limit = ['/api/model-response','/api/decisions-response'].includes(request.url) ? REQUEST_LIMIT : 65_536;
        if (Number(contentLength ?? 0) > limit) throw failure('body_too_large', '요청이 허용 크기를 초과했습니다.', 413);
        body = await readRequest(request, controller.signal, limit);
        if (contentLength !== undefined && Number(contentLength) !== body.length) throw failure('invalid_body', '요청 본문 형식이 올바르지 않습니다.', 400);
        try {
          const parsed = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body));
          if (!isObject(parsed)) throw new Error();
          if (request.url === '/api/model-response') requestedModel = parsed.model;
        } catch { throw failure('invalid_json', '요청 JSON 형식이 올바르지 않습니다.', 400); }
      }
      // 인증은 원본 브리지에서 검사합니다. 외부 헤더·쿠키·Origin·전달 주소는 복사하지 않습니다.
      const upstream = await abortable(Promise.resolve().then(() => {
        controller.signal.throwIfAborted();
        return fetcher(localOrigin + request.url, {
        method, headers: { Host: `127.0.0.1:${localPort}`, Authorization: authorization, Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        ...(body === undefined ? {} : { body }), redirect: 'error', signal: controller.signal,
        });
      }), controller.signal);
      const value = await readUpstream(upstream, controller.signal);
      controller.signal.throwIfAborted();
      send(response, upstream.status, phoneResponse(request.url, upstream.status, value, requestedModel));
    } catch (error) {
      const typed = error instanceof ProxyError ? error : error?.name === 'TimeoutError' ?
        failure('timeout', '서버 안내 요청 제한 시간을 초과했습니다. 자동 재시도는 하지 않습니다.', 504) : error?.name === 'AbortError' ?
        failure('cancelled', '안내 요청이 중단되었습니다.', 409) : failure('upstream_unavailable', '개인 서버의 안내 연결을 완료하지 못했습니다.', 502);
      // 외부 오류 원문·본문·Bearer·계정 정보는 응답이나 로그로 내보내지 않습니다.
      send(response, typed.status, { error: typed.code, message: typed.message });
    } finally {
      clearTimeout(timeout);
      request.removeListener('aborted', abort); response.removeListener('close', disconnected);
      if (registered) active.delete(controller);
    }
  };

  const makeServer = serverFactory ?? (loopbackHttp ? createHttpServer : createHttpsServer);
  const server = loopbackHttp ? makeServer(handler) :
    makeServer({ key: tls.key, cert: tls.cert, ...(tls.ca ? { ca: tls.ca } : {}), minVersion: 'TLSv1.2' }, handler);
  server.requestTimeout = timeoutMs;
  server.headersTimeout = Math.min(10_000, timeoutMs);
  return { server, origin, listen: () => new Promise((resolve, reject) => {
    if (closing) return reject(new Error('종료한 개인 서버를 시작할 수 없습니다.'));
    const error = reason => { server.removeListener('error', error); reject(reason); };
    server.once('error', error);
    server.listen(listenPort, bindHost, () => { server.removeListener('error', error); resolve(origin); });
  }), close: () => new Promise(resolve => {
    closing = true;
    for (const controller of active) controller.abort(new DOMException('Cancelled', 'AbortError'));
    server.closeAllConnections();
    server.close(resolve);
  }) };
}
