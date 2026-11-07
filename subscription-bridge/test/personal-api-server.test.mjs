import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { createPersonalApiServer } from '../personal-api-server.mjs';

// 전부 가짜 값입니다. 서버 생성기와 fetch를 모의로 바꾸므로 소켓·파일·실제 AI를 사용하지 않습니다.
const ORIGIN = 'https://phone.fixture.invalid:8443';
const BEARER = 'Bearer fixture-phone-token';
const GUIDE = { action: 'highlight', targetNodeId: 2, message: '검색 메뉴를 눌러주세요.' };

class FakeServer extends EventEmitter {
  constructor() { super(); this.listenCalls = []; this.connectionsClosed = 0; this.closed = 0; }
  listen(...args) { const callback = args.pop(); this.listenCalls.push(args); queueMicrotask(callback); }
  closeAllConnections() { this.connectionsClosed++; }
  close(callback) { this.closed++; queueMicrotask(callback); }
}

class FakeResponse extends EventEmitter {
  constructor() { super(); this.destroyed = false; this.writableEnded = false; }
  writeHead(status, headers) { this.status = status; this.headers = headers; }
  end(body) { this.body = body; this.writableEnded = true; this.emit('close'); }
  json() { return JSON.parse(this.body); }
}

function fixture(t, options = {}) {
  let handler; let tlsOptions;
  const calls = []; const server = new FakeServer();
  const fetcher = async (url, options) => { calls.push({ url, options }); return Response.json({ ready: true, message: '연결 완료', model: 'fixture-model' }); };
  const proxy = createPersonalApiServer({ tls: { key: 'fixture-key', cert: 'fixture-certificate' }, publicOrigin: ORIGIN,
    bindHost: '127.0.0.1', fetcher, ...options, serverFactory: (...args) => { tlsOptions = args.length === 2 ? args[0] : undefined; handler = args.at(-1); return server; } });
  t.after(() => proxy.close());
  function start({ path = '/api/status', method = 'GET', body, chunks, headers = {}, encrypted = true, remoteAddress = '127.0.0.1', request: custom } = {}) {
    const request = custom ?? Readable.from(chunks ?? (body === undefined ? [] : [Buffer.from(body)]));
    request.method = method; request.url = path;
    request.headers = { host: new URL(ORIGIN).host, authorization: BEARER, ...headers };
    request.socket = { encrypted, remoteAddress };
    const response = new FakeResponse();
    const done = handler(request, response);
    return { request, response, done };
  }
  async function send(input) { const result = start(input); await result.done; return result.response; }
  return { proxy, server, calls, start, send, tlsOptions };
}

function guideInput(extra = {}) {
  return { path: '/api/guide', method: 'POST', body: '{"goal":"메시지를 보내고 싶다"}', headers: { 'content-type': 'application/json' }, ...extra };
}

test('명시 TLS/bind만 사용하며 생성 시 리스너를 열지 않는다', async t => {
  const f = fixture(t, { tls: { key: 'fixture-key', cert: 'fixture-certificate', ca: 'fixture-ca', minVersion: 'TLSv1' }, port: 0 });
  assert.equal(f.server.listenCalls.length, 0);
  assert.deepEqual(f.tlsOptions, { key: 'fixture-key', cert: 'fixture-certificate', ca: 'fixture-ca', minVersion: 'TLSv1.2' });
  assert.equal(f.proxy.origin, ORIGIN);
  assert.equal(await f.proxy.listen(), ORIGIN);
  assert.deepEqual(f.server.listenCalls, [[0, '127.0.0.1']]);
  assert.equal(f.server.requestTimeout, 35_000);
  assert.equal(f.server.headersTimeout, 10_000);
});

test('설정 오류는 서버 생성 전에 거부한다', () => {
  let count = 0;
  const base = { tls: { key: 'fixture-key', cert: 'fixture-certificate' }, publicOrigin: ORIGIN, bindHost: '127.0.0.1',
    serverFactory: () => { count++; return new FakeServer(); } };
  for (const invalid of [{ publicOrigin: 'http://fixture.invalid' }, { publicOrigin: 'https://user:pass@fixture.invalid' },
    { publicOrigin: ORIGIN + '/api' }, { publicOrigin: ORIGIN + '?x=1' }, { publicOrigin: ORIGIN + '#x' },
    { publicOrigin: 'bad origin' }, { bindHost: undefined }, { bindHost: 'attacker.invalid' }, { tls: {} },
    { localPort: 0 }, { localPort: 65536 }, { port: -1 }, { timeoutMs: 0 }, { maxConcurrent: 129 }]) {
    assert.throws(() => createPersonalApiServer({ ...base, ...invalid }), TypeError);
  }
  assert.equal(count, 0);
});

test('Nginx 뒤 HTTP 모드는 127.0.0.1 바인딩과 loopback peer만 허용한다', async t => {
  const f = fixture(t, { tls: undefined });
  assert.equal(f.tlsOptions, undefined);
  assert.equal(await f.proxy.listen(), ORIGIN);
  assert.deepEqual(f.server.listenCalls, [[18766, '127.0.0.1']]);
  assert.equal((await f.send({ encrypted: false })).status, 200);
  assert.equal((await f.send({ encrypted: false, remoteAddress: '::ffff:127.0.0.1' })).status, 200);
  for (const remoteAddress of [undefined, '192.168.1.2', '0.0.0.0', '::1']) {
    const input = { encrypted: false, remoteAddress: remoteAddress ?? null };
    assert.equal((await f.send(input)).status, 403);
  }
  assert.equal((await f.send({ encrypted: false, headers: { host: '127.0.0.1:18766' } })).status, 403);
  assert.equal((await f.send({ encrypted: false, headers: { origin: 'http://127.0.0.1:18766' } })).status, 403);
  assert.equal(f.calls.length, 2);
  for (const bindHost of ['0.0.0.0', '::', '::1', 'localhost', '192.168.1.10']) {
    assert.throws(() => createPersonalApiServer({ publicOrigin: ORIGIN, bindHost }), TypeError);
  }
});

test('상태 요청은 고정 loopback URL에 최소 헤더만 전달하고 계정 필드를 제거한다', async t => {
  const calls = [];
  const f = fixture(t, { fetcher: async (url, options) => {
    calls.push({ url, options });
    return Response.json({ ready: true, message: '연결 완료', model: 'fixture-model', email: 'private-fixture',
      credentials: { accessToken: 'private-fixture' }, accounts: ['private-fixture'] }, { headers: { 'Set-Cookie': 'private-fixture' } });
  } });
  const response = await f.send({ headers: { origin: ORIGIN, cookie: 'private-fixture', 'x-forwarded-host': 'attacker.invalid',
    'x-apl-csrf': 'private-fixture', 'x-forwarded-for': 'private-fixture' } });
  assert.equal(response.status, 200);
  assert.deepEqual(response.json(), { ready: true, message: '연결 완료', model: 'fixture-model' });
  assert.deepEqual(calls[0].options.headers, { Host: '127.0.0.1:18765', Authorization: BEARER, Accept: 'application/json' });
  assert.equal(calls[0].url, 'http://127.0.0.1:18765/api/status');
  assert.equal(calls[0].options.redirect, 'error');
  assert.equal(response.headers['Cache-Control'], 'no-store');
  assert.equal(response.headers['X-Content-Type-Options'], 'nosniff');
  assert.equal('Set-Cookie' in response.headers, false);
  assert.equal('Access-Control-Allow-Origin' in response.headers, false);
  assert.equal(response.body.includes('private-fixture'), false);
});

test('안내 JSON을 동일 본문으로 전달하고 highlight/manual/done 계약을 보존한다', async t => {
  for (const answer of [GUIDE, { action: 'manual', targetNodeId: null, message: '직접 확인해주세요.' },
    { action: 'done', targetNodeId: null, message: '안내를 마쳤습니다.' }]) {
    const calls = [];
    const f = fixture(t, { localPort: 18000, fetcher: async (url, options) => {
      calls.push({ url, options }); return Response.json({ ...answer, credentials: 'private-fixture' });
    } });
    const input = guideInput({ headers: { 'content-type': 'application/json; charset=utf-8' } });
    const response = await f.send(input);
    assert.equal(response.status, 200); assert.deepEqual(response.json(), answer);
    assert.equal(calls[0].url, 'http://127.0.0.1:18000/api/guide');
    assert.equal(calls[0].options.method, 'POST');
    assert.equal(calls[0].options.headers.Host, '127.0.0.1:18000');
    assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
    assert.equal(calls[0].options.body.toString('utf8'), input.body);
    assert.equal(response.body.includes('private-fixture'), false);
  }
});

test('공개 UI/OAuth/health/변형 경로와 잘못된 메서드는 upstream에 도달하지 않는다', async t => {
  const f = fixture(t);
  for (const path of ['/', '/health', '/ui/state', '/ui/connect', '/ui/cost-confirm', '/auth/callback', '/models',
    '/api/status?x=1', '/api/guide/', '/api/%73tatus', '/api/../ui/state', ORIGIN + '/api/status',
    '/api/status#fragment', '//api/status', '/API/status']) {
    const response = await f.send({ path });
    assert.equal(response.status, 404, path); assert.equal(response.json().error, 'not_found');
  }
  for (const input of [{ path: '/api/status', method: 'POST' }, { path: '/api/guide', method: 'GET' },
    { path: '/api/status', method: 'OPTIONS' }]) {
    assert.equal((await f.send(input)).status, 405);
  }
  assert.equal(f.calls.length, 0);
});

test('HTTPS·Host·Origin·Bearer 경계는 원본 서버에 전달하기 전에 검사한다', async t => {
  const f = fixture(t);
  for (const input of [{ encrypted: false }, { headers: { host: 'attacker.invalid' } },
    { headers: { host: 'phone.fixture.invalid' } }, { headers: { host: new URL(ORIGIN).host.toUpperCase() } },
    { headers: { origin: 'https://attacker.invalid' } }, { headers: { origin: 'null' } }, { headers: { origin: [ORIGIN] } }]) {
    assert.equal((await f.send(input)).status, 403);
  }
  for (const authorization of [undefined, '', 'Basic private-fixture', 'Bearer fixture token', 'Bearer fixture\r\nToken',
    'Bearer ' + 'x'.repeat(257), [BEARER], 'bearer fixture']) {
    assert.equal((await f.send({ headers: { authorization } })).status, 401);
  }
  assert.equal(f.calls.length, 0);
});

test('요청 MIME·길이·JSON 객체·64 KiB 크기를 제한한다', async t => {
  const f = fixture(t, { fetcher: async () => Response.json(GUIDE) });
  const cases = [
    [guideInput({ headers: { 'content-type': 'text/plain' } }), 415],
    [guideInput({ headers: { 'content-type': 'application/json-attacker' } }), 415],
    [guideInput({ headers: { 'content-type': 'application/json', 'content-length': '65537' } }), 413],
    [guideInput({ chunks: [Buffer.alloc(REQUEST_TOO_LARGE, 32)] }), 413],
    [guideInput({ chunks: [Buffer.alloc(32_768, 32), Buffer.alloc(32_769, 32)] }), 413],
    [guideInput({ body: '[1]' }), 400], [guideInput({ body: 'null' }), 400], [guideInput({ body: 'bad-json' }), 400],
    [guideInput({ chunks: [Buffer.from([0xff])] }), 400],
    [guideInput({ headers: { 'content-type': 'application/json', 'content-length': 'no' } }), 400],
    [guideInput({ headers: { 'content-type': 'application/json', 'content-length': '999999999999999999999' } }), 400],
    [guideInput({ headers: { 'content-type': 'application/json', 'content-length': '2' } }), 400],
    [{ headers: { 'content-length': '1' } }, 400], [{ headers: { 'transfer-encoding': 'chunked' } }, 400],
  ];
  for (const [input, status] of cases) assert.equal((await f.send(input)).status, status);
  assert.equal(f.calls.length, 0);
  const exact = JSON.stringify({ value: 'x'.repeat(65_524) });
  assert.equal(Buffer.byteLength(exact), 65_536);
  assert.equal((await f.send(guideInput({ body: exact, headers: { 'content-type': 'application/json', 'content-length': '65536' } }))).status, 200);
});
const REQUEST_TOO_LARGE = 65_537;

test('모델 이미지 본문은 256KiB까지 중계하고 일반 guide의 64KiB 제한은 유지한다',async t=>{
  const f=fixture(t);const body=JSON.stringify({value:'x'.repeat(80_000)});
  // fake upstream의 상태 응답은 모델 응답 계약에 맞지 않아 502지만 본문은 온전히 도달한다.
  const response=await f.send({path:'/api/model-response',method:'POST',body,headers:{'content-type':'application/json'}});
  assert.equal(response.status,502);assert.equal(f.calls.length,1);
  assert.equal(Buffer.from(f.calls[0].options.body).toString(),body);
  const rejected=await f.send({path:'/api/model-response',method:'POST',body:JSON.stringify({value:'x'.repeat(262_144)}),headers:{'content-type':'application/json'}});
  assert.equal(rejected.status,413);assert.equal(f.calls.length,1);
});

test('원본 페어링 실패·요청 제한 상태를 보존하되 추가 자료를 내보내지 않는다', async t => {
  for (const [status, error, message] of [[401, 'invalid_pairing', '폰 연결을 확인해주세요.'],
    [409, 'not_ready', '연결을 먼저 확인해주세요.'], [429, 'rate_limited', '잠시 뒤 다시 요청해주세요.'],
    [504, 'timeout', '요청 제한 시간을 초과했습니다.']]) {
    const f = fixture(t, { fetcher: async () => Response.json({ error, message, credentials: 'private-fixture' }, { status }) });
    const response = await f.send();
    assert.equal(response.status, status); assert.deepEqual(response.json(), { error, message });
    assert.equal(response.body.includes('private-fixture'), false);
  }
});

test('HTML·과대 응답·잘못된 JSON·계약 오류·리디렉션은 원문 없이 거부한다', async t => {
  const cases = [
    () => new Response('<html>private-fixture</html>', { headers: { 'Content-Type': 'text/html' } }),
    () => new Response('private-fixture', { headers: { 'Content-Type': 'application/json' } }),
    () => Response.json(['private-fixture']),
    () => new Response(' '.repeat(262_145), { headers: { 'Content-Type': 'application/json' } }),
    () => Response.json({ ready: true, message: '연결' }, { headers: { 'Content-Length': '262145' } }),
    () => Response.json({ ready: 'private-fixture', message: '연결' }),
    () => Response.json({ ready: true, message: 'x'.repeat(501) }),
    () => Response.json({ error: 'private-fixture', message: '연결' }, { status: 401 }),
    () => Response.json({ ready: true, message: '연결' }, { status: 302, headers: { Location: 'https://attacker.invalid' } }),
  ];
  for (const makeResponse of cases) {
    const f = fixture(t, { fetcher: async () => makeResponse() }); const response = await f.send();
    assert.equal(response.status, 502); assert.equal(response.json().error, 'invalid_upstream');
    assert.equal(response.body.includes('private-fixture'), false); assert.equal(response.body.includes('attacker.invalid'), false);
  }
  for (const answer of [{ ...GUIDE, targetNodeId: -1 }, { ...GUIDE, action: 'private-fixture' },
    { action: 'manual', targetNodeId: 2, message: '확인' }, { ...GUIDE, message: '' }]) {
    const f = fixture(t, { fetcher: async () => Response.json(answer) });
    assert.equal((await f.send(guideInput())).status, 502);
  }
  const f = fixture(t, { fetcher: async () => { throw new Error('private-fixture'); } });
  const response = await f.send();
  assert.equal(response.status, 502); assert.equal(response.json().error, 'upstream_unavailable');
  assert.equal(response.body.includes('private-fixture'), false);
});

async function blockedFixture(t, options = {}) {
  let notify; let upstreamSignal;
  const started = new Promise(resolve => { notify = resolve; });
  const f = fixture(t, { ...options, fetcher: async (url, request) => { upstreamSignal = request.signal; notify(); return new Promise(() => {}); } });
  const running = f.start(); await started;
  return { ...f, running, upstreamSignal };
}

test('폰 중단·응답 연결 종료는 upstream 취소로 전달하며 자동 재시도하지 않는다', async t => {
  for (const event of ['aborted', 'close', 'error']) {
    const f = await blockedFixture(t);
    if (event === 'aborted') f.running.request.emit('aborted');
    else if (event === 'error') f.running.request.emit('error', new Error('private-fixture'));
    else { f.running.response.destroyed = true; f.running.response.emit('close'); }
    await f.running.done;
    assert.equal(f.upstreamSignal.aborted, true);
    if (event !== 'close') {
      assert.equal(f.running.response.status, 409);
      assert.equal(f.running.response.json().error, 'cancelled');
      assert.equal(f.running.response.body.includes('private-fixture'), false);
    } else assert.equal(f.running.response.body, undefined);
  }
});

test('시간 제한과 종료는 취소를 무시하는 fetch도 끝내며 종료 후 새 요청을 차단한다', async t => {
  const timed = await blockedFixture(t, { timeoutMs: 25 });
  await timed.running.done;
  assert.equal(timed.upstreamSignal.aborted, true);
  assert.equal(timed.running.response.status, 504);
  const closed = await blockedFixture(t);
  await closed.proxy.close(); await closed.running.done;
  assert.equal(closed.upstreamSignal.aborted, true);
  assert.equal((await closed.send()).status, 503);
  await assert.rejects(() => closed.proxy.listen());
});

test('동시 요청 제한은 원본 호출 전에 적용하며 취소 후 자리를 반환한다', async t => {
  const f = await blockedFixture(t, { maxConcurrent: 1 });
  assert.equal((await f.send()).status, 429);
  f.running.request.emit('aborted'); await f.running.done;
  const next = f.start();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(next.response.status, undefined);
  next.request.emit('aborted'); await next.done;
  assert.equal(next.response.status, 409);
});

test('아직 도착하지 않은 요청 본문도 시간 제한으로 종료한다', async t => {
  const f = fixture(t, { timeoutMs: 25 });
  const request = new Readable({ read() {} });
  const running = f.start(guideInput({ request }));
  await running.done;
  assert.equal(running.response.status, 504);
  assert.equal(f.calls.length, 0);
  request.destroy();
});


test('구독·Decisions 상태는 계정·키 없이 모델 준비 상태만 공개한다',async t=>{
  for(const path of ['/api/model-status','/api/decisions-status']){
    const f=fixture(t,{fetcher:async()=>Response.json({ready:true,message:'준비 완료',models:['gpt-6.1-sol'],credentials:{accessToken:'private-fixture'},email:'private-fixture'})});
    const response=await f.send({path});assert.equal(response.status,200);assert.equal(response.json().ready,true);
    assert.equal(response.body.includes('private-fixture'),false);
  }
});
test('모델 응답 gateway는 시간·토큰·선택만 전달하고 임의 계정 필드를 제거한다',async t=>{
  for(const subscription of [true,false]){
    const data={model:subscription?'gpt-6.1-sol':'gpt-6-luna',status:'completed',usage:{input_tokens:120,output_tokens:0},
      apl:{upstreamMs:240,costUsd:subscription?0:.000012},credentials:{accessToken:'private-fixture'},
      output:[{type:'message',content:[{type:'output_text',text:'{"choice":"node_1","message":"문자 메시지"}'}]}],
      answers:[{name:'next',type:'choice',choice:'node_1',credentials:'private-fixture'}]};
    const f=fixture(t,{fetcher:async()=>Response.json(data)});
    const response=await f.send({path:subscription?'/api/model-response':'/api/decisions-response',method:'POST',body:JSON.stringify({model:data.model}),headers:{'content-type':'application/json'}});
    assert.equal(response.status,200);assert.equal(response.json().apl.upstreamMs,240);
    assert.equal(response.body.includes('private-fixture'),false);
  }
});
