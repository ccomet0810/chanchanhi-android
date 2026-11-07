import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, rmdir } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { SecureStore, loadPairingToken } from '../storage.mjs';

// 실제 인증 정보 없이 이 테스트 폴더 안의 임시 자료에 대해서만 Windows 보안 저장을 확인합니다.
test('Windows DPAPI 왕복·개인 ACL·원문 페어링 형식을 합성 자료로 확인한다', { skip: process.platform !== 'win32' }, async () => {
  const root = dirname(fileURLToPath(import.meta.url));
  const directory = await mkdtemp(join(root, '.storage-fixture-'));
  const relativePath = relative(root, resolve(directory));
  assert.equal(relativePath.startsWith('..') || relativePath.includes(':'), false);
  try {
    const store = new SecureStore(directory);
    const state = await store.initialize();
    assert.equal(state.accounts.length, 0);
    state.accounts.push({ clientId: 'oaiapp_synthetic', credentials: { accessToken: 'SYNTHETIC-ONLY-NOT-A-REAL-TOKEN' } });
    await store.write(state);
    const envelope = await readFile(join(directory, 'credentials.dpapi.json'), 'utf8');
    assert.equal(envelope.includes('SYNTHETIC-ONLY-NOT-A-REAL-TOKEN'), false);
    assert.equal(JSON.parse(envelope).provider, 'windows-dpapi-current-user');
    const recovered = await store.initialize();
    assert.equal(recovered.accounts[0].credentials.accessToken, 'SYNTHETIC-ONLY-NOT-A-REAL-TOKEN');
    const token = await loadPairingToken(directory);
    assert.equal(token.length, 43);
    assert.equal(await loadPairingToken(directory), token);
    assert.equal((await readFile(join(directory, 'pairing-token.txt'), 'utf8')).length, 43);
    const aclResult = await new Promise((resolveResult, reject) => {
      const script = "$ErrorActionPreference='Stop'; $taskPath=[Console]::In.ReadToEnd(); $taskAcl=[System.IO.Directory]::GetAccessControl($taskPath); $taskSid=[System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $taskRules=$taskAcl.GetAccessRules($true,$true,[System.Security.Principal.SecurityIdentifier]); if(-not $taskAcl.AreAccessRulesProtected -or $taskRules.Count -ne 1 -or $taskRules[0].IdentityReference.Value -ne $taskSid){exit 1}; [Console]::Out.Write('ACL_PASS')";
      const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      let result = ''; child.stdout.setEncoding('utf8'); child.stdout.on('data', value => { result += value; }); child.stderr.resume();
      child.once('error', reject); child.once('close', code => code === 0 ? resolveResult(result) : reject(new Error('Private ACL verification failed')));
      child.stdin.end(directory, 'utf8');
    });
    assert.equal(aclResult, 'ACL_PASS');
    const original = await readFile(join(directory, 'credentials.dpapi.json'), 'utf8');
    await writeFile(join(directory, 'credentials.dpapi.json'), JSON.stringify({ version: 1, provider: 'unexpected-provider', ciphertext: 'synthetic' }));
    await assert.rejects(store.initialize());
    assert.equal(JSON.parse(await readFile(join(directory, 'credentials.dpapi.json'), 'utf8')).provider, 'unexpected-provider');
    await writeFile(join(directory, 'credentials.dpapi.json'), original);
  } finally {
    // 삭제할 파일 이름을 고정하고 부모 범위를 확인했으며 재귀 삭제를 사용하지 않습니다.
    await rm(join(directory, 'credentials.dpapi.json'), { force: true });
    await rm(join(directory, 'pairing-token.txt'), { force: true });
    await rmdir(directory);
  }
});
