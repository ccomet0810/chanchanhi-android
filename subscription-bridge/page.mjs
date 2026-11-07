// 로그인은 사용자가 로컬 관리 페이지의 버튼을 눌렀을 때만 시작한다. 개인 서버는 SSH 터널로 연다.
// 인증 토큰과 폰 페어링 키는 HTML이나 UI 상태에 포함하지 않습니다.
export function renderPage(csrfToken, nonce, appName = 'APL 스마트폰 안내') {
  const displayName = appName.replace(/[&<>"']/g, value => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[value]);
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>APL ChatGPT 연결</title>
  <style nonce="${nonce}">body{font-family:system-ui,sans-serif;background:#f5f7fb;color:#16253d;max-width:820px;margin:40px auto;padding:0 24px;line-height:1.65}main{background:white;padding:28px;border-radius:18px;border:1px solid #dce3ee}h1{font-size:28px;margin:0 0 12px}h2{font-size:20px;margin-top:28px}button,select{font:inherit;padding:12px;border:1px solid #b6c6df;border-radius:10px;margin:6px 6px 6px 0}button{cursor:pointer;background:#edf3ff}button.primary{background:#095bd3;color:white;border-color:#095bd3}button:disabled{opacity:.5;cursor:default}p{margin:12px 0}.notice{background:#fff7da;padding:16px;border-radius:10px}.status{background:#eaf1fc;padding:12px;border-radius:10px}.small{font-size:14px;color:#4a5e79}a{color:#0759bd}#models{max-width:100%}</style></head>
  <body><main><h1>APL ChatGPT 연결</h1><p>폰의 요청 문장과 선택한 앱에서 읽을 수 있는 화면 항목을 받아, 다음에 누를 항목을 ChatGPT가 안내합니다. 실제 터치는 사용자가 합니다.</p>
  <p class="status" id="status" role="status">연결 상태를 확인하고 있습니다.</p><p class="notice" id="error" role="alert" hidden></p>
  <h2>1. ChatGPT 계정 연결</h2><p id="account">연결된 계정 없음</p><button class="primary" id="connect">Continue with ChatGPT</button><button id="new-account">다른 계정으로 연결</button><button id="disconnect">연결 해제</button><p id="saved"></p>
  <h2>2. 사용할 모델</h2><select id="models" aria-label="ChatGPT 모델"></select><button id="refresh">모델 목록 새로 확인</button><p class="small" id="model-note">로그인 후 계정에서 실제 사용할 수 있는 목록을 표시합니다.</p>
  <h2>3. 구독 한도만 사용하도록 설정</h2><div class="notice"><p><a href="https://chatgpt.com/settings/usage" target="_blank" rel="noopener noreferrer">ChatGPT 사용량 설정 열기</a></p>
  <p>사용량 설정에서 <strong>${displayName}</strong> 앱을 찾고, 구독 한도 이후 <strong>크레딧 사용을 끄세요.</strong> 필요하면 이 앱의 주간 사용 비율도 낮추세요.</p>
  <p>설정을 확인한 뒤 아래 버튼을 직접 누르면 AI 안내가 활성화됩니다. API 키로 전환하거나 크레딧을 구매하는 기능은 없습니다.</p>
  <button class="primary" id="confirm">APL의 구독 한도 이후 크레딧 사용을 껐습니다</button>
  <p class="small">이 확인은 사용자의 설정 확인 기록입니다. 이 앱은 ChatGPT의 외부 크레딧 설정 변경을 감지할 수 없습니다. 설정을 바꾸면 다시 확인해주세요. 계정 연결 또는 선택을 바꾸면 확인을 초기화합니다.</p></div>
  <p id="cost-state"></p><p class="small">선택한 앱의 읽을 수 있는 조작 항목을 안내하며 요청은 최대 8단계, 동시에 1개입니다. 의미 있는 버튼 정보를 제공하지 않는 화면은 안내가 제한됩니다. 폰 화면 자료는 안내 요청 때만 OpenAI로 전달됩니다.</p></main>
  <script nonce="${nonce}">
  const csrf=${JSON.stringify(csrfToken)};
  const byId=id=>document.getElementById(id);
  let busy=false;
  async function post(path,body={}){const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-APL-CSRF':csrf},body:JSON.stringify(body)});const result=await response.json();if(!response.ok)throw Error(result.message||'요청을 처리하지 못했습니다.');return result;}
  async function update(){try{const response=await fetch('/ui/state',{cache:'no-store'});const state=await response.json();byId('status').textContent=state.message;byId('account').textContent=state.account?('연결 계정: '+(state.account.email||state.account.name||state.account.label)+' · '+state.account.label):'연결된 계정 없음';
  const picker=byId('models');if(document.activeElement!==picker){picker.replaceChildren();for(const model of state.models){const option=document.createElement('option');option.value=model.slug;option.textContent=model.displayName+' ('+model.slug+')';option.selected=model.slug===state.model;picker.append(option);}}
  byId('model-note').textContent=state.model?'현재 선택 모델: '+state.model:'모델 목록이 없습니다. 로그인한 뒤 목록을 확인해주세요.';
  byId('confirm').disabled=busy||!state.canConfirm;byId('refresh').disabled=busy||!state.connected;byId('disconnect').disabled=busy||!state.account;byId('cost-state').textContent=state.costConfirmed?'크레딧 사용 중지 확인 완료 · 구독 안내 활성화':'크레딧 설정 확인 전 · AI 안내 차단';
  byId('saved').replaceChildren();for(const account of state.accounts){const button=document.createElement('button');button.textContent=(account.email||account.label)+(account.active?' · 선택됨':' · 선택');button.disabled=busy||account.active;button.addEventListener('click',()=>action(()=>post('/ui/select-account',{clientId:account.clientId})));byId('saved').append(button);}
  }catch{byId('status').textContent='로컬 관리 서버 연결을 확인해주세요.';}}
  // 상태 자동 갱신이 실패 설명을 덮어쓰지 않도록 별도 알림을 다음 사용자 동작까지 유지합니다.
  async function action(fn){if(busy)return;busy=true;byId('error').hidden=true;byId('error').textContent='';try{await fn();}catch(error){byId('error').textContent=error.message;byId('error').hidden=false;}finally{busy=false;await update();}}
  byId('connect').onclick=()=>action(async()=>{const result=await post('/ui/connect');location.assign(result.url);});
  byId('new-account').onclick=()=>action(async()=>{const result=await post('/ui/connect',{newAccount:true});location.assign(result.url);});
  byId('disconnect').onclick=()=>action(()=>post('/ui/disconnect'));
  byId('confirm').onclick=()=>action(()=>post('/ui/cost-confirm',{confirmed:true}));
  byId('models').onchange=()=>action(()=>post('/ui/model',{model:byId('models').value}));
  byId('refresh').onclick=()=>action(()=>post('/ui/refresh-models'));
  update();setInterval(()=>{if(!busy)update();},2000);
  </script></body></html>`;
}
