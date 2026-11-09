import { createServer } from 'node:http';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { access } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { SecureStore, loadPairingToken } from './storage.mjs';
import { VmSecureStore, loadVmPairingToken } from './vm-storage.mjs';
import { renderPage } from './page.mjs';
import { validateModelRequest, modelResult } from './model-contract.mjs';
import { createDecisionApi } from './decisions-api.mjs';
import { BridgeError, OpenAiProtocol, makeAuthorization, validateCallback, tokenFields,
  randomSecret, equalSecret, RESOURCE, validateGuideInput, validateGuideResult,
  sensitiveGoal, readResponseStream, GUIDE_INSTRUCTIONS } from './protocol.mjs';

const APP_NAME = 'APL 스마트폰 안내';
const PUBLIC_APP = 'APL ChatGPT Bridge';
const PORT = 18765;
const terminalRefreshErrors = new Set(['invalid_grant', 'invalid_refresh_token', 'token_expired', 'refresh_token_expired', 'refresh_token_invalidated', 'refresh_token_reused']);

// 요청 경로는 allowPendingRefresh로 들어와 accessToken()이 남은 갱신을 먼저 마무리하게 한다.
// 그렇지 않으면 일시적인 서명 확인 실패 뒤 pendingRefresh가 남아 모든 안내가 영구히 차단된다.
export function accountReady(account, { allowPendingRefresh = false } = {}) {
  return Boolean(account?.connected && account.credentials && account.scopes?.includes('chatgpt.tokens.use.direct') &&
    account.scopes.includes('resource.invoke') && account.costConfirmed && account.model && account.models?.some(model => model.slug === account.model) &&
    (allowPendingRefresh || !account.pendingRefresh));
}

// 테스트는 모의 저장소를 주입한다. Windows는 DPAPI, Linux 개인 서버는 보호된 암호화 저장소를 쓴다.
export function createBridge({ store, initialState, pairingToken, fetcher = fetch, port = PORT, now = () => Date.now(), appName = APP_NAME }) {
  const protocol = new OpenAiProtocol(fetcher);
  const state = structuredClone(initialState);
  const csrfToken = randomSecret();
  let pending = null; let epoch = 0; let mutation = Promise.resolve();
  let activeGuide = null; let lastGuideStart = -Infinity;
  let lastMessage = '';
  const account = () => state.accounts.find(item => item.clientId === state.activeClientId);
  const origin = () => `http://127.0.0.1:${server.address()?.port ?? port}`;
  const persist = () => store.write(state);
  const serialize = operation => {
    const result = mutation.then(operation);
    mutation = result.catch(() => {});
    return result;
  };
  const decisions = createDecisionApi({getKey:()=>state.decisionsApiKey,getUsage:()=>state.decisionsUsage,fetcher,now,
    saveUsage:usage=>serialize(async()=>{state.decisionsUsage=usage;await persist();})});
  const invalidate = () => { epoch++; activeGuide?.abort(); };

  // 갱신 토큰은 교체 저장을 끝내야 하므로 내부 갱신을 강제로 끊지 않습니다.
  // 호출자는 제한 시간이나 폰 취소 때 즉시 빠져나오며 뒤늦은 갱신 후에도 추론을 보내지 않습니다.
  function abortable(operation, signal) {
    return new Promise((resolve, reject) => {
      const abort = () => { cleanup(); reject(signal.reason ?? new DOMException('Cancelled', 'AbortError')); };
      const cleanup = () => signal.removeEventListener('abort', abort);
      operation.then(value => { cleanup(); resolve(value); }, error => { cleanup(); reject(error); });
      if (signal.aborted) abort(); else signal.addEventListener('abort', abort, { once: true });
    });
  }

  function uiState() {
    const selected = account();
    const ready = accountReady(selected);
    const message = lastMessage || (ready ? '연결 완료 · 폰에서 음성 안내를 시작할 수 있습니다.' :
      !selected?.connected ? 'ChatGPT 계정을 연결해주세요. AI 요청은 보내지 않습니다.' :
      !selected.scopes.includes('chatgpt.tokens.use.direct') ? '구독 사용 권한이 없습니다. 연결을 다시 확인해주세요.' :
      !selected.model ? '사용 가능한 모델을 확인해주세요. AI 요청은 차단되어 있습니다.' :
      selected.pendingRefresh && selected.costConfirmed ? '인증 갱신을 마무리하지 못했습니다. 다음 안내 요청이나 모델 목록 새로 확인 때 다시 시도합니다.' :
      '크레딧 사용 중지 설정을 확인해야 AI 안내를 활성화할 수 있습니다.');
    return { ready, message, connected: Boolean(selected?.connected),
      account: selected ? { email: selected.email, name: selected.name, label: selected.label } : null,
      accounts: state.accounts.map(item => ({ clientId: item.clientId, label: item.label, email: item.email, active: item.clientId === state.activeClientId })),
      models: selected?.models ?? [], model: selected?.model ?? null,
      costConfirmed: Boolean(selected?.costConfirmed),
      canConfirm: Boolean(selected?.connected && selected.credentials && selected.scopes.includes('chatgpt.tokens.use.direct') && selected.scopes.includes('resource.invoke') && selected.model && !pending),
    };
  }

  async function finishPendingRefresh(selected) {
    const rotation = selected.pendingRefresh;
    if (!rotation) return;
    if (rotation.idToken) {
      const identity = await protocol.identity(rotation.idToken, selected.clientId, { receivedAt: rotation.receivedAt });
      if (identity.subject !== selected.subject) throw new BridgeError('account_mismatch', '갱신된 계정이 기존 연결 계정과 다릅니다.', 401);
      Object.assign(selected, identity);
      selected.idToken = rotation.idToken;
    }
    selected.credentials = rotation.credentials;
    selected.scopes = rotation.scopes;
    delete selected.pendingRefresh;
    await persist();
  }

  async function accessToken(expectedClientId, requireCost = false) {
    return serialize(async () => {
      const selected = account();
      if (!selected || selected.clientId !== expectedClientId || !selected.connected || !selected.credentials ||
          !selected.scopes.includes('chatgpt.tokens.use.direct') || (requireCost && !selected.costConfirmed)) throw new BridgeError('not_ready', '관리 화면에서 ChatGPT 연결과 크레딧 설정을 먼저 확인해주세요.', 409);
      try {
        await finishPendingRefresh(selected);
        if (selected.credentials.expiresAt <= now() + 60_000) {
          const earliest = selected.credentials.earliestRefreshAt;
          const earliestMs = typeof earliest === 'number' ? earliest * 1000 : typeof earliest === 'string' ? Date.parse(earliest) : 0;
          if (Number.isFinite(earliestMs) && earliestMs > now()) throw new BridgeError('refresh_not_ready', '인증 갱신 대기 중입니다. 잠시 뒤 직접 다시 요청해주세요.', 503);
          if (!selected.credentials.refreshToken) throw new BridgeError('invalid_grant', '인증 연결이 만료됐습니다. 다시 연결해주세요.', 401);
          const response = await protocol.token({ grant_type: 'refresh_token', client_id: selected.clientId,
            refresh_token: selected.credentials.refreshToken, resource: RESOURCE });
          const rotation = tokenFields(response, selected.scopes);
          // 교체된 refresh token을 먼저 암호화 저장합니다. 서명 확인 실패 때 이전 토큰을 재사용하지 않습니다.
          selected.pendingRefresh = { ...rotation, ...(response.id_token ? { idToken: response.id_token } : {}), receivedAt: now() };
          await persist();
          await finishPendingRefresh(selected);
        }
      } catch (error) {
        if (terminalRefreshErrors.has(error.code) || ['account_mismatch', 'invalid_tokens'].includes(error.code)) {
          selected.credentials = null; selected.connected = false; selected.costConfirmed = false;
          delete selected.pendingRefresh; invalidate(); await persist();
        }
        throw error;
      }
      return selected.credentials.accessToken;
    });
  }

  async function refreshModels(clientId) {
    const operationEpoch = epoch;
    const token = await accessToken(clientId);
    const models = await protocol.models(token, AbortSignal.timeout(20_000));
    await serialize(async () => {
      if (operationEpoch !== epoch || account()?.clientId !== clientId) throw new BridgeError('connection_changed', '계정 연결이 바뀌었습니다. 다시 확인해주세요.', 409);
      const selected = account();
      selected.models = models;
      if (!models.some(model => model.slug === selected.model)) selected.model = models[0]?.slug ?? null;
      if (!selected.model) selected.costConfirmed = false;
      await persist();
    });
  }

  async function beginSignIn(body) {
    return serialize(async () => {
      invalidate(); lastMessage = '';
      const active = account();
      // 다른 계정 로그인 중에도 이전 계정의 비용 확인으로 요청이 통과하지 않도록 먼저 잠급니다.
      if (active) active.costConfirmed = false;
      const selected = body.newAccount === true ? null : active;
      await persist();
      const result = makeAuthorization({ origin: origin(), appName, hostId: state.hostId,
        account: selected, now: now() });
      pending = result.pending;
      // 로그인 URL은 UI 응답에만 전달하며 서버 로그에는 기록하지 않습니다.
      return { url: result.url };
    });
  }

  async function callback(url) {
    const callbackPending = pending;
    const result = validateCallback(url, callbackPending, now());
    await serialize(async () => {
      if (pending !== callbackPending) throw new BridgeError('connection_changed', '새 로그인 요청이 시작되었습니다. 다시 확인해주세요.', 409);
      pending = null; invalidate(); lastMessage = '';
      let selected = state.accounts.find(item => item.clientId === result.clientId);
      if (!selected) {
        selected = { clientId: result.clientId, label: `연결 ${state.accounts.length + 1}`, connected: false,
          credentials: null, scopes: [], models: [], model: null, costConfirmed: false };
        state.accounts.push(selected);
      }
      // 교환 실패 후에도 최초 발급 ID를 보존하여 재로그인 때 중복 앱을 만들지 않습니다.
      state.activeClientId = selected.clientId;
      selected.costConfirmed = false;
      await persist();
      const data = await protocol.token({ grant_type: 'authorization_code', client_id: result.clientId,
        code: result.code, code_verifier: callbackPending.verifier, redirect_uri: callbackPending.redirectUri, resource: RESOURCE });
      const identity = await protocol.identity(data.id_token, result.clientId, { nonce: callbackPending.nonce });
      if ((callbackPending.expectedSubject && callbackPending.expectedSubject !== identity.subject) ||
          (selected.subject && selected.subject !== identity.subject)) throw new BridgeError('account_mismatch', '기존 연결 계정과 로그인한 계정이 다릅니다.', 401);
      const fields = tokenFields(data);
      Object.assign(selected, identity, fields, { idToken: data.id_token, connected: true, models: [], model: null, costConfirmed: false });
      delete selected.pendingRefresh;
      await persist();
    });
    if (account()?.scopes.includes('chatgpt.tokens.use.direct')) await refreshModels(result.clientId);
  }

  async function guide(value, controller) {
    const input = validateGuideInput(value);
    const selected = account();
    if (!accountReady(selected, { allowPendingRefresh: true })) throw new BridgeError('not_ready', '관리 화면에서 ChatGPT 연결과 크레딧 설정을 먼저 확인해주세요. AI 요청은 보내지 않았습니다.', 409);
    if (activeGuide) throw new BridgeError('guide_busy', '이전 안내가 끝날 때까지 기다려주세요.', 429);
    if (now() - lastGuideStart < 3000) throw new BridgeError('guide_rate_limit', '3초 뒤 다시 요청해주세요. AI 요청은 보내지 않았습니다.', 429);
    if (sensitiveGoal(input.goal)) return { action: 'manual', targetNodeId: null,
      message: '비밀번호·인증번호 입력, 송금·결제·초기화 같은 민감한 작업은 직접 확인해주세요. 해당 동작을 대신하지 않습니다.' };
    activeGuide = controller; lastGuideStart = now();
    const operationEpoch = epoch;
    try {
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30_000)]);
      const token = await abortable(accessToken(selected.clientId, true), signal);
      signal.throwIfAborted();
      if (operationEpoch !== epoch || !accountReady(account())) throw new BridgeError('connection_changed', '계정 연결이 변경되어 요청을 중단했습니다.', 409);
      const response = await fetcher(`${RESOURCE}/responses`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` }, signal,
        body: JSON.stringify({ model: selected.model, instructions: GUIDE_INSTRUCTIONS,
          input: [{ role: 'user', content: JSON.stringify(input) }], store: false, stream: true }),
      });
      const text = await readResponseStream(response, signal);
      if (operationEpoch !== epoch) throw new BridgeError('connection_changed', '계정 연결이 변경되어 안내를 적용하지 않았습니다.', 409);
      let result;
      try { result = JSON.parse(text); } catch { throw new BridgeError('invalid_ai_result', 'AI가 올바른 안내 형식을 반환하지 않았습니다. 안내를 적용하지 않았습니다.', 502); }
      return validateGuideResult(result, input.nodes);
    } finally { if (activeGuide === controller) activeGuide = null; }
  }

  async function modelResponse(value, controller) {
    const input = validateModelRequest(value);
    const selected = account();
    if (!accountReady(selected, { allowPendingRefresh: true })) throw new BridgeError('not_ready', 'PC 연결 화면에서 ChatGPT 계정과 크레딧 설정을 확인해주세요.', 409);
    if (!selected.models.some(model => model.slug === input.model)) throw new BridgeError('model_unavailable', '구독 계정에서 선택한 모델을 사용할 수 없습니다.', 403);
    if (activeGuide) throw new BridgeError('guide_busy', '이전 구독 요청이 끝나면 다시 시작해주세요.', 429);
    if (now() - lastGuideStart < 350) throw new BridgeError('guide_rate_limit', '잠시 뒤 직접 다시 요청해주세요.', 429);
    activeGuide = controller; lastGuideStart = now();
    const operationEpoch = epoch;
    try {
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30_000)]);
      const token = await abortable(accessToken(selected.clientId, true), signal);
      signal.throwIfAborted();
      if (operationEpoch !== epoch || !accountReady(account())) throw new BridgeError('connection_changed', '구독 연결이 변경됐습니다.', 409);
      const began = performance.now();
      const response = await fetcher(`${RESOURCE}/responses`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
        signal, body: JSON.stringify(input),
      });
      const completed = await readResponseStream(response, signal, true);
      if (operationEpoch !== epoch) throw new BridgeError('connection_changed', '구독 연결이 변경돼 안내를 적용하지 않았습니다.', 409);
      try { return {...modelResult(completed, input), apl:{upstreamMs:Math.round(performance.now()-began),costUsd:0,
        billingMode:'chatgpt_subscription',transport:'raspberry_pi'}}; }
      catch (error) {
        // 값·본문은 남기지 않는다. 구독 응답의 필드 존재 여부와 자료형만 확인한다.
        const allowed = new Set(['message','reasoning','output_text','refusal']);
        console.error('APL subscription shape: ' + JSON.stringify({
          hasModel: typeof completed.model === 'string', modelMatches: completed.model === input.model,
          output: (completed.output ?? []).slice(0,5).map(row => ({type:allowed.has(row.type)?row.type:'other',
            content:(row.content??[]).slice(0,3).map(p=>({type:allowed.has(p.type)?p.type:'other',length:typeof p.text==='string'?p.text.length:null}))})),
          hasUsage: !!completed.usage,
        }));
        throw error;
      }
    } finally { if (activeGuide === controller) activeGuide = null; }
  }

  async function jsonBody(request) {
    const limit = ['/api/model-response', '/api/decisions-response'].includes(new URL(request.url, 'http://localhost').pathname) ? 262_144 : 65_536;
    if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) throw new BridgeError('invalid_content_type', 'JSON 요청만 처리할 수 있습니다.', 415);
    if (Number(request.headers['content-length']) > limit) throw new BridgeError('body_too_large', '요청이 허용 크기를 초과했습니다.', 413);
    const parts = []; let length = 0;
    for await (const part of request) {
      length += part.length;
      if (length > limit) throw new BridgeError('body_too_large', '요청이 허용 크기를 초과했습니다.', 413);
      parts.push(part);
    }
    try {
      const parsed = JSON.parse(Buffer.concat(parts).toString('utf8'));
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Invalid object');
      return parsed;
    } catch { throw new BridgeError('invalid_json', '요청 JSON 형식이 올바르지 않습니다.'); }
  }

  function sendJson(response, status, value) {
    if (response.destroyed) return;
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    response.end(JSON.stringify(value));
  }

  const server = createServer(async (request, response) => {
    const controller = new AbortController();
    request.once('aborted', () => controller.abort());
    response.once('close', () => { if (!response.writableEnded) controller.abort(); });
    try {
      // loopback 바인딩과 Host 검증을 함께 사용해 외부 접속 및 DNS 재바인딩을 거부합니다.
      if (request.headers.host !== new URL(origin()).host) throw new BridgeError('invalid_host', '허용된 로컬 관리 주소로 접속해주세요.', 403);
      const url = new URL(request.url, origin());
      if (request.method === 'GET' && url.pathname === '/health') return sendJson(response, 200, { app: PUBLIC_APP });
      if (url.pathname.startsWith('/api/')) {
        if (!equalSecret(request.headers.authorization, `Bearer ${pairingToken}`)) throw new BridgeError('invalid_pairing', '이 폰의 서버 연결을 확인할 수 없습니다.', 401);
        if (request.headers.origin && request.headers.origin !== origin()) throw new BridgeError('invalid_origin', '허용되지 않은 요청입니다.', 403);
        if (request.method === 'GET' && url.pathname === '/api/model-status') {
          const status = uiState();
          return sendJson(response, 200, { ready: status.ready, message: status.message, billingMode: 'chatgpt_subscription',
            models: status.models.map(model => model.slug) });
        }
        if (request.method === 'POST' && url.pathname === '/api/model-response') return sendJson(response, 200, await modelResponse(await jsonBody(request), controller));
        if (request.method === 'GET' && url.pathname === '/api/decisions-status') return sendJson(response, 200, decisions.status());
        if (request.method === 'POST' && url.pathname === '/api/decisions-response') return sendJson(response, 200, await decisions.execute(await jsonBody(request), controller));
        if (request.method === 'GET' && url.pathname === '/api/status') {
          const status = uiState();
          return sendJson(response, 200, { ready: status.ready, message: status.message, ...(status.model ? { model: status.model } : {}) });
        }
        if (request.method === 'POST' && url.pathname === '/api/guide') return sendJson(response, 200, await guide(await jsonBody(request), controller));
        throw new BridgeError('not_found', '지원하지 않는 폰 요청입니다.', 404);
      }
      if (request.method === 'GET' && url.pathname === '/') {
        const nonce = randomSecret();
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
          'Content-Security-Policy': `default-src 'self'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'` });
        return response.end(renderPage(csrfToken, nonce, appName));
      }
      if (request.method === 'GET' && url.pathname === '/auth/callback') {
        await callback(url);
        response.writeHead(303, { Location: '/', 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' });
        return response.end();
      }
      if (request.method === 'GET' && url.pathname === '/ui/state') return sendJson(response, 200, uiState());
      if (request.method === 'POST' && url.pathname.startsWith('/ui/')) {
        if (request.headers.origin !== origin() || !equalSecret(request.headers['x-apl-csrf'], csrfToken)) throw new BridgeError('invalid_csrf', '로컬 관리 페이지에서 다시 요청해주세요.', 403);
        const body = await jsonBody(request);
        if (url.pathname === '/ui/connect') return sendJson(response, 200, await beginSignIn(body));
        if (url.pathname === '/ui/cost-confirm') {
          await serialize(async () => {
            if (body.confirmed !== true || !uiState().canConfirm) throw new BridgeError('not_ready', '연결과 모델을 먼저 확인해주세요.', 409);
            account().costConfirmed = true; account().costConfirmedAt = new Date(now()).toISOString();
            lastMessage = ''; await persist();
          });
        } else if (url.pathname === '/ui/model') {
          await serialize(async () => {
            if (!account()?.models.some(model => model.slug === body.model)) throw new BridgeError('invalid_model', '계정에서 사용할 수 있는 모델을 선택해주세요.');
            invalidate(); account().model = body.model; lastMessage = ''; await persist();
          });
        } else if (url.pathname === '/ui/refresh-models') {
          if (!account()) throw new BridgeError('not_ready', '계정을 먼저 연결해주세요.', 409);
          await refreshModels(account().clientId); lastMessage = '';
        } else if (url.pathname === '/ui/select-account') {
          await serialize(async () => {
            const selected = state.accounts.find(item => item.clientId === body.clientId);
            if (!selected) throw new BridgeError('unknown_account', '저장된 계정을 찾을 수 없습니다.');
            invalidate(); pending = null; state.activeClientId = selected.clientId; selected.costConfirmed = false;
            lastMessage = ''; await persist();
          });
        } else if (url.pathname === '/ui/disconnect') {
          await serialize(async () => {
            invalidate(); pending = null;
            const selected = account();
            if (selected) {
              // 원격 해제 응답을 기다리는 동안에도 새 안내를 차단합니다.
              selected.costConfirmed = false; selected.connected = false;
              await persist();
              let revokeError;
              try { await protocol.revoke(selected.pendingRefresh?.credentials.refreshToken ?? selected.credentials?.refreshToken, selected.clientId); }
              catch (error) { revokeError = error; }
              selected.credentials = null; selected.connected = false; selected.scopes = [];
              selected.costConfirmed = false; selected.models = []; selected.model = null;
              delete selected.idToken;
              delete selected.pendingRefresh;
              await persist();
              if (revokeError) throw revokeError;
            }
            lastMessage = 'ChatGPT 연결을 해제했습니다. AI 요청은 차단되어 있습니다.';
          });
        } else throw new BridgeError('not_found', '지원하지 않는 관리 요청입니다.', 404);
        return sendJson(response, 200, { ok: true });
      }
      throw new BridgeError('not_found', '요청한 페이지가 없습니다.', 404);
    } catch (error) {
      if (error instanceof BridgeError) console.error('APL subscription error: ' + JSON.stringify({ code: error.code, httpStatus: error.status }));
      const typed = error instanceof BridgeError ? error :
        error.name === 'TimeoutError' ? new BridgeError('timeout', '30초 안에 AI 안내를 완료하지 못했습니다. 자동 재시도는 하지 않습니다.', 504) :
        error.name === 'AbortError' ? new BridgeError('cancelled', '안내 요청이 중단되었습니다.', 409) :
        new BridgeError('bridge_error', '서버 연결 요청을 완료하지 못했습니다. 기존 인증 자료는 유지합니다.', 500);
      // 형식 진단은 Protocol이 허용한 상태·자료형 정보만 기록한다. 응답 원문과 인증값은 남기지 않는다.
      if (typed.details) console.error('APL upstream diagnostic:', JSON.stringify(typed.details));
      if (urlIsCallback(request.url)) {
        lastMessage = typed.message;
        // 브라우저의 OAuth 실패도 연결 화면에서 읽고 다시 시도할 수 있게 보여줍니다.
        if (request.headers.host === new URL(origin()).host && request.headers.accept?.includes('text/html')) {
          const nonce = randomSecret();
          response.writeHead(typed.status, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
            'Content-Security-Policy': `default-src 'self'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'` });
          return response.end(renderPage(csrfToken, nonce, appName));
        }
      }
      sendJson(response, typed.status, { error: typed.code, message: typed.message });
    }
  });
  server.requestTimeout = 35_000;
  server.headersTimeout = 10_000;
  return { server, listen: () => new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(origin()));
  }), close: () => new Promise(resolve => { activeGuide?.abort(); server.closeAllConnections(); server.close(resolve); }) };
}

function urlIsCallback(value) { return typeof value === 'string' && value.split('?')[0] === '/auth/callback'; }

async function start() {
  const directory = join(dirname(fileURLToPath(import.meta.url)), '.local');
  // 이전한 갱신 토큰을 PC와 서버가 동시에 쓰지 않도록 기존 PC 런타임은 차단한다.
  try {
    await access(join(directory, 'remote-session-transferred.json'));
    throw new Error('APL_REMOTE_SESSION_TRANSFERRED');
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const store = process.platform === 'win32' ? new SecureStore(directory) : new VmSecureStore(directory);
  const initialState = await store.initialize();
  const pairingToken = await (process.platform === 'win32' ? loadPairingToken(directory) : loadVmPairingToken(directory));
  const bridge = createBridge({ store, initialState, pairingToken });
  const address = await bridge.listen();
  // 주소만 기록합니다. 로그인 URL, 토큰, 요청 내용, 계정 정보는 출력하지 않습니다.
  console.log(`APL ChatGPT Bridge 준비: ${address}`);
  process.once('SIGINT', () => bridge.close().then(() => process.exit(0)));
  process.once('SIGTERM', () => bridge.close().then(() => process.exit(0)));
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  start().catch(error => {
    console.error(error.message === 'APL_REMOTE_SESSION_TRANSFERRED' ?
      '구독 세션을 개인 서버로 이전했습니다. 폰의 HTTPS 연결을 사용해주세요. PC 연결을 다시 쓰려면 별도 재로그인이 필요합니다.' :
      'APL PC 브리지를 시작하지 못했습니다. 포트 또는 Windows 보안 저장 상태를 확인해주세요.');
    process.exitCode = 1;
  });
}
