import test from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../server.mjs';
import { createDecisionApi, validateDecisionRequest } from '../decisions-api.mjs';

const key = 'sk-fixture-only-not-a-real-credential', token = 'a'.repeat(43);
const request = () => ({model:'gpt-6-luna',input:'{"goal":"문자 보내기","nodes":[{"id":1,"label":"문자 메시지"}]}',
  questions:[{name:'next',type:'choice',instructions:'제공된 선택지 중 한 단계만 선택하세요.',
    choices:[{value:'node_1',description:'문자 메시지'},{value:'ask',description:'사용자 선택'},{value:'manual',description:'정보 부족'}]}]});
const completed = () => ({model:'gpt-6-luna',answers:[{name:'next',type:'choice',choice:'node_1',confidence:.9}],usage:{input_tokens:120}});
function fixture(options={}) {
  let usage=options.usage; const saves=[];
  const api=createDecisionApi({getKey:()=>key,getUsage:()=>usage,
    saveUsage:async value=>{saves.push(value);usage=structuredClone(value);},
    fetcher:async()=>Response.json(completed()),...options});
  return {api,saves,usage:()=>usage};
}
test('status is inference-free; server day cap is $0.50 and not a per-phone cap',()=>{
  let calls=0; const f=fixture({fetcher:async()=>{calls++;throw Error();}});
  assert.equal(f.api.status().ready,true);assert.equal(f.api.status().dailyLimitUsd,.5);assert.equal(calls,0);
  assert.throws(()=>createDecisionApi({dailyLimitUsd:.51}));
});
test('only the exact Decisions request shape and supplied enums are accepted',()=>{
  assert.deepEqual(validateDecisionRequest(request()),request());
  for (const extra of [{model:'gpt-6.1-sol'},{tools:[]},{endpoint:'https://fixture.invalid'},{input:[]},{questions:[]}])
    assert.throws(()=>validateDecisionRequest({...request(),...extra}));
  const bad=request();bad.questions.push(bad.questions[0]);assert.throws(()=>validateDecisionRequest(bad));
});
test('reserves persist before a paid call and settles using returned usage',async()=>{
  let sent;const f=fixture({fetcher:async(url,options)=>{assert.equal(f.saves.length,1);sent={url,options};return Response.json(completed());}});
  const result=await f.api.execute(request(),new AbortController());
  assert.equal(sent.url,'https://api.openai.com/v1/decisions');assert.deepEqual(JSON.parse(sent.options.body),request());
  assert.equal(f.saves.length,2);assert.equal(f.usage().requests,1);assert.equal(result.apl.costUsd,.000012);
  assert.equal(result.billingMode,'paid_api');assert.equal(result.answers[0].choice,'node_1');
  assert.equal(JSON.stringify(result).includes(key),false);
});
test('missing quota and exhausted day budget do not retry or fall back',async()=>{
  let calls=0;const f=fixture({fetcher:async()=>{calls++;return Response.json({error:{}},{status:429});}});
  await assert.rejects(f.api.execute(request(),new AbortController()),e=>e.status===429);assert.equal(calls,1);
  assert.equal(f.saves.length,1);assert.ok(f.usage().spentUsd>0);
  const full=fixture({usage:{day:'2026-10-15',spentUsd:.4999,requests:100},now:()=>Date.parse('2026-10-15T10:00:00+09:00'),fetcher:async()=>{calls++;throw Error();}});
  await assert.rejects(full.api.execute(request(),new AbortController()),e=>e.code==='budget_limit');assert.equal(calls,1);
});
test('storage failure blocks billing; ambiguous usage and invalid selections fail closed',async()=>{
  let calls=0;const f=fixture({saveUsage:async()=>{throw Error('fixture');},fetcher:async()=>{calls++;return Response.json(completed());}});
  await assert.rejects(f.api.execute(request(),new AbortController()));assert.equal(calls,0);
  const bad=completed();bad.answers[0].choice='node_999';
  const f2=fixture({fetcher:async()=>Response.json(bad)});await assert.rejects(f2.api.execute(request(),new AbortController()));
  assert.equal(f2.usage().spentUsd,.000012);
});
test('concurrent paid calls share the reservation and resets use Korea midnight',async()=>{
  let finish;const pending=new Promise(r=>finish=r);
  let inFlight=0,peak=0;
  const f=fixture({fetcher:async()=>{inFlight++;peak=Math.max(peak,inFlight);await pending;inFlight--;return Response.json(completed());}});
  const first=f.api.execute(request(),new AbortController());
  const second=f.api.execute(request(),new AbortController());
  await new Promise(setImmediate);assert.equal(f.saves.length,1);
  finish();await Promise.all([first,second]);
  assert.equal(peak,1);assert.equal(f.usage().requests,2);assert.equal(f.usage().spentUsd,.000024);
  const next=fixture({usage:{day:'2026-10-14',spentUsd:.5,requests:100},now:()=>Date.parse('2026-10-14T15:00:00Z')});
  assert.equal(next.api.status().ready,true);assert.equal(next.api.status().spentUsd,0);
});
test('cancelled queued request never reserves money or sends an upstream call',async()=>{
  let finish;const pending=new Promise(r=>finish=r);let calls=0;
  const f=fixture({fetcher:async()=>{calls++;await pending;return Response.json(completed());}});
  const first=f.api.execute(request(),new AbortController());
  const controller=new AbortController();const second=f.api.execute(request(),controller);
  controller.abort();await assert.rejects(second,e=>e.name==='AbortError');
  finish();await first;assert.equal(calls,1);assert.equal(f.usage().requests,1);
});
test('bounded queue timeout is distinct from billing quota and does not charge',async()=>{
  let finish;const pending=new Promise(r=>finish=r);let calls=0;
  const f=fixture({queueTimeoutMs:20,fetcher:async()=>{calls++;await pending;return Response.json(completed());}});
  const first=f.api.execute(request(),new AbortController());
  await assert.rejects(f.api.execute(request(),new AbortController()),e=>e.code==='guide_busy' && e.status===429);
  finish();await first;assert.equal(calls,1);assert.equal(f.usage().requests,1);
});
test('queued request rechecks shared budget after the preceding request settles',async()=>{
  let finish;const pending=new Promise(r=>finish=r);let calls=0;
  const reserve=(Buffer.byteLength(JSON.stringify(request()))+16384)*(.10/1_000_000);
  const initial=.5-reserve-.000001;
  const f=fixture({usage:{day:'2026-10-16',spentUsd:initial,requests:0},now:()=>Date.parse('2026-10-16T03:00:00+09:00'),
    fetcher:async()=>{calls++;await pending;return Response.json(completed());}});
  const first=f.api.execute(request(),new AbortController());
  const second=assert.rejects(f.api.execute(request(),new AbortController()),e=>e.code==='budget_limit');
  await new Promise(setImmediate);finish();
  await Promise.all([first,second]);assert.equal(calls,1);assert.equal(f.usage().requests,1);
  assert.equal(f.usage().spentUsd,initial+.000012);
});
test('paired Decisions endpoint works even when subscription authentication is absent',async t=>{
  let calls=0;const b=createBridge({initialState:{version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000001',accounts:[],activeClientId:null,decisionsApiKey:key},
    store:{write:async()=>{}},pairingToken:token,port:0,fetcher:async()=>{calls++;return Response.json(completed());}});
  const origin=await b.listen();t.after(()=>b.close());
  assert.equal((await fetch(origin+'/api/decisions-status')).status,401);
  const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
  const status=await (await fetch(origin+'/api/decisions-status',{headers})).json();assert.equal(status.ready,true);assert.equal(calls,0);
  const result=await fetch(origin+'/api/decisions-response',{method:'POST',headers,body:JSON.stringify(request())});
  assert.equal(result.status,200);assert.equal((await result.json()).answers[0].choice,'node_1');assert.equal(calls,1);
});
