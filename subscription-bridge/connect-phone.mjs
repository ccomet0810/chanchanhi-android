import { spawn } from 'node:child_process';
import { readFile, access } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url));
const sdk = process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME ||
  (process.platform === 'win32' ? join(process.env.LOCALAPPDATA, 'Android/Sdk') : join(homedir(), 'Library/Android/sdk'));
const adb = join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
await access(adb);
const run = (args, stdin = '') => new Promise((resolve, reject) => {
  const child = spawn(adb, args, { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  let output = ''; child.stdout.setEncoding('utf8'); child.stdout.on('data', c => { output += c; }); child.stderr.resume();
  child.once('error', () => reject(new Error('Android 연결 도구를 실행할 수 없습니다.')));
  child.once('close', code => code === 0 ? resolve(output) : reject(new Error('USB 디버깅 승인과 APL 디버그 앱 설치를 확인해주세요.')));
  child.stdin.on('error', () => {}); child.stdin.end(stdin);
});
try {
  const devices = (await run(['devices'])).split(/\r?\n/).map(s => s.trim()).filter(s => /\sdevice$/.test(s)).map(s => s.split(/\s/)[0]);
  const serial = process.argv[2] || (devices.length === 1 ? devices[0] : null);
  if (!serial || !devices.includes(serial)) throw new Error('연결된 휴대폰이 한 대여야 합니다. 여러 대이면 기기 번호를 지정해주세요.');
  const token = (await readFile(join(directory, '.local/pairing-token.txt'), 'utf8')).trim();
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('PC 구독 프로그램을 먼저 실행해주세요.');
  const status = await fetch('http://127.0.0.1:18765/api/model-status', { headers: { Authorization: `Bearer ${token}` } });
  const info = await status.json();
  if (!status.ok || !info.ready || !info.models?.includes('gpt-6.1-terra')) throw new Error('PC의 ChatGPT 연결과 크레딧 사용 중지 확인을 먼저 완료해주세요.');
  await run(['-s', serial, 'reverse', 'tcp:18765', 'tcp:18765']);
  await run(['-s', serial, 'shell', 'am', 'force-stop', 'com.example.aplassistmvp0']);
  // 연결 키는 인수·클립보드·출력에 넣지 않고 앱 전용 파일에 stdin으로만 전달한다.
  await run(['-s', serial, 'shell', 'run-as', 'com.example.aplassistmvp0', 'sh', '-c', "'cat > files/bridge-connection.json'"],
    JSON.stringify({version:1,mode:'usb',endpoint:'http://127.0.0.1:18765',pairingToken:token,certificateSha256:''}));
  await run(['-s', serial, 'shell', 'am', 'start', '-n', 'com.example.aplassistmvp0/.MainActivity']);
  console.log('APL 구독 USB 연결 완료. 앱에서 ChatGPT 구독 · 6.1 Terra를 선택하세요.');
} catch (error) { console.error(error.message); process.exitCode = 1; }
