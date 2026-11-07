import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateModelRequest,modelResult} from '../model-contract.mjs';
import {taskPlanSchema} from '../task-plan-contract.mjs';

const cases=JSON.parse(readFileSync(new URL('./fixtures/condition-contract-cases.json',import.meta.url),'utf8'));
for(const row of cases)test('goal condition contract: '+row.name,()=>{
  const request={model:'gpt-6.1-terra',store:false,stream:true,service_tier:'default',reasoning:{effort:'low'},
    instructions:'전체 목표의 필수 조건과 현재 근거를 대조한다.',input:[{role:'user',content:JSON.stringify(row.input)}],
    text:{format:{type:'json_schema',name:'task_plan',strict:true,schema:structuredClone(taskPlanSchema)}}};
  const response={status:'completed',model:'gpt-6.1-terra',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify(row.plan)}]}],usage:{input_tokens:100,output_tokens:150}};
  validateModelRequest(request);
  if(row.accepted)assert.doesNotThrow(()=>modelResult(response,request));
  else assert.throws(()=>modelResult(response,request));
});
