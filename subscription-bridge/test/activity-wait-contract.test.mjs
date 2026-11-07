import test from 'node:test';
import assert from 'node:assert/strict';
import { validateModelRequest, modelResult } from '../model-contract.mjs';

test('typed waiting choices use the existing enum contract and never masquerade as completion evidence', () => {
  const request = {
    model:'gpt-6.1-sol',store:false,stream:true,service_tier:'default',reasoning:{effort:'low'},instructions:'공개된 진행 근거로 대기 또는 조작을 고르세요.',
    input:[{role:'user',content:JSON.stringify({goal:'시간을 재고 싶어',nodes:[{id:1,label:'중지'}]})}],
    text:{format:{type:'json_schema',name:'screen_guidance',strict:true,schema:{type:'object',additionalProperties:false,
      properties:{choice:{type:'string',enum:['node_1','wait_user_node_1','wait_system_node_1','manual']},message:{type:'string'},
        evidenceNodeIds:{type:'array',maxItems:3,items:{type:'integer'}}},required:['choice','message','evidenceNodeIds']}}}
  };
  assert.deepEqual(validateModelRequest(request),request);
  function response(choice,ids=[]) {
    return {status:'completed',model:'gpt-6.1-sol',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify({choice,message:'',evidenceNodeIds:ids})}]}],
      usage:{input_tokens:100,output_tokens:10}};
  }
  for(const choice of ['wait_user_node_1','wait_system_node_1'])assert.equal(modelResult(response(choice),request).status,'completed');
  assert.throws(()=>modelResult(response('wait_user_node_99'),request));
  assert.throws(()=>modelResult(response('wait_user_node_1',[1]),request));
});
