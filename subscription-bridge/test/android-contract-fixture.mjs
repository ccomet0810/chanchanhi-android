// No real account, credential or network upstream. Used only with an emulator's
// actual GuideModelCodec instrumentation to catch client/server schema drift.
import { createBridge } from '../server.mjs';
const state = {version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000001',activeClientId:'fixture',accounts:[{
  clientId:'fixture',connected:true,costConfirmed:true,model:'gpt-6.1-sol',models:[{slug:'gpt-6.1-sol'}],
  scopes:['resource.invoke','chatgpt.tokens.use.direct'],credentials:{accessToken:'fixture-only',expiresAt:Date.now()+600_000}}]};
const bridge = createBridge({store:{write:async()=>{}},initialState:state,pairingToken:'c'.repeat(43),port:0,
  fetcher:async(url,options)=>{
    if(url!=='https://api.openai.com/v1/responses') throw Error('Unexpected fixture request');
    const request=JSON.parse(options.body), value={choice:'confirm',message:'검사 화면을 열었어요.'};
    if(request.text.format.schema.properties.evidenceNodeIds) value.evidenceNodeIds=[1,2];
    const response={status:'completed',model:'gpt-6.1-sol',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(value)}]}],
      usage:{input_tokens:100,output_tokens:20}};
    return new Response(`data: ${JSON.stringify({type:'response.completed',response})}\n\n`);
  }});
console.log(JSON.stringify({origin:await bridge.listen(),offline:true}));
for(const signal of ['SIGINT','SIGTERM']) process.once(signal,()=>bridge.close().then(()=>process.exit(0)));
process.stdin.resume();
process.stdin.once('end',()=>bridge.close().then(()=>process.exit(0)));
