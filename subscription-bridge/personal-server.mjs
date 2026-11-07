import { readFile } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { VmSecureStore, loadVmPairingToken } from './vm-storage.mjs';
import { createBridge } from './server.mjs';
import { createPersonalApiServer } from './personal-api-server.mjs';

export function validatePersonalConfig(config) {
  if (config?.version !== 1 || !isAbsolute(config.dataDirectory ?? '')) throw new Error('개인 서버 설정 형식 오류');
  const origin = new URL(config.publicOrigin);
  if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash || origin.origin !== config.publicOrigin) throw new Error('HTTPS 공개 주소 형식 오류');
  for (const port of [config.controlPort ?? 18765, config.gatewayPort ?? 18766]) {
    if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('서버 내부 포트 형식 오류');
  }
  if ((config.controlPort ?? 18765) === (config.gatewayPort ?? 18766)) throw new Error('서버 포트가 겹칩니다.');
  return { dataDirectory: config.dataDirectory, publicOrigin: origin.origin,
    controlPort: config.controlPort ?? 18765, gatewayPort: config.gatewayPort ?? 18766 };
}

export async function startPersonalServer(config) {
  const checked = validatePersonalConfig(config);
  const store = new VmSecureStore(checked.dataDirectory);
  const initialState = await store.initialize();
  const pairingToken = await loadVmPairingToken(checked.dataDirectory);
  // OAuth와 Decisions 키는 같은 암호화 저장소에서만 읽는다. 계정 관리는 loopback에만 열고 gateway는 폰 API만 공개한다.
  const bridge = createBridge({ store,initialState,pairingToken,port:checked.controlPort });
  const gateway = createPersonalApiServer({ publicOrigin: checked.publicOrigin,
    bindHost: '127.0.0.1', port: checked.gatewayPort, localPort: checked.controlPort });
  await bridge.listen();
  try { await gateway.listen(); } catch (error) { await bridge.close(); throw error; }
  return { close: async () => { await gateway.close(); await bridge.close(); } };
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) {
  let runtime;
  (async () => {
    // 설정에는 주소·경로·포트만 둔다. OpenAI 토큰이나 폰 연결 키는 명령 인수에 넣지 않는다.
    const configPath = process.argv[2];
    if (!configPath || !isAbsolute(configPath)) throw new Error('설정 파일의 절대 경로가 필요합니다.');
    runtime = await startPersonalServer(JSON.parse(await readFile(configPath, 'utf8')));
    console.log('APL 구독·Decisions 개인 서버 준비 완료 · 내부 gateway는 loopback 전용');
    const stop = () => runtime.close().then(() => process.exit(0));
    process.once('SIGINT', stop); process.once('SIGTERM', stop);
  })().catch(() => { console.error('APL 개인 서버를 시작하지 못했습니다. 설정·포트·개인 저장소 권한을 확인해주세요.'); process.exitCode = 1; });
}
