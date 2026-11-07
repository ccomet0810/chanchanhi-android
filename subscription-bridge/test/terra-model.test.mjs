import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { createBridge } from '../server.mjs';
import { createPersonalApiServer } from '../personal-api-server.mjs';
import { validateModelRequest,modelResult } from '../model-contract.mjs';

function request(model='gpt-6.1-terra') {
  return {model,store:false,stream:true,service_tier:'default',reasoning:{effort:'low'},instructions:'같은 공개 화면에서 다음 항목 하나를 선택하세요.',
    input:[{role:'user',content:'{"goal":"시간 측정","nodes":[{"id":1,"label":"시작"}]}'}],
    text:{format:{type:'json_schema',name:'screen_guidance',strict:true,schema:{type:'object',additionalProperties:false,
      properties:{choice:{type:'string',enum:['node_1','manual']},message:{type:'string'}},required:['choice','message']}}}};
}
function complete(model) {
  return {model,status:'completed',usage:{input_tokens:100,output_tokens:20},output:[{type:'message',content:[{
    type:'output_text',text:'{"choice":"node_1","message":"시작을 눌러주세요."}'}]}]};
}
test('Terra low and old Sol low share the strict subscription contract without admitting other models or efforts',()=>{
  for(const model of ['gpt-6.1-terra','gpt-6.1-sol'])assert.deepEqual(validateModelRequest(request(model)),request(model));
  for(const model of ['gpt-6-luna','gpt-6-astra','gpt-6.1-terra-invented'])assert.throws(()=>validateModelRequest(request(model)));
  for(const effort of ['none','medium','high'])assert.throws(()=>validateModelRequest({...request(),reasoning:{effort}}));
  assert.throws(()=>modelResult(complete('gpt-6.1-sol'),request()));
});
test('the actual subscription route sends Terra low and keeps legacy Sol requests working',async t=>{
  let clock=1000;const seen=[];
  const state={version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000001',activeClientId:'fixture',accounts:[{
    clientId:'fixture',connected:true,costConfirmed:true,model:'gpt-6.1-terra',models:[{slug:'gpt-6.1-terra'},{slug:'gpt-6.1-sol'}],
    scopes:['resource.invoke','chatgpt.tokens.use.direct'],credentials:{accessToken:'fixture-only',expiresAt:1000000}}]};
  const bridge=createBridge({store:{write:async()=>{}},initialState:state,pairingToken:'t'.repeat(43),port:0,now:()=>clock,
    fetcher:async(url,options)=>{
      assert.equal(url,'https://api.openai.com/v1/responses');const body=JSON.parse(options.body);seen.push(body.model);
      assert.equal(body.reasoning.effort,'low');return new Response(`data: ${JSON.stringify({type:'response.completed',response:complete(body.model)})}\n\n`);
    }});
  const origin=await bridge.listen();t.after(()=>bridge.close());
  for(const model of ['gpt-6.1-terra','gpt-6.1-sol']) {
    const response=await fetch(origin+'/api/model-response',{method:'POST',headers:{Authorization:'Bearer '+'t'.repeat(43),'Content-Type':'application/json'},body:JSON.stringify(request(model))});
    assert.equal(response.status,200);assert.equal((await response.json()).model,model);clock+=1000;
  }
  assert.deepEqual(seen,['gpt-6.1-terra','gpt-6.1-sol']);
});
test('phone gateway binds the model echo to the request and rejects a swapped Sol response for Terra',async t=>{
  let echo='gpt-6.1-terra';
  const gateway=createPersonalApiServer({publicOrigin:'https://phone.fixture.invalid',bindHost:'127.0.0.1',port:0,
    fetcher:async()=>Response.json({...complete(echo),apl:{upstreamMs:100,costUsd:0},credentials:'private-fixture'})});
  await gateway.listen();t.after(()=>gateway.close());const endpoint='http://127.0.0.1:'+gateway.server.address().port;
  const send=()=>new Promise((resolve,reject)=>{
    const req=httpRequest(endpoint+'/api/model-response',{method:'POST',headers:{Host:'phone.fixture.invalid',Authorization:'Bearer fixture-token','Content-Type':'application/json'}},res=>{
      let body='';res.setEncoding('utf8');res.on('data',chunk=>body+=chunk);res.on('end',()=>resolve({status:res.statusCode,body}));
    });req.on('error',reject);req.end(JSON.stringify(request()));
  });
  const good=await send();assert.equal(good.status,200,good.body);assert.equal(good.body.includes('private-fixture'),false);
  echo='gpt-6.1-sol';assert.equal((await send()).status,502);
});
