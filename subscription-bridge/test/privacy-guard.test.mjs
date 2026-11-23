import test from 'node:test';
import assert from 'node:assert/strict';
import { assertNoRawPii, findRawPii, inputTexts } from '../privacy-guard.mjs';

const request = content => ({ input: [{ role: 'user', content }] });

test('masked tokens and ordinary screen text pass through', () => {
  for (const text of ['{"goal":"[이름1]에게 문자 보내기"}', '알람 7:30 설정', '2026-11-23 오후 3시', '[0,0,1080,2340]', '1,234,000원'])
    assert.equal(findRawPii([text]), null, text);
});

test('raw phone, email, resident number and card number are blocked', () => {
  assert.equal(findRawPii(['010-1234-5678로 전화']), 'phone');
  assert.equal(findRawPii(['mail me: a.b@example.com']), 'email');
  assert.equal(findRawPii(['900101-1234567']), 'resident_id');
  assert.equal(findRawPii(['4111 1111 1111 1111']), 'card');
  assert.throws(() => assertNoRawPii(request('{"goal":"01012345678에게 문자"}')), e => e.code === 'privacy_blocked' && e.status === 422);
});

test('only user input parts are inspected, not the image payload', () => {
  const parts = [{ type: 'input_text', text: '{"goal":"사진 보내기"}' }, { type: 'input_image', image_url: 'data:image/jpeg;base64,MDEwMTIzNDU2Nzg=' }];
  assert.deepEqual(inputTexts(request(parts)), ['{"goal":"사진 보내기"}']);
  assert.doesNotThrow(() => assertNoRawPii(request(parts)));
});
