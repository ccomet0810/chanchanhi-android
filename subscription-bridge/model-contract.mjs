import { BridgeError } from './protocol.mjs';
import { validVisionInput } from './vision-input.mjs';
import {validTaskPlanSchema,validTaskPlanResult} from './task-plan-contract.mjs';

const invalid = () => new BridgeError('invalid_model_request', '구독 요청의 모델과 형식을 확인해주세요.', 400);
export function validateModelRequest(value) {
  const allowed = new Set(['model', 'store', 'stream', 'service_tier', 'reasoning', 'instructions', 'input', 'text']);
  if (!value || Array.isArray(value) || Object.keys(value).some(k => !allowed.has(k)) ||
      !['gpt-6.1-sol','gpt-6.1-terra'].includes(value.model) || value.store !== false || value.stream !== true || value.service_tier !== 'default' ||
      value.reasoning?.effort !== 'low' || Object.keys(value.reasoning).length !== 1 ||
      typeof value.instructions !== 'string' || !value.instructions || value.instructions.length > 8000 ||
      !Array.isArray(value.input) || value.input.length !== 1 || value.input[0]?.role !== 'user' ||
      Object.keys(value.input[0]).some(k => !['role', 'content'].includes(k)) ||
      !(typeof value.input[0].content === 'string' && value.input[0].content.length <= 60_000 || validVisionInput(value.input))) throw invalid();
  const f = value.text?.format, schema = f?.schema;
  if (Object.keys(value.text ?? {}).length !== 1 || f?.type !== 'json_schema' || f.strict !== true ||
      !['screen_guidance', 'app_routing', 'task_plan'].includes(f.name) || Object.keys(f).some(k => !['type', 'strict', 'name', 'schema'].includes(k)) ||
      schema?.type !== 'object' || schema.additionalProperties !== false || !schema.properties || Array.isArray(schema.properties) ||
      Object.keys(schema).some(k => !['type', 'additionalProperties', 'properties', 'required'].includes(k))) throw invalid();
  if(f.name==='task_plan'){
    if(typeof value.input[0].content!=='string'||!validTaskPlanSchema(schema))throw invalid();
    return structuredClone(value);
  }
  const names = Object.keys(schema.properties);
  if (!names.length || names.length > 200 || !Array.isArray(schema.required) ||
      new Set(schema.required).size !== names.length || schema.required.length !== names.length ||
      names.some(name => !schema.required.includes(name) || !/^(?:choice|message|evidenceNodeIds|route|app_\d+)$/.test(name))) throw invalid();
  for (const [name, field] of Object.entries(schema.properties)) {
    if (name === 'evidenceNodeIds') {
      if (f.name !== 'screen_guidance' || field?.type !== 'array' || field.maxItems !== 3 ||
          Object.keys(field).some(k => !['type', 'maxItems', 'items'].includes(k)) ||
          field.items?.type !== 'integer' || Object.keys(field.items).length !== 1) throw invalid();
      continue;
    }
    if (field?.type !== 'string' || Object.keys(field).some(k => !['type', 'enum'].includes(k))) throw invalid();
    if (name === 'message') { if (field.enum !== undefined) throw invalid(); }
    else if (!Array.isArray(field.enum) || !field.enum.length || field.enum.length > 200 ||
      field.enum.some(v => typeof v !== 'string' || !/^[a-z][a-z0-9_]{0,100}$/.test(v)) || new Set(field.enum).size !== field.enum.length) throw invalid();
  }
  if (f.name === 'screen_guidance' && (![2, 3].includes(names.length) || !names.includes('choice') ||
      !names.includes('message') || (names.length === 3 && !names.includes('evidenceNodeIds')))) throw invalid();
  if (Array.isArray(value.input[0].content) && f.name !== 'screen_guidance') throw invalid();
  return structuredClone(value);
}

export function modelResult(response, request) {
  const model = response?.model;
  if (response?.status !== 'completed' || response.error || typeof model !== 'string' ||
      !(model === request.model || model.startsWith(`${request.model}-`))) throw new BridgeError('invalid_model_result', '구독 응답의 모델을 확인하지 못했습니다.', 502);
  const parts = (response.output ?? []).filter(row => row.type === 'message').flatMap(row => row.content ?? []);
  const texts = parts.filter(p => p.type === 'output_text');
  if (parts.some(p => p.type === 'refusal') || texts.length !== 1 || typeof texts[0].text !== 'string' || texts[0].text.length > 16_384)
    throw new BridgeError('invalid_model_result', '구독 응답 형식을 확인하지 못했습니다.', 502);
  let data;
  try { data = JSON.parse(texts[0].text); } catch { throw new BridgeError('invalid_model_result', '구독 안내가 올바른 JSON이 아닙니다.', 502); }
  const schema = request.text.format.schema;
  const validField = k => k === 'evidenceNodeIds'
    ? Array.isArray(data[k]) && data[k].length <= 3 &&
      data[k].every(id => Number.isSafeInteger(id) && id >= 0 && id <= 2_147_483_647) &&
      new Set(data[k]).size === data[k].length
    : typeof data[k] === 'string' && (!schema.properties[k].enum || schema.properties[k].enum.includes(data[k])) &&
      (k !== 'message' || data[k].length <= 240);
  const valid=request.text.format.name==='task_plan'?validTaskPlanResult(data,request):
    !!data&&!Array.isArray(data)&&Object.keys(data).length===schema.required.length&&
      schema.required.every(k=>Object.hasOwn(data,k)&&validField(k));
  if (!valid)
    throw new BridgeError('invalid_model_result', '구독 안내의 선택값을 확인하지 못했습니다.', 502);
  if (schema.properties.evidenceNodeIds) {
    let input;
    try {
      const content = request.input[0].content;
      input = JSON.parse(typeof content === 'string' ? content : content.find(p => p.type === 'input_text')?.text);
    } catch { /* Missing screen evidence must never authorize arrival. */ }
    const nodeIds = new Set((Array.isArray(input?.nodes) ? input.nodes : []).map(node => node.id));
    if (data.choice === 'confirm' ? !data.evidenceNodeIds.length || data.evidenceNodeIds.some(id => !nodeIds.has(id))
        : data.evidenceNodeIds.length !== 0)
      throw new BridgeError('invalid_model_result', '도착 판단의 화면 근거를 확인하지 못했습니다.', 502);
  }
  for (const key of ['input_tokens', 'output_tokens']) {
    if (!Number.isSafeInteger(response.usage?.[key]) || response.usage[key] < 0 || response.usage[key] > 1_000_000)
      throw new BridgeError('invalid_model_usage', '구독 사용량을 확인하지 못했습니다.', 502);
  }
  return { status: 'completed', model, output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(data) }] }],
    usage: { input_tokens: response.usage.input_tokens, output_tokens: response.usage.output_tokens }, billingMode: 'chatgpt_subscription' };
}
