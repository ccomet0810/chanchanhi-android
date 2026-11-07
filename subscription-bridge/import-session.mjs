import { VmSecureStore } from './vm-storage.mjs';
import { accountReady } from './server.mjs';

// SSH stdin에서만 받아 암호화 저장한다. 계정 외 서버 API 키·비용·연결 키는 유지한다.
try {
  const parts=[];let size=0;
  for await(const part of process.stdin){size+=part.length;if(size>2*1024*1024)throw Error();parts.push(part);}
  const bytes=Buffer.concat(parts);let value;
  try{value=JSON.parse(bytes.toString('utf8'));}finally{bytes.fill(0);}
  if(value?.version!==1 || !accountReady(value.account) || typeof value.account.clientId!=='string' ||
      !/^[A-Za-z0-9_-]{1,200}$/.test(value.account.clientId))throw Error();
  const store=new VmSecureStore(process.argv[2]),state=await store.initialize();
  const old=state.accounts.find(a=>a.clientId===value.account.clientId);
  if(old?.subject && old.subject!==value.account.subject)throw Error();
  state.accounts=[...state.accounts.filter(a=>a.clientId!==value.account.clientId),value.account];
  state.activeClientId=value.account.clientId;await store.write(state);
  console.log('APL_SUBSCRIPTION_IMPORTED');
}catch{console.error('구독 세션 이전을 완료하지 못했습니다. 기존 인증 자료는 보존했습니다.');process.exitCode=1;}
