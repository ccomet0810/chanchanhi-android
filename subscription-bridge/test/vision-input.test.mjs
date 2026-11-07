import test from 'node:test';
import assert from 'node:assert/strict';
import {validVisionInput} from '../vision-input.mjs';
import {validateDecisionRequest,createDecisionApi} from '../decisions-api.mjs';
import {validateModelRequest} from '../model-contract.mjs';
import {createBridge} from '../server.mjs';

// SOF 구조 검사 전용 fixture. 실제 JPEG 인코딩은 Android 계측에서 검사한다.
function input(width=600,height=1000) {
  const image=Buffer.from([0xff,0xd8,0xff,0xc0,0,17,8,height>>8,height&255,width>>8,width&255,3,1,0x11,0,2,0x11,0,3,0x11,0,0xff,0xd9]);
  return [{role:'user',content:[{type:'input_text',text:'{"goal":"이전 화면으로"}'},
    {type:'input_image',image_url:'data:image/jpeg;base64,'+image.toString('base64'),detail:'high'}]}];
}
function decision() { return {model:'gpt-6-luna',input:input(),questions:[{name:'next',type:'choice',instructions:'하나 선택',
  choices:[{value:'node_1'},{value:'manual'}]}]}; }
function chat() { return {model:'gpt-6.1-sol',store:false,stream:true,service_tier:'default',reasoning:{effort:'low'},
  instructions:'하나 선택',input:input(),text:{format:{type:'json_schema',name:'screen_guidance',strict:true,schema:{type:'object',
    additionalProperties:false,properties:{choice:{type:'string',enum:['node_1','manual']},message:{type:'string'}},required:['choice','message']}}}}; }
test('both backends preserve one bounded JPEG and the same text evidence',()=>{
  assert.deepEqual(validateDecisionRequest(decision()).input,input());
  assert.deepEqual(validateModelRequest(chat()).input,input());
});
test('rejects URLs, oversized dimensions, malformed data, extra parts and routing images',()=>{
  const bad=[input(1281),input(600,1281),[],[...input(),...input()]];
  for(const url of ['https://example.com/image.jpg','data:image/png;base64,AAAA','data:image/jpeg;base64,AAAA']) {
    const p=input();p[0].content[1].image_url=url;bad.push(p);
  }
  const extra=input();extra[0].content.push(extra[0].content[1]);bad.push(extra);
  const huge=input();huge[0].content[1].image_url+='A'.repeat(170_000);bad.push(huge);
  const truncated=input();truncated[0].content[1].image_url='data:image/jpeg;base64,'+Buffer.from([255,216,255,255,255,255,255,255,255,255,255,255,255,255,255,255]).toString('base64');bad.push(truncated);
  for(const p of bad) assert.equal(validVisionInput(p),false);
  const d=decision();d.questions[0].name='route';assert.throws(()=>validateDecisionRequest(d));
  const c=chat();c.text.format.name='app_routing';assert.throws(()=>validateModelRequest(c));
});
test('image inference keeps the persisted daily budget and bills reported input tokens',async()=>{
  let usage=null,calls=0;
  const api=createDecisionApi({getKey:()=> 'sk-fixture-only-not-a-real-credential',getUsage:()=>usage,saveUsage:async u=>{usage=u;},
    fetcher:async(_url,options)=>{calls++;assert.ok(Array.isArray(JSON.parse(options.body).input));
      return Response.json({model:'gpt-6-luna',answers:[{name:'next',type:'choice',choice:'node_1',confidence:.91}],usage:{input_tokens:2400}});}});
  const result=await api.execute(decision(),new AbortController());
  assert.equal(calls,1);assert.ok(Math.abs(result.apl.costUsd-2400*.1/1_000_000)<1e-12);assert.equal(usage.requests,1);
  usage.spentUsd=.5;
  await assert.rejects(api.execute(decision(),new AbortController()));assert.equal(calls,1);
});
test('paired endpoint accepts a JPEG body over the old 64KiB cap without changing its evidence',async t=>{
  const request=decision(), jpeg=Buffer.from(request.input[0].content[1].image_url.slice(23),'base64');
  const app=Buffer.alloc(60004);app.writeUInt16BE(0xffe0,0);app.writeUInt16BE(60002,2);
  request.input[0].content[1].image_url='data:image/jpeg;base64,'+Buffer.concat([jpeg.subarray(0,2),app,jpeg.subarray(2)]).toString('base64');
  let calls=0;
  const bridge=createBridge({initialState:{version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000001',accounts:[],
    activeClientId:null,decisionsApiKey:'sk-fixture-only-not-a-real-credential'},store:{write:async()=>{}},pairingToken:'a'.repeat(43),port:0,
    fetcher:async(_url,options)=>{calls++;assert.deepEqual(JSON.parse(options.body).input,request.input);
      return Response.json({model:'gpt-6-luna',answers:[{name:'next',type:'choice',choice:'node_1'}],usage:{input_tokens:2500}});}});
  const origin=await bridge.listen();t.after(()=>bridge.close());
  const body=JSON.stringify(request);assert.ok(Buffer.byteLength(body)>65536);
  const response=await fetch(origin+'/api/decisions-response',{method:'POST',headers:{Authorization:'Bearer '+'a'.repeat(43),'Content-Type':'application/json'},body});
  assert.equal(response.status,200);assert.equal(calls,1);
});
