// Test-only loopback adapter. The actual bridge validates requests; the upstream is in-memory.
import {createServer,request as httpRequest} from 'node:http';
import {writeFile} from 'node:fs/promises';
import {createBridge} from '../server.mjs';
const model='gpt-6.1-terra';
const report=process.argv[2];
if(!report)throw Error('Fixture report path required');
const metrics={offline:true,realAiRequests:0,requests:[],httpFaults:[]};
const repairs=new Map();
const state={version:1,hostId:'urn:uuid:00000000-0000-0000-0000-000000000003',activeClientId:'fixture',accounts:[{
  clientId:'fixture',connected:true,costConfirmed:true,model,models:[{slug:model}],
  scopes:['resource.invoke','chatgpt.tokens.use.direct'],credentials:{accessToken:'fixture-only',expiresAt:Date.now()+600_000}}]};
const bridge=createBridge({store:{write:async()=>{}},initialState:state,pairingToken:'c'.repeat(43),port:0,
  fetcher:async(url,options)=>{
    if(url!=='https://api.openai.com/v1/responses')throw Error('Unexpected fixture upstream');
    const request=JSON.parse(options.body),input=JSON.parse(request.input[0].content);
    if(request.model!==model||request.reasoning.effort!=='low'||request.text.format.name!=='task_plan')throw Error('Wrong client contract');
    const value={kind:'OBSERVE',summary:'공개 계약 검사',goalKind:'STATE',conditions:[],tasks:[],relations:[],operation:'NONE',targetNodeId:null,
      message:'현재 화면을 더 확인해주세요.',question:null,waitKind:'NONE',relevantNodeIds:[],newEventNodeIds:[],effect:'NONE',commitOutcome:'NONE',
      outcomeNodeIds:[],scopeNodeIds:[],outcomeReceiptId:null,conditionEdits:[],actionGates:[]};
    if(input.userSignal.startsWith('qa_repair_')){
      const previous=repairs.get(input.userSignal);
      const copy=structuredClone(input);delete copy.planningFeedback;
      if(!previous){
        if(input.planningFeedback)throw Error('Unexpected initial correction');
        repairs.set(input.userSignal,copy);
        value.relations=[{kind:'ALL',members:['not_a_task']}];
      }else{
        const stable=v=>JSON.stringify(v,(key,value)=>value&&typeof value==='object'&&!Array.isArray(value)?Object.fromEntries(Object.entries(value).sort(([a],[b])=>a.localeCompare(b))):value);
        if(stable(previous)!==stable(copy)||input.planningFeedback?.attempt!==1||input.planningFeedback.maximumRepairs!==1||
          input.planningFeedback.source!=='RESPONSE'||input.planningFeedback.reason!=='server_invalid_model_result')throw Error('Lost frozen repair context');
        const id=input.nodes[0].id;Object.assign(value,{kind:'GUIDE',operation:'TAP',targetNodeId:id,effect:'PREPARE',message:'공개 항목을 눌러주세요.'});
      }
    }else if(input.userSignal==='qa_simple'||input.userSignal==='qa_input_finished'){
      if(input.userSignal==='qa_input_finished'){
        const p=input.inputProgress;
        if(p?.length!==1||p[0].inputId!==1||p[0].nodeId!==1||p[0].userFinished!==true||p[0].receiptId!==7||
            JSON.stringify(input).includes('localInputRevision')||JSON.stringify(input).includes('987654321'))throw Error('Lost or leaked native input context');
      }
      const id=input.nodes[0].id;
      Object.assign(value,{kind:'GUIDE',operation:'TAP',targetNodeId:id,relevantNodeIds:[id],message:'공개 항목을 눌러주세요.',
        tasks:[{key:'open',description:'공개 항목 열기',nodeIds:[id],dependsOn:[]}],effect:'PREPARE'});
    }else if(['qa_values','qa_values_mismatch','qa_input_presence'].includes(input.userSignal)){
      const r=input.requirements[0],id=input.nodes[0].id;
      const e=r.expectations[0];
      if(r.phase!=='PREPARATION'||(input.userSignal==='qa_input_presence'?
        e.property!=='INPUT_PRESENT'||typeof e.expectedBoolean!=='boolean'||e.expectedNumber!==null:
        e.expectedNumber!==30||e.property!=='RANGE'))throw Error('Lost typed desired value');
      value.conditions=[{key:'value_ready',description:r.description,required:r.required,truth:'SATISFIED',evidence:'OBSERVED',evidenceNodeIds:[id],
        requirementId:r.id,requirementVersion:r.version,phase:r.phase,expectations:r.expectations,basis:null}];
    }else if(input.userSignal==='qa_owned'){
      const r=input.requirements[0],receipt=input.operationHistory[0];
      if(r.id!==1||r.version!==2||input.answers.desired!=='값 A'||input.answerAuthorities.desired.revision!==2||receipt.receiptId!==7||!input.previousPlan)throw Error('Lost owned context');
      value.conditions=[{key:'owned',description:r.description,required:r.required,truth:'UNKNOWN',evidence:'UNKNOWN',evidenceNodeIds:[],
        requirementId:r.id,requirementVersion:r.version,phase:'OUTCOME',expectations:[],basis:null}];
      Object.assign(value,{commitOutcome:'PENDING',outcomeReceiptId:receipt.receiptId});
    }else if(input.userSignal==='qa_dense'){
      if(input.observationComplete!==false||input.nodes.length<1||input.nodes.length>=180)throw Error('Dense observation must be bounded and explicit');
    }else if(input.userSignal==='qa_bad_partial_completion'){
      const id=input.nodes[0].id;
      Object.assign(value,{kind:'COMPLETE',conditions:[{key:'observed',description:'공개 상태 확인',required:true,truth:'SATISFIED',evidence:'OBSERVED',
        evidenceNodeIds:[id],requirementId:null,requirementVersion:null,phase:'OUTCOME',expectations:[],basis:{kind:'REQUEST',key:null,quote:input.request,rationale:'',evidenceNodeIds:[]}}]});
    }else throw Error('Unexpected public fixture scenario');
    metrics.requests.push({scenario:input.userSignal,model,effort:request.reasoning.effort,nodes:input.nodes.length,
      complete:input.observationComplete,utf8Bytes:Buffer.byteLength(options.body),requirements:input.requirements.length,repair:input.planningFeedback??null});
    const response={status:'completed',model,output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(value)}]}],usage:{input_tokens:100,output_tokens:200}};
    return new Response(`data: ${JSON.stringify({type:'response.completed',response})}\n\n`);
  }});
const bridgeOrigin=await bridge.listen();
// Preserve the production bridge's strict Host check while adb uses the fixed USB port.
// Only this test proxy translates its loopback hop's Host; payload and authorization are unchanged.
const proxy=createServer(async(req,res)=>{
  if(req.headers.host!=='127.0.0.1:18765'||!['/api/model-status','/api/model-response'].includes(req.url)){
    res.writeHead(403);res.end();return;
  }
  const chunks=[];let size=0;
  for await(const chunk of req){size+=chunk.length;if(size>65_536){res.writeHead(413);res.end();return}chunks.push(chunk)}
  const body=Buffer.concat(chunks);
  let input;try{input=JSON.parse(JSON.parse(body.toString()).input[0].content)}catch{}
  const faults={qa_http_auth:[401,'invalid_model_result'],qa_http_limit:[429,'usage_limit'],qa_http_busy:[429,'guide_busy'],qa_http_rate:[429,'guide_rate_limit'],
    qa_http_unknown:[502,'missing_completed'],qa_http_usage:[502,'invalid_model_usage'],qa_http_mismatch:[400,'invalid_model_request'],
    qa_http_invalid:[502,'invalid_model_result']};
  const fault=faults[input?.userSignal];
  if(fault){metrics.httpFaults.push({scenario:input.userSignal,status:fault[0],code:fault[1]});res.writeHead(fault[0],{'Content-Type':'application/json'});
    res.end(JSON.stringify({error:fault[1],message:'SENSITIVE_UPSTREAM_TEXT'}));return}
  const forwarded=httpRequest(new URL(req.url,bridgeOrigin),{method:req.method,headers:{...req.headers,host:new URL(bridgeOrigin).host}},r=>{
    res.writeHead(r.statusCode,r.headers);r.pipe(res);
  });
  forwarded.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end()});forwarded.end(body);
});
await new Promise(resolve=>proxy.listen(0,'127.0.0.1',resolve));
console.log(JSON.stringify({origin:`http://127.0.0.1:${proxy.address().port}`,offline:true,model,nativePort:18765}));
let closing=false;
async function close(){if(closing)return;closing=true;await new Promise(resolve=>proxy.close(resolve));await bridge.close();await writeFile(report,JSON.stringify(metrics,null,2));process.exit(0)}
for(const signal of ['SIGINT','SIGTERM'])process.once(signal,close);
process.stdin.resume();process.stdin.once('end',close);
