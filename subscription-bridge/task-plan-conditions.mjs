// Contract defense only. Native session ownership and app-result meaning are checked on Android.
const normalize=s=>s.trim().replace(/\s+/g,' ');
const expectationKeys=values=>{
  values??=[];
  if(!Array.isArray(values)||values.length>30||values.some(v=>!v||!['RANGE','CHECKED','SELECTED','INPUT_PRESENT'].includes(v.property)||
    !v.subject||!['role','resource','label'].every(k=>typeof v.subject[k]==='string')||
    !(v.property==='RANGE'?typeof v.expectedNumber==='number'&&Number.isFinite(v.expectedNumber)&&v.expectedBoolean===null:
      v.expectedNumber===null&&typeof v.expectedBoolean==='boolean')))return null;
  return values.map(v=>JSON.stringify([v.property,v.subject.role,v.subject.resource,v.subject.label,v.expectedNumber,v.expectedBoolean])).sort();
};
const sameExpectations=(a,b)=>{const aa=expectationKeys(a),bb=expectationKeys(b);return aa!==null&&bb!==null&&JSON.stringify(aa)===JSON.stringify(bb)};
const strengthens=(a,b)=>{
  const old=expectationKeys(a.expectations),next=expectationKeys(b.expectations);
  return (a.staleAnswerKey??null)===null&&normalize(a.description)===normalize(b.description)&&a.required===b.required&&(a.phase??'OUTCOME')===b.phase&&
    old!==null&&next!==null&&next.length>old.length&&old.every(x=>next.includes(x));
};
export function validConditionProposal(value,input){
  const previous=Array.isArray(input.requirements)?input.requirements:[];
  if(previous.length>128||previous.some(r=>!Number.isSafeInteger(r.id)||r.id<1||!Number.isSafeInteger(r.version)||r.version<1||
    typeof r.description!=='string'||typeof r.required!=='boolean'||expectationKeys(r.expectations)===null||!['OUTCOME','PREPARATION'].includes(r.phase??'OUTCOME')||!['REQUEST','ANSWER','INFERRED'].includes(r.origin)))return false;
  const original=new Map(previous.map(r=>[r.id,r]));if(original.size!==previous.length)return false;
  const owned=new Map(previous.map(r=>[r.id,{...r}]));
  const sourceValid=b=>{
    if(!b.quote.trim()||b.rationale.length>320||(b.key?.length??0)>100)return false;
    const source=b.kind==='ANSWER'?(b.key===null?null:input.answers?.[b.key]):b.key===null?input.request:null;
    return typeof source==='string'&&source.includes(b.quote)&&(b.kind!=='INFERRED'||!!b.rationale.trim());
  };
  const reports=value.conditions.filter(c=>c.requirementId!==null);
  if(new Set(reports.map(c=>c.requirementId)).size!==reports.length)return false;
  const edits=new Map(value.conditionEdits.map(e=>[e.requirementId,e]));if(edits.size!==value.conditionEdits.length)return false;
  for(const e of value.conditionEdits){
    const r=original.get(e.requirementId);
    if(!r||r.version!==e.expectedVersion||r.retired&&e.kind!=='REPLACE'||!e.reason.trim()||e.reason.length>320||!sourceValid(e.basis))return false;
    const report=reports.find(c=>c.requirementId===r.id);
    const refinement=e.kind==='REPLACE'&&report&&strengthens(r,report)&&e.basis.kind==='INFERRED'&&e.basis.evidenceNodeIds.length&&e.basis.rationale.trim();
    if(refinement){ /* Only extra checks; original user authority is retained below. */ }
    else if(e.basis.kind==='ANSWER'){
      const a=input.answerAuthorities?.[e.basis.key];
      if(!a||!a.requirementIds?.includes(r.id)||!(a.revision>r.changedAtRevision)||!(a.revision<=input.goalRevision))return false;
    }else if(r.origin!=='INFERRED'||!e.basis.evidenceNodeIds.length||!e.basis.rationale.trim())return false;
    if(e.kind==='RETIRE'){
      if(report)return false;
      owned.set(r.id,{...r,retired:true,version:r.version+1,staleAnswerKey:null});
    }else if(!report)return false;
  }
  let serial=Math.max(0,...owned.keys());const bound=[];
  for(const c of value.conditions){
    if((c.requirementId===null)!==(c.requirementVersion===null))return false;
    let r;
    if(c.requirementId===null){
      if(!c.basis||!sourceValid(c.basis)||[...owned.values()].some(r=>(r.phase??'OUTCOME')===c.phase&&normalize(r.description)===normalize(c.description)))return false;
      r={id:++serial,version:1,description:normalize(c.description),required:c.required,retired:false,origin:c.basis.kind,phase:c.phase,expectations:c.expectations};
    }else{
      const before=original.get(c.requirementId),edit=edits.get(c.requirementId);
      if(!before||before.version!==c.requirementVersion||before.retired&&edit?.kind!=='REPLACE'||c.basis!==null)return false;
      if((before.phase??'OUTCOME')!==c.phase)return false;
      if(!edit){
        if(normalize(before.description)!==normalize(c.description)||before.required!==c.required||before.staleAnswerKey||!sameExpectations(before.expectations,c.expectations))return false;
        r=before;
      }else{
        if(edit.kind!=='REPLACE')return false;
        r={...before,version:before.version+1,description:normalize(c.description),required:c.required,retired:false,staleAnswerKey:null,
          origin:edit.basis.kind==='INFERRED'&&strengthens(before,c)?before.origin:edit.basis.kind,expectations:c.expectations};
      }
    }
    owned.set(r.id,r);bound.push({...c,requirementId:r.id});
  }
  if(owned.size>128||[...owned.values()].filter(r=>!r.retired).length>30)return false;
  if(value.question&&(new Set(value.question.conditionKeys).size!==value.question.conditionKeys.length||
    value.question.conditionKeys.some(key=>!bound.some(c=>c.key===key))))return false;
  if(value.kind==='COMPLETE'){
    const required=[...owned.values()].filter(r=>!r.retired&&r.required&&(r.phase??'OUTCOME')==='OUTCOME');
    if(!required.length||required.some(r=>{
      const c=bound.find(c=>c.requirementId===r.id);
      return !c||r.staleAnswerKey||c.truth!=='SATISFIED'||c.evidence!=='OBSERVED'||!c.evidenceNodeIds.length;
    }))return false;
  }
  return true;
}
