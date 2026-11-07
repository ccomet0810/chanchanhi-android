import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { generateKeyPair, exportJWK, SignJWT } from 'jose';
import { createBridge, accountReady } from '../server.mjs';

// 이 값들은 테스트 전용 가짜 문자열입니다. 실제 페어링/계정 자료를 읽지 않습니다.
const PAIRING = 'fixture-phone-token';
const nodes = [{ id: 2, label: '연결', clickable: true, scrollable: false }];
const input = { goal: '와이파이 켜줘', step: 0, snapshotId: 8, packageName: 'com.android.settings', nodes };
const answer = { action: 'highlight', targetNodeId: 2, message: '연결을 눌러주세요.' };
function state(confirmed = false) {
  return { version: 1, hostId: 'urn:uuid:fixture-host', activeClientId: 'oaiapp_fixture', accounts: [{
    clientId: 'oaiapp_fixture', subject: 'fixture-sub', label: '연결 1', email: 'fixture@example.invalid',
    connected: true, costConfirmed: confirmed, scopes: ['resource.invoke', 'chatgpt.tokens.use.direct'],
    credentials: { accessToken: 'fixture-access', refreshToken: 'fixture-refresh', expiresAt: Date.now() + 3600_000 },
    models: [{ slug: 'fixture-model', displayName: '테스트 모델' }], model: 'fixture-model',
  }] };
}
function stream(value = answer) {
  return new Response(`data: ${JSON.stringify({ type: 'response.output_text.delta', delta: JSON.stringify(value) })}\n\n`+
    `data: ${JSON.stringify({ type: 'response.completed', response: { status: 'completed' } })}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } });
}
async function fixture(t, { confirmed = false, fetcher, now, initialState } = {}) {
  let count = 0; const writes = []; const requests = [];
  const mockFetch = async (url, options) => {
    count++; requests.push({ url: String(url), options });
    if (fetcher) return fetcher(url, options);
    if (String(url).endsWith('/responses')) return stream();
    if (String(url).endsWith('/models')) return Response.json({ models: [{ slug: 'fixture-model', display_name: '테스트 모델', visibility: 'list' }] });
    throw Error('Unexpected mock route');
  };
  const store = { write: async value => writes.push(structuredClone(value)) };
  const bridge = createBridge({ store, initialState: initialState ?? state(confirmed), pairingToken: PAIRING, fetcher: mockFetch, port: 0, ...(now ? { now } : {}) });
  const origin = await bridge.listen(); t.after(() => bridge.close());
  const page = await (await fetch(origin)).text();
  const csrf = JSON.parse(page.match(/const csrf=("[^"]+");/)[1]);
  const phone = (path, body, token = PAIRING) => fetch(origin + path, { method: body ? 'POST' : 'GET', headers: {
    Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}),
  }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const ui = (path, body = {}, headerChanges = {}) => fetch(origin + path, { method: 'POST', headers: {
    Origin: origin, 'Content-Type': 'application/json', 'X-APL-CSRF': csrf, ...headerChanges,
  }, body: JSON.stringify(body) });
  return { origin, phone, ui, writes, requests, count: () => count };
}

test('모든 ready 조건을 확인하고 비용 확인 전에는 AI 네트워크 호출이 없다', async t => {
  const f = await fixture(t);
  assert.equal(accountReady(state(false).accounts[0]), false);
  assert.equal((await (await f.phone('/api/status')).json()).ready, false);
  const result = await f.phone('/api/guide', input);
  assert.equal(result.status, 409);
  assert.equal((await f.phone('/api/guide', { ...input, packageName: 'com.samsung.android.messaging', appLabel: '메시지', goal: '메시지를 보내고 싶다' })).status, 409);
  assert.equal(f.count(), 0);
  const confirm = await f.ui('/ui/cost-confirm', { confirmed: true });
  assert.equal(confirm.status, 200);
  assert.equal((await (await f.phone('/api/status')).json()).ready, true);
  assert.equal(f.count(), 0);
  const guide = await f.phone('/api/guide', input);
  assert.deepEqual(await guide.json(), answer);
  assert.equal(f.count(), 1);
  const body = JSON.parse(f.requests[0].options.body);
  assert.equal(body.store, false); assert.equal(body.stream, true);
  assert.equal('max_output_tokens' in body, false);
});

test('메시지·쇼핑 앱의 현재 노드와 앱 이름만 추론 요청으로 전달한다', async t => {
  let currentTime = Date.now();
  const f = await fixture(t, { confirmed: true, now: () => currentTime });
  const cases = [
    { packageName: 'com.samsung.android.messaging', appLabel: '메시지', goal: '메시지를 보내고 싶다' },
    { packageName: 'com.coupang.mobile', appLabel: '쿠팡', goal: '운동화를 찾아보고 싶어' },
  ];
  for (const target of cases) {
    const result = await f.phone('/api/guide', { ...input, ...target,
      nodes: [{ ...nodes[0], bounds: [0, 0, 10, 10], typedText: 'fixture-private-editable-content' }] });
    assert.equal(result.status, 200);
    assert.deepEqual(await result.json(), answer);
    const body = JSON.parse(f.requests.at(-1).options.body);
    const actual = JSON.parse(body.input[0].content);
    assert.equal(actual.packageName, target.packageName);
    assert.equal(actual.appLabel, target.appLabel);
    assert.equal(actual.goal, target.goal);
    assert.deepEqual(actual.nodes, nodes);
    assert.equal(body.store, false); assert.equal(body.stream, true);
    assert.equal(JSON.stringify(body).includes('fixture-private-editable-content'), false);
    currentTime += 4000;
  }
  assert.equal(f.count(), 2);
});

test('제외 앱·무효 노드는 추론 전에 거부하고 타 앱 인증 목표도 수동으로 종료한다', async t => {
  const f = await fixture(t, { confirmed: true });
  for (const change of [{ packageName: 'com.example.aplassistmvp0' }, { packageName: 'com.android.systemui' },
    { packageName: 'com.sec.android.app.launcher' }, { nodes: [{ ...nodes[0], id: -1 }] },
    { nodes: [{ ...nodes[0], label: '' }] }, { nodes: [...nodes, ...nodes] }]) {
    assert.equal((await f.phone('/api/guide', { ...input, ...change })).status, 400);
  }
  const manual = await f.phone('/api/guide', { ...input, packageName: 'com.samsung.android.messaging',
    appLabel: '메시지', goal: '인증번호를 입력해줘' });
  assert.equal(manual.status, 200);
  assert.equal((await manual.json()).action, 'manual');
  assert.equal(f.count(), 0);
});

test('public health 외에는 폰 페어링을 검증하고 CSRF/Origin/Host를 검사한다', async t => {
  const f = await fixture(t);
  assert.deepEqual(await (await fetch(f.origin + '/health')).json(), { app: 'APL ChatGPT Bridge' });
  assert.equal((await f.phone('/api/status', null, 'wrong')).status, 401);
  assert.equal((await f.ui('/ui/cost-confirm', { confirmed: true }, { 'X-APL-CSRF': 'wrong' })).status, 403);
  assert.equal((await f.ui('/ui/cost-confirm', { confirmed: true }, { Origin: 'https://evil.invalid' })).status, 403);
  // Node fetch는 Host 헤더를 원래 주소로 정규화하므로 실제 HTTP 요청으로 공격 헤더를 보냅니다.
  const wrongHostStatus = await new Promise((resolve, reject) => {
    const request = httpRequest(f.origin + '/health', { headers: { Host: 'evil.invalid' } }, response => { response.resume(); resolve(response.statusCode); });
    request.once('error', reject); request.end();
  });
  assert.equal(wrongHostStatus, 403);
  assert.equal(f.count(), 0);
});

test('민감한 실제 목표는 AI 호출 없이 manual이며 일반 요청은 잘못된 대상이면 실패한다', async t => {
  const f = await fixture(t, { confirmed: true, fetcher: async () => stream({ ...answer, targetNodeId: 999 }) });
  const manual = await f.phone('/api/guide', { ...input, goal: '공장 초기화 하고 싶어' });
  assert.equal((await manual.json()).action, 'manual'); assert.equal(f.count(), 0);
  const invalid = await f.phone('/api/guide', input);
  assert.equal(invalid.status, 502); assert.equal((await invalid.json()).error, 'invalid_ai_target');
});

test('연속 호출은 3초 간격이며 8번째 이후/과대 요청은 모델로 보내지 않는다', async t => {
  const f = await fixture(t, { confirmed: true });
  assert.equal((await f.phone('/api/guide', input)).status, 200);
  assert.equal((await f.phone('/api/guide', input)).status, 429);
  assert.equal((await f.phone('/api/guide', { ...input, step: 8 })).status, 400);
  const oversized = await fetch(f.origin + '/api/guide', { method: 'POST', headers: { Authorization: `Bearer ${PAIRING}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ value: 'x'.repeat(66_000) }) });
  assert.equal(oversized.status, 413); assert.equal(f.count(), 1);
});

test('새 계정 로그인 시작 시 기존 비용 확인을 즉시 초기화하며 인증만 시작한다', async t => {
  const f = await fixture(t, { confirmed: true });
  const response = await f.ui('/ui/connect', { newAccount: true });
  assert.equal(response.status, 200);
  const auth = new URL((await response.json()).url);
  assert.equal(auth.searchParams.get('client_id'), 'dynamic_agent_client');
  assert.equal((await (await f.phone('/api/status')).json()).ready, false);
  assert.equal(f.count(), 0);
  const callback = await fetch(f.origin + '/auth/callback?state=wrong&code=fixture&client_id=oaiapp_fixture');
  assert.equal(callback.status, 400); assert.equal(f.count(), 0);
});

test('사용량 한도 실패는 모델 호출 한 번으로 종료하고 API 과금 경로로 전환하지 않는다', async t => {
  const f = await fixture(t, { confirmed: true, fetcher: async () => Response.json({ error: { code: 'subscription_sharing_usage_limit_exceeded' } }, { status: 429 }) });
  const result = await f.phone('/api/guide', input);
  assert.equal(result.status, 429); assert.equal((await result.json()).error, 'usage_limit'); assert.equal(f.count(), 1);
  assert.equal(f.requests[0].url, 'https://api.openai.com/v1/responses');
});

test('동시에 두 요청을 보내면 하나만 모델로 전달한다', async t => {
  let release; const waiting = new Promise(resolve => { release = resolve; });
  const f = await fixture(t, { confirmed: true, fetcher: async () => { await waiting; return stream(); } });
  const first = f.phone('/api/guide', input);
  while (!f.count()) await new Promise(resolve => setTimeout(resolve, 5));
  const second = await f.phone('/api/guide', input);
  assert.equal(second.status, 429); assert.equal((await second.json()).error, 'guide_busy');
  release(); assert.equal((await first).status, 200); assert.equal(f.count(), 1);
});

test('폰이 취소하면 OpenAI 요청에 전달한 AbortSignal도 중단된다', async t => {
  let receivedSignal;
  let notified; const started = new Promise(resolve => { notified = resolve; });
  const f = await fixture(t, { confirmed: true, fetcher: async (_url, options) => {
    receivedSignal = options.signal; notified();
    return new Promise((_resolve, reject) => options.signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')), { once: true }));
  } });
  const cancellation = new AbortController();
  const result = fetch(f.origin + '/api/guide', { method: 'POST', headers: { Authorization: `Bearer ${PAIRING}`, 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal: cancellation.signal });
  await started; cancellation.abort();
  await assert.rejects(result);
  for (let attempt = 0; attempt < 20 && !receivedSignal.aborted; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(receivedSignal.aborted, true);
});

test('동시 모델 확인은 갱신을 직렬화하고 교체 토큰을 한 번만 사용한다', async t => {
  const initial = state(false);
  initial.accounts[0].credentials.expiresAt = Date.now() - 1000;
  let tokenCalls = 0;
  const f = await fixture(t, { initialState: initial, fetcher: async (url, options) => {
    if (String(url).endsWith('/.well-known/openid-configuration')) return Response.json({ issuer: 'https://auth.openai.com',
      authorization_endpoint: 'https://auth.openai.com/api/accounts/authorize', token_endpoint: 'https://auth.openai.com/api/accounts/oauth/token',
      revocation_endpoint: 'https://auth.openai.com/api/accounts/oauth/revoke', jwks_uri: 'https://auth.openai.com/fixture-jwks' });
    if (String(url).endsWith('/oauth/token')) {
      tokenCalls++;
      assert.equal(options.body.get('client_id'), 'oaiapp_fixture');
      assert.equal(options.body.get('refresh_token'), 'fixture-refresh');
      return Response.json({ access_token: 'fixture-new-access', refresh_token: 'fixture-new-refresh', token_type: 'Bearer', expires_in: 3600,
        scope: 'resource.invoke chatgpt.tokens.use.direct offline_access' });
    }
    if (String(url).endsWith('/models')) return Response.json({ models: [
      { slug: 'fixture-first', display_name: '첫 모델', visibility: 'list' },
      { slug: 'fixture-hidden', display_name: '숨김', visibility: 'hidden' },
      { slug: 'fixture-second', display_name: '둘째 모델', visibility: 'list' },
    ] });
    throw Error('Unexpected mock route');
  } });
  const responses = await Promise.all([f.ui('/ui/refresh-models'), f.ui('/ui/refresh-models')]);
  assert.equal(responses.every(response => response.status === 200), true);
  assert.equal(tokenCalls, 1);
  const final = f.writes.at(-1).accounts[0];
  assert.equal(final.credentials.refreshToken, 'fixture-new-refresh');
  assert.equal(final.pendingRefresh, undefined);
  assert.deepEqual(final.models.map(model => model.slug), ['fixture-first', 'fixture-second']);
  assert.equal(final.model, 'fixture-first');
  assert.equal(final.costConfirmed, false);
});

test('모의 전체 OAuth는 발급 ID·검증된 계정·모델을 저장하고 비용 확인 전 추론하지 않는다', async t => {
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'fixture-signing-key', alg: 'RS256', use: 'sig' };
  let expectedNonce;
  const initialState = { version: 1, hostId: 'urn:uuid:fixture-host', activeClientId: null, accounts: [] };
  const f = await fixture(t, { initialState, fetcher: async (url, options) => {
    const address = String(url);
    if (address.includes('.well-known/openid-configuration')) return Response.json({ issuer: 'https://auth.openai.com',
      authorization_endpoint: 'https://auth.openai.com/api/accounts/authorize', token_endpoint: 'https://auth.openai.com/api/accounts/oauth/token',
      revocation_endpoint: 'https://auth.openai.com/api/accounts/oauth/revoke', jwks_uri: 'https://auth.openai.com/fixture-jwks' });
    if (address.endsWith('/fixture-jwks')) return Response.json({ keys: [jwk] });
    if (address.endsWith('/oauth/token')) {
      assert.equal(options.body.get('grant_type'), 'authorization_code');
      assert.equal(options.body.get('client_id'), 'oaiapp_new_fixture');
      assert.equal(options.body.get('redirect_uri'), f.origin + '/auth/callback');
      assert.equal(options.body.get('code_verifier').length, 43);
      const idToken = await new SignJWT({ nonce: expectedNonce, email: 'fixture@example.invalid' })
        .setProtectedHeader({ alg: 'RS256', kid: jwk.kid }).setIssuer('https://auth.openai.com')
        .setAudience('oaiapp_new_fixture').setSubject('fixture-sub').setIssuedAt().setExpirationTime('5m').sign(privateKey);
      return Response.json({ access_token: 'fixture-access', refresh_token: 'fixture-refresh', id_token: idToken,
        token_type: 'Bearer', expires_in: 3600, scope: 'openid profile email offline_access resource.invoke chatgpt.tokens.use.direct' });
    }
    if (address.endsWith('/models')) return Response.json({ models: [{ slug: 'fixture-model', display_name: '테스트 모델', visibility: 'list' }] });
    throw Error('Inference must not be called before explicit cost confirmation');
  } });
  const auth = new URL((await (await f.ui('/ui/connect')).json()).url);
  expectedNonce = auth.searchParams.get('nonce');
  const callback = new URL(f.origin + '/auth/callback');
  callback.search = new URLSearchParams({ state: auth.searchParams.get('state'), code: 'fixture-code', client_id: 'oaiapp_new_fixture' });
  assert.equal((await fetch(callback, { redirect: 'manual' })).status, 303);
  const saved = f.writes.at(-1).accounts[0];
  assert.equal(saved.clientId, 'oaiapp_new_fixture');
  assert.equal(saved.subject, 'fixture-sub');
  assert.equal(saved.connected, true);
  assert.equal(typeof saved.idToken, 'string');
  assert.equal(saved.costConfirmed, false);
  assert.equal(saved.model, 'fixture-model');
  assert.equal((await (await f.phone('/api/status')).json()).ready, false);
  assert.equal((await f.phone('/api/guide', input)).status, 409);
  assert.equal(f.requests.some(request => request.url.endsWith('/responses')), false);
  const ui = await (await fetch(f.origin + '/ui/state')).json();
  assert.equal(JSON.stringify(ui).includes('fixture-access'), false);
  assert.equal(JSON.stringify(ui).includes('fixture-refresh'), false);
});
