import test from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createCipheriv } from 'node:crypto';
import { mkdtemp, readFile, writeFile, rm, rmdir, chmod, chown, stat, symlink, unlink } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { encryptState, decryptState, VmSecureStore, loadVmPairingToken } from '../vm-storage.mjs';

const synthetic = () => ({ version: 1, hostId: `urn:uuid:${randomUUID()}`, accounts: [
  { clientId: 'SYNTHETIC_CLIENT', credentials: { accessToken: 'SYNTHETIC_ACCESS_ONLY', refreshToken: 'SYNTHETIC_REFRESH_ONLY' } }
], activeClientId: 'SYNTHETIC_CLIENT' });

function maximumState(state = synthetic()) {
  const padded = { ...state, padding: '' };
  padded.padding = 'x'.repeat(2 * 1024 * 1024 - Buffer.byteLength(JSON.stringify(padded)));
  return padded;
}

test('개인 서버 인증 암호문은 원문 없이 왕복하고 매번 새 IV를 쓴다', () => {
  const key = randomBytes(32); const state = synthetic();
  const a = encryptState(state, key); const b = encryptState(state, key);
  assert.equal(JSON.stringify(a).includes('SYNTHETIC_ACCESS_ONLY'), false);
  assert.notEqual(a.iv, b.iv); assert.deepEqual(decryptState(a, key), state);
});
test('암호문·태그·provider 변조 및 다른 키는 거부한다', () => {
  const key = randomBytes(32); const original = encryptState(synthetic(), key);
  for (const field of ['ciphertext', 'tag', 'iv']) {
    const bytes = Buffer.from(original[field], 'base64'); bytes[0] ^= 1;
    assert.throws(() => decryptState({ ...original, [field]: bytes.toString('base64') }, key));
  }
  assert.throws(() => decryptState({ ...original, provider: 'plain' }, key));
  assert.throws(() => decryptState(original, randomBytes(32)));
  assert.throws(() => decryptState({ ...original, iv: original.iv + '!' }, key));
});
test('호스트 ID와 저장 크기 제한을 확인한다', () => {
  assert.throws(() => encryptState({ ...synthetic(), hostId: 'laptop-id' }, randomBytes(32)));
  assert.throws(() => encryptState({ ...synthetic(), padding: 'x'.repeat(3 * 1024 * 1024) }, randomBytes(32)));
  assert.throws(() => new VmSecureStore('relative-path'));
});

test('2 MiB 평문은 3 MiB 이내 envelope로 왕복하며 초과 복호화·base64는 거부한다', () => {
  const key = randomBytes(32); const state = maximumState();
  assert.equal(Buffer.byteLength(JSON.stringify(state)), 2 * 1024 * 1024);
  const envelope = encryptState(state, key);
  const bytes = Buffer.byteLength(JSON.stringify(envelope));
  assert.ok(bytes > 2 * 1024 * 1024 && bytes < 3 * 1024 * 1024);
  const restored = decryptState(envelope, key);
  assert.equal(restored.hostId, state.hostId); assert.equal(restored.padding.length, state.padding.length);
  assert.throws(() => decryptState({ ...envelope, ciphertext: 'A'.repeat(3 * 1024 * 1024 + 4) }, key));
  // 암호학적으로 올바른 envelope도 복호화 평문 한도를 넘으면 사용하지 않습니다.
  const iv = randomBytes(12); const cipher = createCipheriv('aes-256-gcm', key, iv);
  cipher.setAAD(Buffer.from('APL personal host credential envelope v1'));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify({ ...state, padding: state.padding + 'x' })), cipher.final()]);
  assert.throws(() => decryptState({ ...envelope, iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'), ciphertext: ciphertext.toString('base64') }, key));
});

test('Linux 개인 저장소는 권한·host ID 유지·손상 보존·누락키 거부·링크 거부를 검증한다', { skip: process.platform !== 'linux' }, async () => {
  const root = dirname(fileURLToPath(import.meta.url));
  const directory = await mkdtemp(join(root, '.vm-storage-fixture-'));
  const keyPath = join(directory, 'credential-key.bin'); const statePath = join(directory, 'credentials.encrypted.json');
  try {
    const store = new VmSecureStore(directory); const state = await store.initialize();
    const savedHost = state.hostId; state.accounts = synthetic().accounts; await store.write(state);
    assert.equal((await new VmSecureStore(directory).initialize()).hostId, savedHost);
    const largest = maximumState(state); await store.write(largest);
    assert.ok((await stat(statePath)).size > 2 * 1024 * 1024);
    const restoredLargest = await new VmSecureStore(directory).initialize();
    assert.equal(restoredLargest.hostId, savedHost); assert.equal(restoredLargest.padding.length, largest.padding.length);
    await store.write(state);
    for (const path of [keyPath, statePath]) assert.equal((await stat(path)).mode & 0o077, 0);
    assert.equal((await stat(directory)).mode & 0o777, 0o700);
    const original = await readFile(statePath);
    assert.equal(original.includes('SYNTHETIC_ACCESS_ONLY'), false);
    await writeFile(statePath, '{broken', { mode: 0o600 });
    await assert.rejects(new VmSecureStore(directory).initialize());
    assert.equal(await readFile(statePath, 'utf8'), '{broken');
    await writeFile(statePath, original);
    await chmod(keyPath, 0o644); await assert.rejects(new VmSecureStore(directory).initialize()); await chmod(keyPath, 0o600);
    const key = await readFile(keyPath); await unlink(keyPath);
    await assert.rejects(new VmSecureStore(directory).initialize());
    await assert.rejects(readFile(keyPath), { code: 'ENOENT' });
    await writeFile(keyPath, key, { flag: 'wx', mode: 0o600 });
    await unlink(statePath); await symlink(keyPath, statePath);
    await assert.rejects(new VmSecureStore(directory).initialize());
  } finally {
    // 이 테스트에서 만든 고정된 두 파일만 제거하며 재귀 삭제를 하지 않는다.
    await rm(statePath, { force: true }); await rm(keyPath, { force: true }); await rmdir(directory);
  }
});

test('Linux 페어링 파일은 UID·정확한 0600·43자·O_NOFOLLOW를 검사하고 손상을 보존한다', { skip: process.platform !== 'linux' }, async () => {
  const directory = await mkdtemp(join(dirname(fileURLToPath(import.meta.url)), '.vm-pairing-fixture-'));
  const path = join(directory, 'pairing-token.txt'); const reference = join(directory, 'pairing-reference.txt');
  try {
    const token = await loadVmPairingToken(directory);
    assert.ok(/^[A-Za-z0-9_-]{43}$/.test(token));
    assert.equal((await stat(path)).mode & 0o777, 0o600);
    assert.equal((await stat(path)).uid, process.getuid());
    assert.equal(await loadVmPairingToken(directory), token);
    for (const mode of [0o400, 0o640, 0o644]) {
      await chmod(path, mode); await assert.rejects(loadVmPairingToken(directory));
      assert.equal(await readFile(path, 'utf8'), token);
    }
    await chmod(path, 0o600);
    if (process.getuid() === 0) {
      await chown(path, 65534, 65534);
      await assert.rejects(loadVmPairingToken(directory));
      await chown(path, process.getuid(), process.getgid());
    }
    for (const malformed of ['x'.repeat(42), 'x'.repeat(44), 'x'.repeat(42) + '!', 'x'.repeat(43) + '\n']) {
      await writeFile(path, malformed); await assert.rejects(loadVmPairingToken(directory));
      assert.equal(await readFile(path, 'utf8'), malformed);
    }
    await writeFile(reference, 'S'.repeat(43), { mode: 0o600 });
    await unlink(path); await symlink(reference, path);
    await assert.rejects(loadVmPairingToken(directory));
    assert.equal(await readFile(reference, 'utf8'), 'S'.repeat(43));
  } finally {
    // 합성 fixture에서 만든 두 파일만 제거합니다.
    await rm(path, { force: true }); await rm(reference, { force: true }); await rmdir(directory);
  }
});
