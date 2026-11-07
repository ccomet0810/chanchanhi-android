import {readFileSync} from 'node:fs';
import {validConditionProposal} from './task-plan-conditions.mjs';

// Exact schema whitelist. This does not grant tool calls, app execution, or semantic truth.
export const taskPlanSchema=JSON.parse(readFileSync(new URL('./task-plan.schema.json',import.meta.url),'utf8'));
function canonical(v){
  if(Array.isArray(v))return '['+v.map(canonical).join(',')+']';
  if(v && typeof v==='object')return '{'+Object.keys(v).sort().map(k=>JSON.stringify(k)+':'+canonical(v[k])).join(',')+'}';
  return JSON.stringify(v);
}
export const validTaskPlanSchema=value=>canonical(value)===canonical(taskPlanSchema);
function matches(value,schema){
  if(value===null)return schema.type==='null'||Array.isArray(schema.type)&&schema.type.includes('null');
  const type=Array.isArray(schema.type)?schema.type.filter(t=>t!=='null')[0]:schema.type;
  if(type==='string')return typeof value==='string'&&value.length<=500&&(!schema.enum||schema.enum.includes(value));
  if(type==='boolean')return typeof value==='boolean';
  if(type==='integer')return Number.isSafeInteger(value)&&value>=0&&value<=2_147_483_647;
  if(type==='number')return typeof value==='number'&&Number.isFinite(value);
  if(type==='array')return Array.isArray(value)&&value.length<=schema.maxItems&&value.every(v=>matches(v,schema.items));
  if(type==='object')return !!value&&!Array.isArray(value)&&typeof value==='object'&&
    Object.keys(value).length===schema.required.length&&schema.required.every(k=>Object.hasOwn(value,k)&&matches(value[k],schema.properties[k]));
  return false;
}
function validTaskGraph(value){
  const tasks=new Map(value.tasks.map(t=>[t.key,t]));
  if(tasks.size!==value.tasks.length||new Set(value.conditions.map(c=>c.key)).size!==value.conditions.length)return false;
  if(value.tasks.some(t=>!t.key.trim()||!t.description.trim()||t.dependsOn.some(key=>!tasks.has(key))))return false;
  const visiting=new Set(),visited=new Set();
  function cyclic(key){
    if(visited.has(key))return false;
    if(visiting.has(key))return true;
    visiting.add(key);
    if(tasks.get(key).dependsOn.some(cyclic))return true;
    visiting.delete(key);visited.add(key);return false;
  }
  if([...tasks.keys()].some(cyclic))return false;
  return value.relations.every(r=>r.members.length>0&&new Set(r.members).size===r.members.length&&r.members.every(key=>tasks.has(key)));
}
function validExpectation(v){
  const s=v.subject;
  return !!s.role.trim()&&s.role.length<=140&&s.resource.length<=180&&s.label.length<=140&&
    (s.resource===''?!!s.label.trim():!!s.resource.trim()&&s.label==='')&&
    (v.property==='RANGE'?typeof v.expectedNumber==='number'&&Number.isFinite(v.expectedNumber)&&v.expectedBoolean===null:
      v.expectedNumber===null&&typeof v.expectedBoolean==='boolean');
}
function validValueEvidence(value,input){
  const nodes=Array.isArray(input.nodes)?input.nodes:[],defaults=input.nodeDefaults??{};
  const field=(n,k)=>Object.hasOwn(n,k)?n[k]:defaults[k];
  const editable=n=>field(n,'editable')===true||n.role?.endsWith('EditText')===true;
  const publicNode=(n,property)=>n.visibleToUser!==false&&!n.password&&!n.sensitiveContent&&
    (property==='INPUT_PRESENT'?editable(n):!field(n,'editable')&&!n.role?.endsWith('EditText'));
  for(const c of value.conditions){
    if(c.expectations.some(v=>!validExpectation(v))||new Set(c.expectations.map(v=>JSON.stringify([v.property,v.subject.role,v.subject.resource,v.subject.label]))).size!==c.expectations.length)return false;
    const subjectMatches=(v,n)=>n.role===v.subject.role&&(v.subject.resource?(field(n,'resource')??'')===v.subject.resource:n.label===v.subject.label);
    if(c.truth==='SATISFIED')for(const v of c.expectations){
      if(c.evidence!=='OBSERVED')return false;
      const s=v.subject,matches=nodes.filter(n=>publicNode(n,v.property)&&n.role===s.role&&(s.resource?(field(n,'resource')??'')===s.resource:n.label===s.label));
      if(matches.length!==1||!c.evidenceNodeIds.includes(matches[0].id))return false;
      const n=matches[0];
      if(v.property==='RANGE'){
        const r=field(n,'range');
        if(!r||![r.min,r.max,r.current].every(x=>typeof x==='number'&&Number.isFinite(x))||!(r.max>r.min)||r.current<r.min||r.current>r.max||
          v.expectedNumber<r.min||v.expectedNumber>r.max||r.current!==v.expectedNumber)return false;
      }else if(v.property==='CHECKED'){
        const checked=field(n,'checked');if(field(n,'checkable')!==true||![0,1].includes(checked)||(checked===1)!==v.expectedBoolean)return false;
      }else if(v.property==='SELECTED'){
        if(typeof field(n,'selected')!=='boolean'||field(n,'selected')!==v.expectedBoolean)return false;
      }else if(typeof field(n,'inputPresent')!=='boolean'||field(n,'inputPresent')!==v.expectedBoolean)return false;
    }
    if(c.phase==='PREPARATION'&&c.required&&['SATISFIED','UNSATISFIED'].includes(c.truth))for(const id of c.evidenceNodeIds){
      const n=nodes.find(n=>n.id===id);if(!n||!publicNode(n))continue;
      const r=field(n,'range');
      if(r&&[r.min,r.max,r.current].every(x=>typeof x==='number'&&Number.isFinite(x))&&r.max>r.min&&r.current>=r.min&&r.current<=r.max&&
        !c.expectations.some(v=>v.property==='RANGE'&&subjectMatches(v,n)))return false;
      if(field(n,'checkable')===true&&!c.expectations.some(v=>v.property==='CHECKED'&&subjectMatches(v,n)))return false;
    }
  }
  return true;
}
function validPreparation(value,input){
  const ready=c=>c.truth==='SATISFIED'&&c.evidence==='OBSERVED'&&c.evidenceNodeIds.length>0;
  const gate=value.actionGates.find(g=>g.targetNodeId===value.targetNodeId);
  if(value.effect==='COMMIT'&&value.conditions.some(c=>gate.conditionKeys.includes(c.key)&&c.required&&!ready(c)))return false;
  const history=input.preparationHistory??[];
  const previous=Array.isArray(input.requirements)?input.requirements:[];
  if(!Array.isArray(history)||history.length>64||history.some(h=>typeof h.role!=='string'||typeof h.resource!=='string'||typeof h.label!=='string'||
    !Array.isArray(h.requirementIds)||!h.requirementIds.length||new Set(h.requirementIds).size!==h.requirementIds.length||
    h.requirementIds.some(id=>!Number.isSafeInteger(id)||id<1||!previous.some(r=>r.id===id&&r.phase==='PREPARATION'))))return false;
  if(value.kind!=='GUIDE')return true;
  const node=(Array.isArray(input.nodes)?input.nodes:[]).find(n=>n.id===value.targetNodeId);
  if(!node)return true; // Native grounding rejects unavailable controls.
  const matches=history.filter(h=>h.resource&&h.resource===(node.resource??'')||h.label&&h.label===node.label&&h.role===node.role);
  if(matches.length>1)return false;
  if(!matches.length)return true;
  if(value.operation!=='TAP'||input.observationComplete===false)return false;
  const retired=new Set(value.conditionEdits.filter(e=>e.kind==='RETIRE').map(e=>e.requirementId));
  const ids=matches[0].requirementIds.filter(id=>previous.some(r=>r.id===id&&!r.retired&&r.required)&&!retired.has(id)&&
    !value.conditionEdits.some(e=>e.requirementId===id&&e.kind==='REPLACE'&&value.conditions.some(c=>c.requirementId===id&&!c.required)));
  if(!ids.length&&!gate)return false;
  return ids.every(id=>value.conditions.some(c=>c.requirementId===id&&c.phase==='PREPARATION'&&ready(c)));
}
export function validTaskPlanResult(value,request){
  if(!matches(value,taskPlanSchema))return false;
  if(!validTaskGraph(value))return false;
  let input;try{input=JSON.parse(request.input[0].content)}catch{return false}
  if(!validValueEvidence(value,input))return false;
  if(input?.observationComplete===false&&(value.kind==='COMPLETE'||value.effect==='COMMIT'))return false;
  if(new Set(value.actionGates.map(g=>g.targetNodeId)).size!==value.actionGates.length)return false;
  if(value.actionGates.some(g=>!g.conditionKeys.length||new Set(g.conditionKeys).size!==g.conditionKeys.length||
    g.conditionKeys.some(key=>!value.conditions.some(c=>c.key===key&&c.phase==='PREPARATION'))||
    !value.conditions.some(c=>g.conditionKeys.includes(c.key)&&c.required)))return false;
  if(value.effect==='COMMIT'&&!value.actionGates.some(g=>g.targetNodeId===value.targetNodeId))return false;
  if(!validPreparation(value,input))return false;
  const ids=new Set((Array.isArray(input?.nodes)?input.nodes:[]).map(n=>n.id));
  const refs=[...value.relevantNodeIds,...value.newEventNodeIds,...value.outcomeNodeIds,...value.scopeNodeIds,...value.tasks.flatMap(t=>t.nodeIds),
    ...value.conditions.flatMap(c=>c.basis?.evidenceNodeIds??[]),...value.conditionEdits.flatMap(e=>e.basis.evidenceNodeIds),
    ...value.conditions.flatMap(c=>c.evidenceNodeIds),...value.actionGates.map(g=>g.targetNodeId),...(value.targetNodeId===null?[]:[value.targetNodeId])];
  if(refs.some(id=>!ids.has(id)))return false;
  if(value.kind==='ASK'?!value.question:value.question!==null)return false;
  if(value.kind==='GUIDE'&&(value.targetNodeId===null||value.operation==='NONE'))return false;
  if(value.kind==='WAIT'&&value.waitKind==='NONE')return false;
  if(value.effect==='COMMIT'&&(value.kind!=='GUIDE'||value.operation!=='TAP'))return false;
  if(['SUCCEEDED','FAILED'].includes(value.commitOutcome)&&!value.outcomeNodeIds.length)return false;
  const receipts=new Set((Array.isArray(input?.operationHistory)?input.operationHistory:[])
    .map(r=>r.receiptId).filter(id=>Number.isSafeInteger(id)&&id>0));
  if((value.commitOutcome==='NONE')!==(value.outcomeReceiptId===null))return false;
  if(value.outcomeReceiptId!==null&&!receipts.has(value.outcomeReceiptId))return false;
  if(value.kind==='COMPLETE'&&(input?.requiresNewEvent===true||value.goalKind==='NEW_EVENT')&&
    (!value.newEventNodeIds.length||value.commitOutcome!=='SUCCEEDED'||value.outcomeReceiptId===null))return false;
  return value.message.length>0&&value.message.length<=320&&validConditionProposal(value,input);
}
