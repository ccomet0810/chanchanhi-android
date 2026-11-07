import test from 'node:test';
import assert from 'node:assert/strict';
import { createBridge } from '../server.mjs';
import { validateModelRequest, modelResult } from '../model-contract.mjs';

const request = () => ({model:'gpt-6.1-sol', store:false, stream:true, service_tier:'default', reasoning:{effort:'low'},
  instructions:'주어진 항목에서 한 단계만 선택하세요.', input:[{role:'user', content:'{"goal":"문자 보내기"}'}],
  text:{format:{type:'json_schema',name:'screen_guidance',strict:true,schema:{type:'object',additionalProperties:false,
    properties:{choice:{type:'string',enum:['node_1','ask','manual']},message:{type:'string'}},required:['choice','message']}}}});
const completed = () => ({status:'completed',model:'gpt-6.1-sol',output:[{type:'message',content:[{type:'output_text',text:'{"choice":"node_1","message":"문자 메시지를 눌러주세요."}'}]}],usage:{input_tokens:120,output_tokens:25}});
async function fixture(t, {ready=true, fetcher=async()=>new Response(`data: ${JSON.stringify({type:'response.completed',response:completed()})}\n\n`,{headers:{'Content-Type':'text/event-stream'}})}={}) {
  const state={version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000001',activeClientId:'fixture',accounts:[{
    clientId:'fixture',connected:true,costConfirmed:ready,model:'gpt-6.1-sol',models:[{slug:'gpt-6.1-sol'}],
    scopes:['resource.invoke','chatgpt.tokens.use.direct'],credentials:{accessToken:'fixture-only',expiresAt:Date.now()+600_000}}]};
  const bridge=createBridge({store:{write:async()=>{}},initialState:state,pairingToken:'a'.repeat(43),fetcher,port:0});
  const origin=await bridge.listen(); t.after(()=>bridge.close());
  const call=(value=request())=>fetch(origin+'/api/model-response',{method:'POST',headers:{Authorization:'Bearer '+'a'.repeat(43),'Content-Type':'application/json'},body:JSON.stringify(value)});
  return {bridge,origin,call};
}
test('subscription preserves input, instructions and schema; rejects unsupported paid options',()=>{
  assert.deepEqual(validateModelRequest(request()),request());
  for(const extra of [{max_output_tokens:1024},{tools:[]},{model:'gpt-6-luna'},{stream:false},{input:'text'},{store:true}])
    assert.throws(()=>validateModelRequest({...request(),...extra}));
});
const evidenceRequest = () => {
  const value=request(), schema=value.text.format.schema;
  schema.properties.choice.enum.push('confirm');
  schema.properties.evidenceNodeIds={type:'array',maxItems:3,items:{type:'integer'}};
  schema.required.push('evidenceNodeIds');
  value.input[0].content=JSON.stringify({goal:'보관함 열기',nodes:[{id:1,label:'보관함'},{id:2,label:'보관 중인 상품'}]});
  return value;
};
const evidenceCompleted = (choice='node_1',ids=[]) => {
  const value=completed();
  value.output[0].content[0].text=JSON.stringify({choice,message:'보관함을 눌러주세요.',evidenceNodeIds:ids});
  return value;
};
test('current Android evidence schema and legacy schema are both accepted without widening other schemas',()=>{
  assert.deepEqual(validateModelRequest(evidenceRequest()),evidenceRequest());
  for(const mutate of [
    r=>r.text.format.schema.properties.evidenceNodeIds.maxItems=4,
    r=>r.text.format.schema.properties.evidenceNodeIds.items.type='string',
    r=>r.text.format.schema.properties.evidenceNodeIds.items.minimum=0,
    r=>r.text.format.name='app_routing',
    r=>r.text.format.schema.required.pop(),
    r=>r.text.format.schema.properties.extra={type:'string'},
  ]) {const r=evidenceRequest();mutate(r);assert.throws(()=>validateModelRequest(r));}
});
test('evidence output needs bounded unique integer IDs and confirm needs submitted screen nodes',()=>{
  const r=evidenceRequest();
  assert.equal(modelResult(evidenceCompleted(),r).status,'completed');
  assert.equal(modelResult(evidenceCompleted('confirm',[1,2]),r).status,'completed');
  for(const ids of [[],[99],[-1],['1'],[1.1],[1,1],[1,2,3,4],null,[Number.MAX_SAFE_INTEGER]])
    assert.throws(()=>modelResult(evidenceCompleted('confirm',ids),r));
  assert.throws(()=>modelResult(evidenceCompleted('node_1',[1]),r));
  assert.throws(()=>modelResult(completed(),r));
  const broken=evidenceRequest();broken.input[0].content='not JSON';
  assert.throws(()=>modelResult(evidenceCompleted('confirm',[1]),broken));
});
test('current Android schema reaches upstream and returns evidence through the real subscription route',async t=>{
  let calls=0;
  const f=await fixture(t,{fetcher:async(url,options)=>{
    calls++;assert.deepEqual(JSON.parse(options.body),evidenceRequest());
    return new Response(`data: ${JSON.stringify({type:'response.completed',response:evidenceCompleted('confirm',[1,2])})}\n\n`);
  }});
  const res=await f.call(evidenceRequest());assert.equal(res.status,200);assert.equal(calls,1);
  const data=await res.json();assert.deepEqual(JSON.parse(data.output[0].content[0].text).evidenceNodeIds,[1,2]);
});
test('completed output must use supplied enum, exact model, bounded message and valid usage',()=>{
  assert.equal(modelResult(completed(),request()).billingMode,'chatgpt_subscription');
  assert.throws(()=>modelResult({...completed(),model:'gpt-6-astra'},request()));
  assert.throws(()=>modelResult({...completed(),status:'incomplete'},request()));
  assert.throws(()=>modelResult({...completed(),usage:{input_tokens:1,output_tokens:-1}},request()));
  const bad=completed(); bad.output[0].content[0].text='{"choice":"node_99","message":"누르세요"}';
  assert.throws(()=>modelResult(bad,request()));
});
test('status never invokes inference and does not expose tokens',async t=>{
  let calls=0; const f=await fixture(t,{fetcher:async()=>{calls++;throw Error();}});
  const res=await fetch(f.origin+'/api/model-status',{headers:{Authorization:'Bearer '+'a'.repeat(43)}});
  const data=await res.json();assert.equal(data.ready,true);assert.deepEqual(data.models,['gpt-6.1-sol']);
  assert.equal(calls,0);assert.equal(JSON.stringify(data).includes('fixture-only'),false);
});
test('same payload reaches Responses with OAuth and terminal response returns usage',async t=>{
  let actual;
  const f=await fixture(t,{fetcher:async(url,options)=>{actual={url,options};return new Response(`data: ${JSON.stringify({type:'response.completed',response:completed()})}\n\n`);}});
  const res=await f.call();assert.equal(res.status,200);
  const data=await res.json();assert.equal(data.usage.input_tokens,120);assert.equal(data.billingMode,'chatgpt_subscription');
  assert.equal(actual.url,'https://api.openai.com/v1/responses');assert.deepEqual(JSON.parse(actual.options.body),request());
  assert.equal(actual.options.headers.Authorization,'Bearer fixture-only');
});
test('credits confirmation gates requests; errors never retry or switch to API billing',async t=>{
  let calls=0; const blocked=await fixture(t,{ready:false,fetcher:async()=>{calls++;throw Error();}});
  assert.equal((await blocked.call()).status,409);assert.equal(calls,0);
  const f=await fixture(t,{fetcher:async()=>{calls++;return Response.json({error:{code:'subscription_sharing_usage_limit_exceeded'}},{status:429});}});
  assert.equal((await f.call()).status,429);assert.equal(calls,1);
});
test('invalid enum output and missing terminal completion never return guidance',async t=>{
  const f=await fixture(t,{fetcher:async()=>new Response('data: {"type":"response.output_text.delta","delta":"{}"}\n\n')});
  assert.equal((await f.call()).status,502);
});
test('subscription delta text is restored only after completed metadata with empty output',async t=>{
  const done=completed(), text=done.output[0].content[0].text; done.output=[];
  const f=await fixture(t,{fetcher:async()=>new Response(
    `data: ${JSON.stringify({type:'response.output_text.delta',delta:text})}\n\ndata: ${JSON.stringify({type:'response.completed',response:done})}\n\n`)});
  const res=await f.call(); assert.equal(res.status,200);
  const body=await res.json(); assert.equal(body.usage.input_tokens,120);
  assert.equal(JSON.parse(body.output[0].content[0].text).choice,'node_1');
});
