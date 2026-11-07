import { randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile, rename, rm, lstat, chmod } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const helper = join(dirname(fileURLToPath(import.meta.url)), 'dpapi.ps1');

// stdout은 이 함수에서만 받습니다. DPAPI 복호화 결과를 콘솔이나 진단 로그에 전달하지 않습니다.
function runPowerShell(scriptArguments, input = '', timeout = 15_000) {
  return new Promise((resolve, reject) => {
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', ...scriptArguments], {
      windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], shell: false,
    });
    let output = '';
    let exceeded = false;
    const timer = setTimeout(() => child.kill(), timeout);
    child.stdout.setEncoding('utf8');
    child.stdout.on('data', value => {
      output += value;
      if (output.length > 2 * 1024 * 1024) { exceeded = true; child.kill(); }
    });
    child.stderr.resume();
    child.once('error', () => { clearTimeout(timer); reject(new Error('Windows 보안 저장 기능을 실행할 수 없습니다.')); });
    child.once('close', code => {
      clearTimeout(timer);
      if (code !== 0 || exceeded) reject(new Error('Windows 보안 저장에 실패했습니다. 저장된 자료는 유지됩니다.'));
      else resolve(output);
    });
    child.stdin.on('error', () => {});
    child.stdin.end(input, 'utf8');
  });
}

export async function protectDirectory(path) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink()) throw new Error('보안 저장 폴더는 실제 개인 폴더여야 합니다.');
  if (process.platform !== 'win32') { await chmod(path, 0o700); return; }
  // 상속된 ACL을 제거하고 현재 사용자 SID 한 명에게만 접근을 허용합니다.
  // PowerShell 7에서 물려받은 PSModulePath 때문에 Windows PowerShell의 Set-Acl 자동 로드가
  // 실패할 수 있습니다. 동일 ACL을 .NET API로 적용하여 셸 모듈 자동 로드에 의존하지 않습니다.
  const script = "$ErrorActionPreference='Stop'; $taskPath=[Console]::In.ReadToEnd(); $taskSid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User; $taskAcl=New-Object System.Security.AccessControl.DirectorySecurity; $taskAcl.SetOwner($taskSid); $taskAcl.SetAccessRuleProtection($true,$false); $taskRule=New-Object System.Security.AccessControl.FileSystemAccessRule($taskSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'); $taskAcl.AddAccessRule($taskRule); [System.IO.Directory]::SetAccessControl($taskPath,$taskAcl)";
  await runPowerShell(['-Command', script], path);
}

export class SecureStore {
  constructor(directory) { this.directory = directory; this.filename = join(directory, 'credentials.dpapi.json'); }
  async initialize() {
    if (process.platform !== 'win32') throw new Error('이 테스트 브리지는 Windows DPAPI를 사용합니다.');
    await protectDirectory(this.directory);
    let saved;
    try {
      const info = await lstat(this.filename);
      if (!info.isFile() || info.isSymbolicLink() || info.size > 2 * 1024 * 1024) throw new Error('보안 저장 파일 형식이 올바르지 않습니다.');
      const envelope = JSON.parse(await readFile(this.filename, 'utf8'));
      if (envelope.version !== 1 || envelope.provider !== 'windows-dpapi-current-user' || typeof envelope.ciphertext !== 'string') throw new Error('보안 저장 형식이 일치하지 않습니다.');
      saved = JSON.parse(await runPowerShell(['-File', helper], JSON.stringify({ operation: 'decrypt', data: envelope.ciphertext })));
      if (saved.version !== 1 || !/^urn:uuid:[a-f0-9-]+$/.test(saved.hostId) || !Array.isArray(saved.accounts)) throw new Error('저장된 계정 자료가 올바르지 않습니다.');
    } catch (error) {
      if (error.code !== 'ENOENT') throw new Error('저장된 인증 자료를 읽을 수 없습니다. 기존 파일을 보존했습니다.');
      saved = { version: 1, hostId: `urn:uuid:${randomUUID()}`, accounts: [], activeClientId: null };
      await this.write(saved);
    }
    return saved;
  }
  async write(state) {
    const ciphertext = await runPowerShell(['-File', helper], JSON.stringify({ operation: 'encrypt', data: JSON.stringify(state) }));
    const temporary = join(this.directory, `.credentials-${randomUUID()}.tmp`);
    try {
      await writeFile(temporary, JSON.stringify({ version: 1, provider: 'windows-dpapi-current-user', ciphertext }), { flag: 'wx', mode: 0o600 });
      await rename(temporary, this.filename);
    } finally { await rm(temporary, { force: true }); }
  }
}

export async function loadPairingToken(directory) {
  const filename = join(directory, 'pairing-token.txt');
  try {
    const info = await lstat(filename);
    if (!info.isFile() || info.isSymbolicLink() || info.size > 1024) throw new Error('페어링 파일 형식이 올바르지 않습니다.');
    const token = await readFile(filename, 'utf8');
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('페어링 키 형식이 올바르지 않습니다.');
    return token;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const token = randomBytes(32).toString('base64url');
    await writeFile(filename, token, { flag: 'wx', mode: 0o600 });
    return token;
  }
}
