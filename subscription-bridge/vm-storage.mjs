import { randomBytes, randomUUID, createCipheriv, createDecipheriv } from 'node:crypto';
import { constants } from 'node:fs';
import { mkdir, lstat, chmod, open, rename, rm } from 'node:fs/promises';
import { join, isAbsolute } from 'node:path';

const PROVIDER = 'personal-host-aes-256-gcm-v1';
const AAD = Buffer.from('APL personal host credential envelope v1');
const PLAINTEXT_LIMIT = 2 * 1024 * 1024;
const ENVELOPE_LIMIT = 3 * 1024 * 1024;

function stateValid(state) {
  return state?.version === 1 && /^urn:uuid:[0-9a-f-]{36}$/.test(state.hostId) && Array.isArray(state.accounts);
}

// AES-GCM은 암호문 변조도 검사한다. 키·토큰·복호화 원문은 로그에 전달하지 않는다.
export function encryptState(state, key) {
  if (!stateValid(state) || key.length !== 32) throw new Error('개인 서버 인증 자료 형식이 올바르지 않습니다.');
  const plaintext = Buffer.from(JSON.stringify(state));
  if (plaintext.length > PLAINTEXT_LIMIT) throw new Error('인증 자료 저장 한도를 넘었습니다.');
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(AAD);
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  plaintext.fill(0);
  return { version: 1, provider: PROVIDER, iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') };
}

function decode(value, length) {
  if (typeof value !== 'string' || !value || value.length > ENVELOPE_LIMIT) throw new Error('암호화 형식 오류');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length > ENVELOPE_LIMIT || bytes.toString('base64') !== value || (length !== undefined && bytes.length !== length)) throw new Error('암호화 형식 오류');
  return bytes;
}

export function decryptState(envelope, key) {
  try {
    if (envelope?.version !== 1 || envelope.provider !== PROVIDER || key.length !== 32) throw new Error('암호화 형식 오류');
    const decipher = createDecipheriv('aes-256-gcm', key, decode(envelope.iv, 12));
    decipher.setAAD(AAD);
    decipher.setAuthTag(decode(envelope.tag, 16));
    const plaintext = Buffer.concat([decipher.update(decode(envelope.ciphertext)), decipher.final()]);
    try {
      if (plaintext.length > PLAINTEXT_LIMIT) throw new Error('인증 자료 저장 한도 오류');
      const saved = JSON.parse(plaintext.toString('utf8'));
      if (!stateValid(saved)) throw new Error('인증 자료 형식 오류');
      return saved;
    } finally { plaintext.fill(0); }
  } catch { throw new Error('개인 서버 인증 자료를 검증하지 못했습니다. 기존 자료를 보존합니다.'); }
}

async function readPrivateFile(filename, limit, requiredMode) {
  // 심볼릭 링크를 따라가지 않고 실제 열린 파일의 소유자·권한·크기를 확인한다.
  const handle = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.uid !== process.getuid() || (info.mode & 0o077) !== 0 || info.size > limit ||
        (requiredMode !== undefined && (info.mode & 0o777) !== requiredMode)) throw new Error('개인 파일 권한 또는 형식 오류');
    return await handle.readFile();
  } finally { await handle.close(); }
}

export class VmSecureStore {
  constructor(directory) {
    if (!isAbsolute(directory)) throw new Error('개인 서버 저장소는 절대 경로여야 합니다.');
    this.directory = directory;
    this.filename = join(directory, 'credentials.encrypted.json');
    this.keyFilename = join(directory, 'credential-key.bin');
    this.key = null;
  }
  async initialize() {
    if (!['linux', 'darwin'].includes(process.platform)) throw new Error('개인 저장소는 Linux/macOS의 소유자·파일 권한을 사용합니다.');
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const info = await lstat(this.directory);
    if (!info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid()) throw new Error('개인 서버 저장 폴더를 확인해주세요.');
    await chmod(this.directory, 0o700);
    let credentialExists = false;
    try { await lstat(this.filename); credentialExists = true; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    try { this.key = await readPrivateFile(this.keyFilename, 32); }
    catch (error) {
      // 기존 암호문이 있으면 누락된 키를 새로 만들거나 계정을 초기화하지 않는다.
      if (error.code !== 'ENOENT' || credentialExists) throw new Error('서버의 암호화 키를 읽을 수 없습니다. 기존 자료를 보존합니다.');
      const key = randomBytes(32);
      const handle = await open(this.keyFilename, 'wx', 0o600);
      try { await handle.writeFile(key); await handle.sync(); } finally { await handle.close(); }
      this.key = key;
    }
    if (this.key.length !== 32) throw new Error('서버의 암호화 키 형식이 올바르지 않습니다.');
    if (credentialExists) {
      // base64 envelope는 평문보다 커지므로 파일 한도와 복호화 평문 한도를 분리합니다.
      try { return decryptState(JSON.parse((await readPrivateFile(this.filename, ENVELOPE_LIMIT)).toString('utf8')), this.key); }
      catch { throw new Error('서버 인증 자료를 읽을 수 없습니다. 기존 자료를 보존합니다.'); }
    }
    // host ID는 서버마다 한 번 만들고 로그인·갱신·재시작 때 동일하게 유지한다.
    const state = { version: 1, hostId: `urn:uuid:${randomUUID()}`, accounts: [], activeClientId: null };
    await this.write(state);
    return state;
  }
  async write(state) {
    if (!this.key) throw new Error('서버 저장소가 준비되지 않았습니다.');
    const temporary = join(this.directory, `.credentials-${randomUUID()}.tmp`);
    try {
      const handle = await open(temporary, 'wx', 0o600);
      try { await handle.writeFile(JSON.stringify(encryptState(state, this.key))); await handle.sync(); }
      finally { await handle.close(); }
      await rename(temporary, this.filename);
      const directoryHandle = await open(this.directory, constants.O_RDONLY);
      try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
    } finally { await rm(temporary, { force: true }); }
  }
}

// 서버용 페어링은 실제 열린 파일의 UID·0600·링크 여부를 검사하고 값은 반환 외에는 출력하지 않습니다.
export async function loadVmPairingToken(directory) {
  if (!['linux', 'darwin'].includes(process.platform) || !isAbsolute(directory)) throw new Error('개인 페어링 저장소를 확인해주세요.');
  const info = await lstat(directory);
  if (!info.isDirectory() || info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700) throw new Error('개인 페어링 저장 폴더를 확인해주세요.');
  const filename = join(directory, 'pairing-token.txt');
  try {
    const token = (await readPrivateFile(filename, 43, 0o600)).toString('utf8');
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('페어링 형식 오류');
    return token;
  } catch (error) {
    if (error.code !== 'ENOENT') throw new Error('서버 페어링 자료를 확인하지 못했습니다. 기존 자료를 보존합니다.');
    const token = randomBytes(32).toString('base64url');
    const handle = await open(filename, 'wx', 0o600);
    try { await handle.writeFile(token); await handle.sync(); } finally { await handle.close(); }
    const directoryHandle = await open(directory, constants.O_RDONLY);
    try { await directoryHandle.sync(); } finally { await directoryHandle.close(); }
    return token;
  }
}
