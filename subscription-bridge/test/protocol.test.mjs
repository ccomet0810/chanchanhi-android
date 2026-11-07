import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPair, exportJWK, SignJWT } from 'jose';
import { OpenAiProtocol, ISSUER, makeAuthorization, validateCallback, tokenFields,
  validateGuideInput, validateGuideResult, readResponseStream, sensitiveGoal } from '../protocol.mjs';

const nodes = [{ id: 2, label: '연결', clickable: true, scrollable: false }];
const guide = { goal: '와이파이 켜줘', step: 0, snapshotId: 8, packageName: 'com.android.settings', nodes };
const answer = { action: 'highlight', targetNodeId: 2, message: '연결을 눌러주세요.' };
function sse(events, fragmented = false, contentType = 'text/event-stream') {
  const raw = events.map(value => `data: ${JSON.stringify(value)}\r\n\r\n`).join('');
  const bytes = new TextEncoder().encode(raw);
  return new Response(new ReadableStream({ start(controller) {
    if (fragmented) for (const value of bytes) controller.enqueue(Uint8Array.of(value));
    else controller.enqueue(bytes);
    controller.close();
  } }), { headers: contentType === null ? {} : { 'Content-Type': contentType } });
}
const completed = text => [{ type: 'response.output_text.delta', delta: text }, { type: 'response.completed', response: { status: 'completed' } }];

test('실제 첫 단계는 0이며 선택 앱과 제한된 노드만 허용한다', () => {
  assert.deepEqual(validateGuideInput(guide), guide);
  for (const change of [{ step: 8 }, { step: -1 }, { packageName: 'https://example.invalid' }, { goal: 'x'.repeat(501) },
    { nodes: [] }, { nodes: [...nodes, ...nodes] }, { nodes: [{ ...nodes[0], label: '' }] },
    { nodes: [{ ...nodes[0], clickable: 'true' }] }, { nodes: [{ ...nodes[0], id: -1 }] },
    { nodes: Array.from({ length: 121 }, (_, id) => ({ ...nodes[0], id })) }]) {
    assert.throws(() => validateGuideInput({ ...guide, ...change }));
  }
  const clean = validateGuideInput({ ...guide, nodes: [{ ...nodes[0], bounds: [0, 0, 10, 10], url: 'https://example.invalid' }] });
  assert.equal('bounds' in clean.nodes[0], false);
  assert.equal('url' in clean.nodes[0], false);
});

test('실제 타 앱 패키지·이름은 허용하고 APL·시스템 UI·HOME 및 잘못된 형식은 거부한다', () => {
  for (const packageName of ['com.android.settings', 'com.android.chrome', 'com.samsung.android.messaging', 'com.coupang.mobile']) {
    const clean = validateGuideInput({ ...guide, packageName, appLabel: ' 선택한 앱 ' });
    assert.equal(clean.packageName, packageName);
    assert.equal(clean.appLabel, '선택한 앱');
  }
  for (const packageName of ['android', 'com.android.systemui', 'com.example.aplassistmvp0', 'com.sec.android.app.launcher',
    'com.google.android.apps.nexuslauncher', 'com.android.launcher3', '', null, 'com.app/other', 'com..app', 'com.app\n', 'com.' + 'x'.repeat(252)]) {
    assert.throws(() => validateGuideInput({ ...guide, packageName }), { code: 'invalid_guide' });
  }
  for (const appLabel of ['', null, 3, 'x'.repeat(81), '앱\n명']) {
    assert.throws(() => validateGuideInput({ ...guide, appLabel }), { code: 'invalid_guide' });
  }
});

test('일반 메시지·쇼핑 탐색은 허용하며 인증번호·송금·결제 목표는 수동으로 제한한다', () => {
  for (const goal of ['메시지를 보내고 싶다', '새 메시지 작성 화면을 찾아줘', '쿠팡에서 운동화를 찾아보고 싶어', '상품 검색을 하고 싶어']) {
    assert.equal(sensitiveGoal(goal), false);
  }
  for (const goal of ['인증번호 입력해줘', 'OTP 입력해줘', '비밀번호 알려줘', '돈을 송금해줘', '자동 결제해줘', '공장 초기화']) {
    assert.equal(sensitiveGoal(goal), true);
  }
});

test('AI의 존재하지 않거나 클릭할 수 없는 대상은 거부한다', () => {
  assert.deepEqual(validateGuideResult(answer, nodes), answer);
  assert.throws(() => validateGuideResult({ ...answer, targetNodeId: 999 }, nodes), { code: 'invalid_ai_target' });
  assert.throws(() => validateGuideResult(answer, [{ ...nodes[0], clickable: false }]), { code: 'invalid_ai_target' });
  assert.throws(() => validateGuideResult({ action: 'done', targetNodeId: 2, message: '완료' }, nodes));
  assert.throws(() => validateGuideResult({ ...answer, arbitraryAction: 'tap' }, nodes));
  assert.equal(validateGuideResult(answer, [{ ...nodes[0], label: '공장 초기화' }]).action, 'manual');
});

test('완료 이벤트와 잘게 나뉜 CRLF/한국어 UTF-8을 처리한다', async () => {
  const text = JSON.stringify(answer);
  assert.equal(await readResponseStream(sse(completed(text), true)), text);
});

test('Content-Type이 없거나 틀려도 실제 SSE 완료 이벤트를 검증한다', async () => {
  const text = JSON.stringify(answer);
  for (const contentType of [null, 'application/json', 'text/plain; charset=utf-8']) {
    assert.equal(await readResponseStream(sse(completed(text), true, contentType)), text);
    await assert.rejects(readResponseStream(sse([{ type: 'response.output_text.delta', delta: text }], false, contentType)), { code: 'missing_completed' });
    await assert.rejects(readResponseStream(sse([{ type: 'response.incomplete' }], false, contentType)), { code: 'incomplete_response' });
    await assert.rejects(readResponseStream(sse([{ type: 'response.failed', response: { error: { code: 'subscription_sharing_usage_limit_exceeded' } } }], false, contentType)), { code: 'usage_limit' });
  }
});

test('HTTP 200의 JSON completed는 SSE 완료가 아니며 허용된 진단만 남긴다', async () => {
  const secret = 'fixture-private-response-content';
  const response = Response.json({ object: 'response', status: 'completed', id: secret,
    output: [{ content: [{ type: 'output_text', text: secret }] }], [secret]: secret },
  { headers: { 'x-request-id': 'req_fixture-diagnostic' } });
  await assert.rejects(readResponseStream(response), error => {
    assert.equal(error.code, 'invalid_stream');
    assert.deepEqual(error.details, { httpStatus: 200, contentType: 'application/json', bodyKind: 'json',
      jsonType: 'object', jsonKeys: ['id', 'object', 'output', 'status'], jsonStatus: 'completed', requestId: 'req_fixture-diagnostic' });
    assert.equal(JSON.stringify(error.details).includes(secret), false);
    return true;
  });
});

test('HTML·임의 헤더·알 수 없는 JSON 상태는 원문을 진단에 포함하지 않는다', async () => {
  const secret = 'fixture-private-error-content';
  const html = new Response(`<!DOCTYPE html><html><body>${secret}</body></html>`,
    { headers: { 'content-type': 'text/html; private=' + secret, 'x-request-id': secret } });
  await assert.rejects(readResponseStream(html), error => {
    assert.deepEqual(error.details, { httpStatus: 200, contentType: 'text/html', bodyKind: 'html' });
    assert.equal(JSON.stringify(error.details).includes(secret), false);
    return error.code === 'invalid_stream';
  });
  const json = new Response(JSON.stringify({ status: secret, detail: secret, [secret]: secret }),
    { headers: { 'content-type': 'application/' + secret } });
  await assert.rejects(readResponseStream(json), error => {
    assert.deepEqual(error.details, { httpStatus: 200, contentType: 'other', bodyKind: 'json', jsonType: 'object', jsonKeys: ['detail', 'status'] });
    assert.equal(JSON.stringify(error.details).includes(secret), false);
    return error.code === 'invalid_stream';
  });
});

test('비-SSE 응답도 최대 읽기 크기를 넘으면 중단한다', async () => {
  await assert.rejects(readResponseStream(new Response('x'.repeat(1024 * 1024 + 1))), { code: 'response_too_large' });
});

test('델타 뒤 실패, 미완료, 완료 누락은 성공으로 사용하지 않는다', async () => {
  const text = JSON.stringify(answer);
  await assert.rejects(readResponseStream(sse([{ type: 'response.output_text.delta', delta: text }])), { code: 'missing_completed' });
  await assert.rejects(readResponseStream(sse([{ type: 'response.output_text.delta', delta: text }, { type: 'response.failed', response: { error: { code: 'subscription_sharing_usage_limit_exceeded' } } }])), { code: 'usage_limit', status: 429 });
  await assert.rejects(readResponseStream(sse([{ type: 'response.incomplete' }])), { code: 'incomplete_response' });
  await assert.rejects(readResponseStream(sse([{ type: 'response.completed', response: { status: 'incomplete' } }])), { code: 'incomplete_response' });
});

test('동적 등록·PKCE·호스트 ID와 동일 콜백 URI를 사용한다', () => {
  const { pending, url } = makeAuthorization({ origin: 'http://127.0.0.1:18765', appName: 'APL 스마트폰 안내', hostId: 'urn:uuid:fixture', now: 1000 });
  const auth = new URL(url);
  assert.equal(auth.searchParams.get('client_id'), 'dynamic_agent_client');
  assert.equal(auth.searchParams.get('redirect_uri'), 'http://127.0.0.1:18765/auth/callback');
  assert.equal(auth.searchParams.get('ext_agent_host_id'), 'urn:uuid:fixture');
  assert.equal(auth.searchParams.get('code_challenge_method'), 'S256');
  const callback = new URL(`http://127.0.0.1:18765/auth/callback?state=${pending.state}&code=fixture-code&client_id=oaiapp_fixture`);
  assert.deepEqual(validateCallback(callback, pending, 1001), { code: 'fixture-code', clientId: 'oaiapp_fixture' });
  callback.searchParams.set('state', 'wrong');
  assert.throws(() => validateCallback(callback, pending, 1001), { code: 'invalid_oauth_state' });
});

test('콜백 중복 state·client ID 바꿔치기·만료·미발급 ID를 거부한다', () => {
  const { pending } = makeAuthorization({ origin: 'http://127.0.0.1:18765', appName: 'APL', hostId: 'urn:uuid:fixture', account: { clientId: 'oaiapp_original', subject: 'fixture-sub' }, now: 1000 });
  const callback = new URL(`http://127.0.0.1:18765/auth/callback?state=${pending.state}&code=fixture-code&client_id=oaiapp_other`);
  assert.throws(() => validateCallback(callback, pending, 1001), { code: 'invalid_client' });
  callback.searchParams.set('client_id', 'oaiapp_original');
  callback.searchParams.append('state', pending.state);
  assert.throws(() => validateCallback(callback, pending, 1001), { code: 'invalid_oauth_state' });
  callback.searchParams.delete('state'); callback.searchParams.set('state', pending.state);
  assert.throws(() => validateCallback(callback, pending, 601001), { code: 'invalid_oauth_state' });
  callback.searchParams.set('client_id', 'dynamic_agent_client');
  assert.throws(() => validateCallback(callback, pending, 1001), { code: 'invalid_client' });
});

test('모의 JWKS로 ID 토큰 서명·issuer·audience·nonce를 검증한다', async () => {
  const { privateKey, publicKey } = await generateKeyPair('RS256');
  const jwk = { ...(await exportJWK(publicKey)), kid: 'fixture-key', alg: 'RS256', use: 'sig' };
  const fetcher = async address => {
    const value = String(address);
    if (value.includes('.well-known/openid-configuration')) return Response.json({ issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/api/accounts/authorize`, token_endpoint: `${ISSUER}/api/accounts/oauth/token`,
      revocation_endpoint: `${ISSUER}/api/accounts/oauth/revoke`, jwks_uri: `${ISSUER}/fixture-jwks` });
    if (value.endsWith('/fixture-jwks')) return Response.json({ keys: [jwk] });
    throw Error('Unexpected mock route');
  };
  const protocol = new OpenAiProtocol(fetcher);
  const token = await new SignJWT({ nonce: 'fixture-nonce', email: 'fixture@example.invalid' }).setProtectedHeader({ alg: 'RS256', kid: jwk.kid })
    .setIssuer(ISSUER).setAudience('oaiapp_fixture').setSubject('fixture-sub').setIssuedAt().setExpirationTime('5m').sign(privateKey);
  assert.equal((await protocol.identity(token, 'oaiapp_fixture', { nonce: 'fixture-nonce' })).subject, 'fixture-sub');
  await assert.rejects(protocol.identity(token, 'oaiapp_fixture', { nonce: 'wrong' }), { code: 'invalid_identity' });
  await assert.rejects(protocol.identity(token, 'oaiapp_other', { nonce: 'fixture-nonce' }), { code: 'invalid_identity' });
});

test('토큰 자료에는 반환된 권한과 갱신 토큰이 필요하다', () => {
  assert.throws(() => tokenFields({ access_token: 'fixture', token_type: 'bearer', expires_in: 3600 }));
  assert.throws(() => tokenFields({ access_token: 'fixture', token_type: 'bearer', expires_in: 3600, scope: 'offline_access' }));
  assert.equal(tokenFields({ access_token: 'fixture', refresh_token: 'fixture-refresh', token_type: 'Bearer', expires_in: 3600, scope: 'offline_access chatgpt.tokens.use.direct' }).scopes.includes('chatgpt.tokens.use.direct'), true);
});
