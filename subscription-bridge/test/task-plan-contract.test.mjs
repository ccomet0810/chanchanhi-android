import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {validateModelRequest,modelResult} from '../model-contract.mjs';
import {taskPlanSchema} from '../task-plan-contract.mjs';

const request=()=>({model:'gpt-6.1-terra',store:false,stream:true,service_tier:'default',reasoning:{effort:'low'},
  instructions:'현재 목표와 관련된 전체 작업 계획을 구성한다.',input:[{role:'user',content:JSON.stringify({request:'원하는 값을 입력하고 저장해줘',answers:{},requirements:[],goalRevision:1,answerAuthorities:{},nodes:[{id:1,label:'값 입력'},{id:2,label:'저장'}],operationHistory:[{operationId:1,receiptId:7,attempt:1,state:'PENDING'}]})}],
  text:{format:{type:'json_schema',name:'task_plan',strict:true,schema:structuredClone(taskPlanSchema)}}});
const plan=()=>({kind:'GUIDE',summary:'관련 입력과 저장',goalKind:'NEW_EVENT',conditions:[],
  tasks:[{key:'input',description:'원하는 값 입력',nodeIds:[1],dependsOn:[]},{key:'save',description:'저장',nodeIds:[2],dependsOn:['input']}],
  relations:[{kind:'BEFORE',members:['input','save']}],operation:'INPUT',targetNodeId:1,message:'원하는 값을 입력해주세요.',question:null,
  waitKind:'NONE',relevantNodeIds:[1,2],newEventNodeIds:[],effect:'NONE',commitOutcome:'NONE',outcomeNodeIds:[],scopeNodeIds:[],outcomeReceiptId:null,conditionEdits:[],actionGates:[]});
const response=p=>({status:'completed',model:'gpt-6.1-terra',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(p)}]}],usage:{input_tokens:100,output_tokens:150}});
const androidSchema=new URL('../../app3/src/main/assets/task-plan.schema.json',import.meta.url);
test('Android and server share exactly the same full-plan contract',
  {skip:!existsSync(androidSchema)?'Android sources are not included in an isolated server deployment':false},()=>{
  assert.deepEqual(taskPlanSchema,JSON.parse(readFileSync(androidSchema,'utf8')));
});
test('the exact full-plan schema is accepted',()=>{
  assert.deepEqual(validateModelRequest(request()),request());
});
test('full plan preserves multiple tasks, dependency and set relation',()=>{
  const p=plan();p.relations.push({kind:'SET',members:['input','save']});
  assert.deepEqual(JSON.parse(modelResult(response(p),request()).output[0].content[0].text),p);
});

test('relations reference task keys, never condition keys from a real public Terra plan',()=>{
  const p=plan();
  p.conditions=[{key:'recipient',description:'받는 사람 선택',required:true,truth:'UNKNOWN',evidence:'UNKNOWN',
    evidenceNodeIds:[],requirementId:null,requirementVersion:null,phase:'OUTCOME',expectations:[],
    basis:{kind:'INFERRED',key:null,quote:'저장',rationale:'저장할 대상을 준비한다.',evidenceNodeIds:[]}}];
  p.relations=[{kind:'ALL',members:['recipient']}];
  assert.throws(()=>modelResult(response(p),request()));
  p.relations=[{kind:'ALL',members:['input','save']}];
  assert.doesNotThrow(()=>modelResult(response(p),request()));
});

test('empty, repeated and missing relation members fail at the server boundary',()=>{
  for(const members of [[],['input','input'],['input','absent']]){
    const p=plan();p.relations=[{kind:'SET',members}];
    assert.throws(()=>modelResult(response(p),request()));
  }
});

test('task graphs reject duplicate tasks, missing dependencies and cycles',()=>{
  for(const change of [p=>p.tasks.push(structuredClone(p.tasks[0])),
    p=>p.tasks[0].dependsOn=['absent'],p=>p.tasks[0].dependsOn=['save'],p=>p.tasks[0].key=' ']){
    const p=plan();change(p);assert.throws(()=>modelResult(response(p),request()));
  }
});
test('a weakened or extended schema never replaces the exact task-plan contract',()=>{
  for(const change of [r=>r.text.format.schema.additionalProperties=true,r=>r.text.format.schema.properties.tasks.maxItems=999,
    r=>r.text.format.schema.properties.kind.enum.push('EXECUTE'),r=>r.text.format.schema.required.pop(),r=>r.tools=[]]){
    const r=request();change(r);assert.throws(()=>validateModelRequest(r));
  }
});
test('fabricated or string-coerced node IDs are rejected',()=>{
  for(const change of [p=>p.targetNodeId=999,p=>p.tasks[0].nodeIds=['1'],p=>p.conditions=[{key:'x',description:'x',required:true,truth:'SATISFIED',evidence:'OBSERVED',evidenceNodeIds:[999],requirementId:null,requirementVersion:null,phase:'OUTCOME',expectations:[],basis:{kind:'REQUEST',key:null,quote:'저장',rationale:'',evidenceNodeIds:[]}}]]){
    const p=plan();change(p);assert.throws(()=>modelResult(response(p),request()));
  }
});
test('one question can be represented without executing an action',()=>{
  const p=plan();Object.assign(p,{kind:'ASK',operation:'NONE',targetNodeId:null,question:{key:'desiredTime',message:'언제 울리게 할까요?',neededNow:true,changesGuidance:true,canEnterInApp:false,conditionKeys:[]}});
  assert.equal(JSON.parse(modelResult(response(p),request()).output[0].content[0].text).kind,'ASK');
});
test('missing question, unexpected question and unexplained waiting fail',()=>{
  for(const change of [p=>p.kind='ASK',p=>p.question={key:'x',message:'x',neededNow:true,changesGuidance:true,canEnterInApp:false,conditionKeys:[]},p=>p.kind='WAIT']){
    const p=plan();change(p);assert.throws(()=>modelResult(response(p),request()));
  }
});
test('the selected model remains bound to the request',()=>{
  const r=response(plan());r.model='gpt-6.1-sol';assert.throws(()=>modelResult(r,request()));
});
test('scope and operation outcomes cannot refer to invented nodes',()=>{
  for(const mutate of [p=>p.scopeNodeIds=[999],p=>{p.commitOutcome='SUCCEEDED';p.outcomeNodeIds=[999]},p=>{p.commitOutcome='FAILED';p.outcomeNodeIds=[]}]){
    const p=plan();mutate(p);assert.throws(()=>modelResult(response(p),request()));
  }
});
test('a commit is a declared native tap to guide, never an execution instruction',()=>{
  const p=plan();p.effect='COMMIT';assert.throws(()=>modelResult(response(p),request()));
  p.conditions=[{key:'ready',description:'공개 값 준비',required:true,truth:'SATISFIED',evidence:'OBSERVED',evidenceNodeIds:[1],
    requirementId:null,requirementVersion:null,phase:'PREPARATION',expectations:[],basis:{kind:'INFERRED',key:null,quote:'입력',rationale:'확정 전 입력 값 확인',evidenceNodeIds:[1]}}];
  p.actionGates=[{targetNodeId:1,conditionKeys:['ready']}];
  p.operation='TAP';assert.equal(JSON.parse(modelResult(response(p),request()).output[0].content[0].text).effect,'COMMIT');
  p.kind='COMPLETE';p.operation='NONE';assert.throws(()=>modelResult(response(p),request()));
});

test('an outcome can only refer to a locally issued native receipt',()=>{
  const p=plan();Object.assign(p,{commitOutcome:'SUCCEEDED',outcomeNodeIds:[2],outcomeReceiptId:7});
  assert.equal(JSON.parse(modelResult(response(p),request()).output[0].content[0].text).outcomeReceiptId,7);
  for(const value of [null,0,8,'7']){
    p.outcomeReceiptId=value;assert.throws(()=>modelResult(response(p),request()));
  }
  const r=request();r.input[0].content=JSON.stringify({nodes:[{id:1},{id:2}]});p.outcomeReceiptId=7;
  assert.throws(()=>modelResult(response(p),r));
});

test('NONE cannot carry a receipt reference and pending outcomes also need one',()=>{
  const p=plan();p.outcomeReceiptId=7;assert.throws(()=>modelResult(response(p),request()));
  p.commitOutcome='PENDING';assert.doesNotThrow(()=>modelResult(response(p),request()));
  p.outcomeReceiptId=null;assert.throws(()=>modelResult(response(p),request()));
});

test('a later goal-kind label cannot erase the session-owned new-event requirement',()=>{
  const r=request();const input=JSON.parse(r.input[0].content);input.requiresNewEvent=true;r.input[0].content=JSON.stringify(input);
  const p=plan();Object.assign(p,{kind:'COMPLETE',goalKind:'STATE',operation:'NONE',targetNodeId:null});
  assert.throws(()=>modelResult(response(p),r));
  Object.assign(p,{commitOutcome:'SUCCEEDED',outcomeReceiptId:7,outcomeNodeIds:[2],newEventNodeIds:[2]});
  p.conditions=[{key:'saved',description:'저장 결과',required:true,truth:'SATISFIED',evidence:'OBSERVED',evidenceNodeIds:[2],requirementId:null,requirementVersion:null,phase:'OUTCOME',expectations:[],basis:{kind:'REQUEST',key:null,quote:'저장',rationale:'',evidenceNodeIds:[]}}];
  assert.doesNotThrow(()=>modelResult(response(p),r));
});

test('partial public observations cannot authorize completion',()=>{
  const r=request();const input=JSON.parse(r.input[0].content);input.observationComplete=false;r.input[0].content=JSON.stringify(input);
  const p=plan();Object.assign(p,{kind:'COMPLETE',goalKind:'STATE',operation:'NONE',targetNodeId:null,
    conditions:[{key:'saved',description:'저장 결과',required:true,truth:'SATISFIED',evidence:'OBSERVED',evidenceNodeIds:[2],
      requirementId:null,requirementVersion:null,phase:'OUTCOME',expectations:[],basis:{kind:'REQUEST',key:null,quote:'저장',rationale:'',evidenceNodeIds:[]}}]});
  assert.throws(()=>modelResult(response(p),r));
  p.kind='OBSERVE';assert.doesNotThrow(()=>modelResult(response(p),r));
});
