// Test-only deterministic encryption using Node's independent HKDF/AES implementation.
// Receiver keys are the public RFC 8291 example, not application credentials.
import { createECDH, createPrivateKey, createCipheriv, hkdfSync } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const ua = createECDH('prime256v1');
ua.setPrivateKey(Buffer.from('q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94', 'base64url'));
const sender = createECDH('prime256v1');
sender.setPrivateKey(Buffer.from('yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw', 'base64url'));
const pub = ua.getPublicKey(), senderPub = sender.getPublicKey();
const auth = Buffer.from('BTBZMqHH6r4Tts7J_aSIgg', 'base64url');
const salt = Buffer.alloc(16, 9);
const label = value => Buffer.from(`${value}\0`, 'ascii');
const payload = JSON.stringify({ notification_id: '123456789012345678901234567890', notification_type: 'future_type',
  title: '新しい通知', body: '<b>そのままのテキスト</b>', access_token: 'ignored-test-token', future: true })
  .replace('"123456789012345678901234567890"', '123456789012345678901234567890');
function encrypt(encoding, text, badPadding = false) {
  const standard = encoding === 'aes128gcm';
  const keyInfo = standard ? Buffer.concat([label('WebPush: info'), pub, senderPub]) : label('Content-Encoding: auth');
  const ikm = hkdfSync('sha256', sender.computeSecret(pub), auth, keyInfo, 32);
  const context = standard ? Buffer.alloc(0) : Buffer.concat([label('P-256'), Buffer.from([0, 65]), pub, Buffer.from([0, 65]), senderPub]);
  const cek = hkdfSync('sha256', ikm, salt, Buffer.concat([label(`Content-Encoding: ${encoding}`), context]), 16);
  const nonce = hkdfSync('sha256', ikm, salt, Buffer.concat([label('Content-Encoding: nonce'), context]), 12);
  const plaintext = standard ? Buffer.concat([Buffer.from(text), Buffer.from([badPadding ? 1 : 2, 0, 0])]) :
    Buffer.concat([Buffer.from(badPadding ? [0, 1, 1] : [0, 1, 0]), Buffer.from(text)]);
  const cipher = createCipheriv('aes-128-gcm', cek, nonce);
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
  const rs = Buffer.alloc(4); rs.writeUInt32BE(Math.max(4096, encrypted.length + 1));
  const body = standard ? Buffer.concat([salt, rs, Buffer.from([65]), senderPub, encrypted]) : encrypted;
  return { version: '1', registrationId: 'r'.repeat(43), messageId: 'm'.repeat(43), encoding,
    headers: JSON.stringify(standard ? {} : { encryption: `salt="${salt.toString('base64url')}";rs=${Math.max(4096, encrypted.length + 1)}`,
      'crypto-key': `dh="${senderPub.toString('base64url')}";p256ecdsa=ignored` }), body: body.toString('base64url') };
}
const privateKey = createPrivateKey({ key: { kty: 'EC', crv: 'P-256', x: pub.subarray(1,33).toString('base64url'),
  y: pub.subarray(33).toString('base64url'), d: ua.getPrivateKey().toString('base64url') }, format: 'jwk' });
const fixture = { keys: { publicKey: pub.toString('base64url'), privateKey: privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64url'), authSecret: auth.toString('base64url') },
  standard: encrypt('aes128gcm', payload), legacy: encrypt('aesgcm', payload),
  large: encrypt('aes128gcm', JSON.stringify({ notification_id: 'large-id', title: 'Large', body: 'x'.repeat(6000) })),
  invalidStandardPadding: encrypt('aes128gcm', payload, true), invalidLegacyPadding: encrypt('aesgcm', payload, true) };
const directory = new URL('../../app/src/test/resources/push/', import.meta.url);
mkdirSync(directory, { recursive: true });
writeFileSync(new URL('encrypted.json', directory), `${JSON.stringify(fixture, null, 2)}\n`);
const deviceDirectory = new URL('../../app/src/androidTest/assets/push/', import.meta.url);
mkdirSync(deviceDirectory, { recursive: true });
writeFileSync(new URL('encrypted.json', deviceDirectory), `${JSON.stringify({ keys: fixture.keys, standard: fixture.standard }, null, 2)}\n`);
console.log(`Wrote deterministic test fixtures to ${fileURLToPath(directory)}`);
