/* 7.2.0-dev.1: the web app's vault (frontend/vault.js) and sync engine (frontend/sync-core.js) run in Node against the
   shared vectors and sync cases, plus engine scenarios ported from test_sync_core_72.py.
   Usage: node datasync_harness.js <vault_vectors.json> <sync_cases.json> [frontend dir]
   Prints {"passed": n, "failed": [{name, error}], "info": {...}}; exit code 1 if anything failed. Never prints key material. */
'use strict';
const fs = require('fs');
const path = require('path');
const nodeCrypto = require('crypto');
const { pathToFileURL } = require('url');

const [vectorsPath, casesPath, frontArg] = process.argv.slice(2);
const FRONT = frontArg || path.resolve(__dirname, '..', '..', '..', 'frontend');

let passed = 0;
const failed = [];
const info = {};

function deepEqual(a, b) {
  if (a === b) return true;
  if (a instanceof Uint8Array && b instanceof Uint8Array) return a.length === b.length && a.every((x, i) => x === b[i]);
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) return a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
  const ka = Object.keys(a), kb = Object.keys(b);
  return ka.length === kb.length && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && deepEqual(a[k], b[k]));
}
function assert(cond, msg) { if (!cond) throw new Error(msg || 'assertion failed'); }
async function check(name, fn) {
  try { await fn(); passed++; } catch (e) { failed.push({ name, error: `${e && e.name}: ${e && e.message}` }); }
}
async function throwsAsync(fn, cls, re) {
  try { await fn(); } catch (e) {
    if (cls && !(e instanceof cls)) throw new Error(`expected ${cls.name}, got ${e && e.name}: ${e && e.message}`);
    if (re && !new RegExp(re).test(e.message)) throw new Error(`message ${JSON.stringify(e.message)} does not match ${re}`);
    return e;
  }
  throw new Error(`expected ${cls ? cls.name : 'an error'}, nothing thrown`);
}
const enc = (s) => new TextEncoder().encode(s);
const dec = (b) => new TextDecoder().decode(b);
const fromHex = (h) => Uint8Array.from(Buffer.from(h, 'hex'));
const toHex = (b) => Buffer.from(b).toString('hex');
const keysHex = (m) => Object.fromEntries([...m].map(([k, v]) => [String(k), toHex(v)]));

/** make_vault_vectors.det: deterministic bytes for vectors only. */
function det(seed) {
  let i = 0;
  return (n) => {
    let out = Buffer.alloc(0);
    while (out.length < n) { out = Buffer.concat([out, nodeCrypto.createHash('sha256').update(`${seed}:${i}`).digest()]); i++; }
    return Uint8Array.from(out.subarray(0, n));
  };
}

(async () => {
  // The modules are ES modules served as .js; Node 22.1 needs .mjs to load them that way (temporary copies).
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'abhify-ds-'));
  let vc, sc;
  try {
    fs.copyFileSync(path.join(FRONT, 'vault.js'), path.join(tmp, 'vault.mjs'));
    const core = fs.readFileSync(path.join(FRONT, 'sync-core.js'), 'utf8');
    assert(core.includes("from './vault.js'"), 'sync-core.js must import ./vault.js');
    fs.writeFileSync(path.join(tmp, 'sync-core.mjs'), core.replace("from './vault.js'", "from './vault.mjs'"));
    vc = await import(pathToFileURL(path.join(tmp, 'vault.mjs')).href);
    sc = await import(pathToFileURL(path.join(tmp, 'sync-core.mjs')).href);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  const V = JSON.parse(fs.readFileSync(vectorsPath, 'utf8'));
  const CASES = JSON.parse(fs.readFileSync(casesPath, 'utf8')).cases;

  // ------------------------------------------------------------------- vectors
  for (const c of V.b32c) await check(`b32c ${c.hex}`, () => assert(vc.b32c(fromHex(c.hex)) === c.out));
  for (const c of V.recovery_keys) {
    await check(`recovery key ${c.canonical.slice(0, 4)}`, async () => {
      assert(await vc.newRecoveryKey((n) => fromHex(c.raw).subarray(0, n)) === c.canonical, 'canonical');
      assert(vc.displayRecoveryKey(c.canonical) === c.display, 'display');
      assert(vc.qrPayload(c.canonical) === c.qr, 'qr');
      assert(await vc.normalizeRecoveryKey(c.display) === c.canonical, 'normalize');
      assert(await vc.fromQr(c.qr) === c.canonical, 'from qr');
    });
  }
  for (const [i, c] of V.normalize.entries()) {
    await check(`normalize #${i}`, async () => {
      if ('out' in c) assert(await vc.normalizeRecoveryKey(c.in) === c.out);
      else await throwsAsync(() => vc.normalizeRecoveryKey(c.in), vc.RecoveryKeyTypo);
    });
  }
  await check('qr inside a URL is refused', () => throwsAsync(() => vc.fromQr('https://example.com/abhify-rk:1:' + V.vault.recovery_key), vc.RecoveryKeyTypo));
  for (const [i, c] of V.kdf.entries()) {
    await check(`kdf #${i}`, async () => assert(toHex(await vc.kdf(c.password, fromHex(c.salt), c.iter)) === c.key));
  }
  await check('kdf iteration bounds', async () => {
    await throwsAsync(() => vc.kdf('p', enc('s'.repeat(16)), 99999), vc.Unsupported);
    await throwsAsync(() => vc.kdf('p', enc('s'.repeat(16)), 5000001), vc.Unsupported);
  });
  for (const [i, c] of V.aead.entries()) {
    await check(`aead #${i}`, async () => {
      assert(vc.b64e(await vc.seal(fromHex(c.key), fromHex(c.iv), fromHex(c.pt), c.aad)) === c.ct, 'seal');
      assert(toHex(await vc.open(fromHex(c.key), fromHex(c.iv), vc.b64d(c.ct), c.aad, 'x')) === c.pt, 'open');
      await throwsAsync(() => vc.open(fromHex(c.key), fromHex(c.iv), vc.b64d(c.ct), c.aad + '!', 'x'), vc.WrongKey);
    });
  }
  await check('base64 strictness matches Python', async () => {
    const ok = { '': '', 'YQ==': '61', 'YWJj': '616263', 'YWJj=': '616263', 'YWJj==': '616263', 'YR==': '61', 'YWE=': '6161', 'AA==': '00', 'AAA=': '0000' };
    for (const [s, h] of Object.entries(ok)) assert(toHex(vc.b64d(s)) === h, s);
    for (const s of ['YQ=', 'YQ', 'Y', 'YWJjZA', '=', '==', 'A=', 'AA=', 'YWJj YQ==', 'YQ==\n', 'YWJjZ===', 'éAAA']) {
      await throwsAsync(async () => vc.b64d(s, 'iv'), vc.Malformed, 'iv unreadable');
    }
    await throwsAsync(async () => vc.b64d(null, 'salt'), vc.Malformed, 'salt missing');
  });

  // vault
  const vt = V.vault;
  await check('vault create + rotate reproduce the vectors byte for byte', async () => {
    const r = det('vault');
    const one = await vc.createVault(vt.recovery_key, 1763900000000, r);
    assert(JSON.stringify(one.doc) === JSON.stringify(vt.doc_epoch1), 'doc_epoch1');
    assert(deepEqual(keysHex(one.keys), vt.keys_epoch1), 'keys_epoch1');
    const two = await vc.rotateVault(one.doc, vt.recovery_key, r);
    assert(JSON.stringify(two.doc) === JSON.stringify(vt.doc_epoch2), 'doc_epoch2');
    assert(deepEqual(keysHex(two.keys), vt.keys_epoch2), 'keys_epoch2');
    // the same random stream continues into the op files and the backup (only the header IVs/salt are portable;
    // gzip bytes depend on the zlib build, e.g. the OS byte)
    const recs = V.ops.expect.records;
    const op = JSON.parse(dec(await vc.sealOps(vt.doc_epoch2.vault_id, 2, two.keys.get(2), 'a'.repeat(16), 7, 1763900000010, recs, r)));
    const ref = JSON.parse(V.ops.file);
    assert(op.iv === ref.iv, 'op iv');
    info.ops_byte_exact_with_this_zlib = JSON.stringify(op) === V.ops.file;
  });
  await check('vault unlock epoch 1', async () => assert(deepEqual(keysHex(await vc.unlockVault(vt.doc_epoch1, vt.recovery_key)), vt.keys_epoch1)));
  await check('vault unlock epoch 2 (display form, lower case)', async () => {
    const k2 = await vc.unlockVault(vt.doc_epoch2, vc.displayRecoveryKey(vt.recovery_key).toLowerCase());
    assert(deepEqual(keysHex(k2), vt.keys_epoch2));
  });
  await check('vault wrong Recovery Key', () => throwsAsync(() => vc.unlockVault(vt.doc_epoch2, vt.wrong_recovery_key), vc.WrongKey, "doesn't open this vault"));
  await check('vault tampered header (epoch)', () => throwsAsync(() => vc.unlockVault({ ...vt.doc_epoch1, epoch: 3 }, vt.recovery_key), vc.WrongKey));
  await check('vault tampered format / v', async () => {
    await throwsAsync(() => vc.unlockVault({ ...vt.doc_epoch1, format: 'x' }, vt.recovery_key), vc.Malformed);
    await throwsAsync(() => vc.unlockVault({ ...vt.doc_epoch1, v: 'x' }, vt.recovery_key), vc.Unsupported);
    await throwsAsync(() => vc.unlockVault({ ...vt.doc_epoch1, chain: [{ epoch: 1, iv: vt.doc_epoch1.wrap.iv, ct: vt.doc_epoch1.wrap.ct }] }, vt.recovery_key), vc.WrongKey, 'vault file is damaged');
  });
  await check('vault document holds no plain key', () => {
    const text = JSON.stringify(vt.doc_epoch2);
    for (const h of Object.values(vt.keys_epoch2)) assert(!text.includes(h) && !text.includes(vc.b64e(fromHex(h))));
    assert(!text.includes(vt.recovery_key));
  });

  await check('type confusion in vault.json raises VaultErrors', async () => {
    for (const [k, val] of [['iter', '100000'], ['iter', null], ['iter', true], ['salt', 5]]) {
      const bad = JSON.parse(JSON.stringify(vt.doc_epoch1)); bad.kdf[k] = val;
      await throwsAsync(() => vc.unlockVault(bad, vt.recovery_key), vc.Unsupported);
    }
    for (const [k, val] of [['created', '1'], ['wrap', null], ['chain', null], ['chain', {}]]) {
      const bad = JSON.parse(JSON.stringify(vt.doc_epoch1)); bad[k] = val;
      await throwsAsync(() => vc.unlockVault(bad, vt.recovery_key), vc.Malformed, 'vault file is damaged');
    }
    const noChain = JSON.parse(JSON.stringify(vt.doc_epoch1)); delete noChain.chain;
    assert(deepEqual(keysHex(await vc.unlockVault(noChain, vt.recovery_key)), vt.keys_epoch1), 'missing chain is allowed');
    const kdfStr = JSON.parse(JSON.stringify(vt.doc_epoch1)); kdfStr.kdf = 'x';
    await throwsAsync(() => vc.unlockVault(kdfStr, vt.recovery_key), vc.Unsupported);
  });
  await check('kdf type and salt checks', async () => {
    for (const it of ['100000', true, null, 100000.5]) await throwsAsync(() => vc.kdf('p', new Uint8Array(16), it), vc.Unsupported);
    await throwsAsync(() => vc.kdf('p', new Uint8Array(15), 100000), vc.Malformed, 'The file is damaged .salt..$');
  });
  await check('encryption header lengths checked before decrypting', async () => {
    const key = new Uint8Array(32);
    await throwsAsync(() => vc.open(key, new Uint8Array(8), new Uint8Array(32), 'x', 'w'), vc.Malformed, 'encryption header');
    await throwsAsync(() => vc.open(key, new Uint8Array(16), new Uint8Array(32), 'x', 'w'), vc.Malformed, 'encryption header');
    await throwsAsync(() => vc.open(key, new Uint8Array(12), new Uint8Array(15), 'x', 'w'), vc.Malformed, 'encryption header');
    await throwsAsync(() => vc.open(key, new Uint8Array(12), new Uint8Array(16), 'x', 'w'), vc.WrongKey);
  });
  await check('gzip OS byte is 255 (same bytes on every OS)', async () => {
    const z = await vc.gz(enc('x'));
    assert(z[9] === 255 && dec(await vc.gunz(z)) === 'x');
  });

  // ops
  const o = V.ops;
  const A16 = 'a'.repeat(16);
  const k2 = new Map(Object.entries(vt.keys_epoch2).map(([k, v]) => [Number(k), fromHex(v)]));
  const vid2 = vt.doc_epoch2.vault_id;
  await check('ops open', async () => assert(deepEqual(await vc.openOps(enc(o.file), k2, vid2), o.expect)));
  await check('ops open with plain-object keys', async () => assert(deepEqual(await vc.openOps(enc(o.file), { 1: k2.get(1), 2: k2.get(2) }, vid2), o.expect)));
  await check('ops epoch-1 file through the chain', async () => assert(deepEqual((await vc.openOps(enc(o.file_epoch1), k2, vid2)).records, o.expect_epoch1_records)));
  await check('op names', () => {
    assert(deepEqual(vc.parseOpName(o.name), ['a'.repeat(16), 7]) && vc.opName('a'.repeat(16), 7) === o.name);
    assert(vc.parseOpName('op-' + 'a'.repeat(16) + '-0000000١.abx') === null, 'Arabic-Indic digit');
    assert(vc.parseOpName('op-xyz-00000001.abx') === null && vc.parseOpName('../op-' + 'a'.repeat(16) + '-00000001.abx') === null);
  });
  for (const t of o.tampered) {
    await check(`ops tampered ${t.what}`, () => throwsAsync(() => vc.openOps(enc(t.file), k2, vid2), t.error === 'wrong_key' ? vc.WrongKey : vc.Malformed));
  }
  await check('ops newer epoch than known', () => throwsAsync(() => vc.openOps(enc(o.file), new Map([[1, k2.get(1)]]), vid2), vc.WrongKey, 'newer vault key'));
  await check('ops header damage', async () => {
    await throwsAsync(() => vc.openOps(enc('not json'), k2, vid2), vc.Malformed, 'A sync file is damaged.');
    await throwsAsync(() => vc.openOps(enc(JSON.stringify({ ...JSON.parse(o.file), v: 2 })), k2, vid2), vc.Unsupported);
    await throwsAsync(() => vc.openOps(enc(JSON.stringify({ ...JSON.parse(o.file), seq: '7' })), k2, vid2), vc.Malformed);
    await throwsAsync(() => vc.openOps(Uint8Array.from([0xef, 0xbb, 0xbf, ...enc(o.file)]), k2, vid2), vc.Malformed);
  });
  await check('ops damaged bodies are Malformed', async () => {
    const key = new Uint8Array(32), iv = new Uint8Array(12), V0 = '0'.repeat(32);
    for (const body of ['[1]', 'not json', '"x"', '﻿{}']) {
      const ct = await vc.seal(key, iv, await vc.gz(enc(body)), `abhify-sync-ops|1|${V0}|1|${A16}|1|1`);
      const f = enc(JSON.stringify({ format: 'abhify-sync-ops', v: 1, vault_id: V0, epoch: 1, device: A16, seq: 1, created: 1, iv: vc.b64e(iv), ct: vc.b64e(ct) }));
      await throwsAsync(() => vc.openOps(f, new Map([[1, key]]), V0), vc.Malformed, "content is damaged; it was not imported");
    }
    const short = JSON.parse(o.file); short.iv = vc.b64e(enc('1234'));
    await throwsAsync(() => vc.openOps(enc(JSON.stringify(short)), new Map([[2, new Uint8Array(32)]]), short.vault_id), vc.Malformed, 'encryption header');
  });
  await check('ops round trip (real random)', async () => {
    const recs = o.expect.records;
    const data = await vc.sealOps(vid2, 2, k2.get(2), 'b'.repeat(16), 3, 5, recs);
    assert(!dec(data).includes('Test PC'));
    assert(deepEqual((await vc.openOps(data, k2, vid2)).records, recs));
  });

  // backup
  const b = V.backup;
  async function handBackup(bodyText, pass = 'a long passphrase') {
    const salt = vc.randomBytes(16), iv = vc.randomBytes(12), sb = vc.b64e(salt);
    const ct = await vc.seal(await vc.kdf(pass, salt, 100000), iv, await vc.gz(enc(bodyText)), `abhify-vault-backup|2|web|v|1|100000|${sb}`);
    return enc(JSON.stringify({ format: 'abhify-vault-backup', v: 2, platform: 'web', app_version: 'v', created: 1,
      kdf: { alg: 'PBKDF2-SHA256', iter: 100000, salt: sb }, iv: vc.b64e(iv), ct: vc.b64e(ct) }));
  }
  await check('backup damaged bodies and shapes', async () => {
    for (const t of ['[1]', 'nope', '5']) await throwsAsync(async () => vc.openBackup(await handBackup(t), 'a long passphrase'), vc.Malformed, "content is damaged; it was not imported");
    for (const t of [{ manifest: [1], records: {} }, { manifest: {}, records: [1] }, { manifest: {}, records: { memory: {} } }, { manifest: { counts: [1] }, records: {} }]) {
      await throwsAsync(async () => vc.openBackup(await handBackup(JSON.stringify(t)), 'a long passphrase'), vc.Malformed, 'The backup content is damaged.');
    }
    const empty = await vc.openBackup(await handBackup(JSON.stringify({ manifest: null, records: [] })), 'a long passphrase');
    assert(deepEqual(empty.records, {}) && deepEqual(empty.manifest, {}), 'falsy manifest/records read as empty like Python');
    const h = JSON.parse(b.file);
    for (const kdf of [{ ...h.kdf, salt: 5 }, { ...h.kdf, iter: '100000' }, 'x']) {
      await throwsAsync(() => vc.openBackup(enc(JSON.stringify({ ...h, kdf })), b.passphrase), vc.Unsupported);
    }
    await throwsAsync(() => vc.openBackup(enc(JSON.stringify({ ...h, created: true })), b.passphrase), vc.Unsupported);
  });
  await check('backup open', async () => assert(deepEqual(await vc.openBackup(enc(b.file), b.passphrase), b.expect)));
  await check('backup wrong passphrase', async () => {
    await throwsAsync(() => vc.openBackup(enc(b.file), b.wrong_passphrase), vc.WrongKey, 'Wrong passphrase');
    await throwsAsync(() => vc.openBackup(enc(b.file), ''), vc.WrongKey);
    await throwsAsync(() => vc.openBackup(enc(b.file), null), vc.WrongKey);
  });
  await check('backup header tamper', async () => {
    const h = JSON.parse(b.file);
    await throwsAsync(() => vc.openBackup(enc(JSON.stringify({ ...h, platform: 'android' })), b.passphrase), vc.WrongKey);
    await throwsAsync(() => vc.openBackup(enc('not a backup'), 'x'), vc.Malformed);
    await throwsAsync(() => vc.openBackup(enc(JSON.stringify({ ...h, v: 3 })), 'x'), vc.Unsupported);
    await throwsAsync(() => vc.openBackup(enc(JSON.stringify({ ...h, kdf: { ...h.kdf, iter: 1000 } })), b.passphrase), vc.Unsupported);
  });
  await check('backup round trip, passphrase rules, fresh salt', async () => {
    const recs = { memory: [{ rid: 'mem-abc-12345', kind: 'memory', ver: { dev: '1'.repeat(16), n: 1 }, base: null, at: 1, del: false, data: { content: 'x', created: 1 } }] };
    for (const bad of ['', null, 'short']) await throwsAsync(() => vc.sealBackup(bad, 'web', '7.2.0-dev.1', 1, recs, ['Personal memory'], undefined, 100000), vc.VaultError);
    await throwsAsync(() => vc.sealBackup('a long passphrase', 'ios', '7.2.0-dev.1', 1, recs, [], undefined, 100000), vc.VaultError, 'Invalid backup header');
    assert(vc.backupPassphraseProblem('a long passphrase') === null);
    const data = await vc.sealBackup('a long passphrase', 'web', '7.2.0-dev.1', 5, recs, ['Personal memory'], undefined, 100000);
    assert(!dec(data).includes('mem-abc') && !dec(data).includes('content'));
    const got = await vc.openBackup(data, 'a long passphrase');
    assert(deepEqual(got.records, recs) && deepEqual(got.manifest.counts, { memory: 1 }));
    const a1 = await vc.sealBackup('a long passphrase', 'web', '7.2.0-dev.1', 5, recs, [], undefined, 100000);
    const a2 = await vc.sealBackup('a long passphrase', 'web', '7.2.0-dev.1', 5, recs, [], undefined, 100000);
    assert(dec(a1) !== dec(a2));
  });
  await check('backup counts must match', async () => {
    // a well-formed but inconsistent body, sealed by hand with the real primitives
    const salt = vc.randomBytes(16), iv = vc.randomBytes(12), sb = vc.b64e(salt);
    const body = await vc.gz(enc(JSON.stringify({ manifest: { counts: { memory: 2 } }, records: { memory: [] } })));
    const ct = await vc.seal(await vc.kdf('a long passphrase', salt, 100000), iv, body, `abhify-vault-backup|2|web|v|1|100000|${sb}`);
    const file = { format: 'abhify-vault-backup', v: 2, platform: 'web', app_version: 'v', created: 1, kdf: { alg: 'PBKDF2-SHA256', iter: 100000, salt: sb }, iv: vc.b64e(iv), ct: vc.b64e(ct) };
    await throwsAsync(() => vc.openBackup(enc(JSON.stringify(file)), 'a long passphrase'), vc.Malformed, "counts don't match");
  });
  await check('real Recovery Keys are random and valid', async () => {
    const keys = new Set();
    for (let i = 0; i < 50; i++) keys.add(await vc.newRecoveryKey());
    assert(keys.size === 50);
    for (const k of keys) assert(await vc.normalizeRecoveryKey(vc.displayRecoveryKey(k)) === k);
  });
  await check('decompression cap and damaged gzip', async () => {
    const z = await vc.gz(enc('0'.repeat(5000)));
    assert(dec(await vc.gunz(z)) === '0'.repeat(5000));
    await throwsAsync(() => vc.gunz(z, 1000), vc.Malformed, 'too large');
    const big = await vc.gz(new Uint8Array(4 * 1024 * 1024));
    await throwsAsync(() => vc.gunz(big, 1024 * 1024), vc.Malformed, 'too large');
    await throwsAsync(() => vc.gunz(enc('not gzip at all')), vc.Malformed, 'content unreadable');
    const cut = z.subarray(0, z.length - 6);
    await throwsAsync(() => vc.gunz(cut), vc.Malformed, 'content unreadable');
  });
  await check('dataHash equals Python json.dumps(sort_keys=True, ensure_ascii=False)', async () => {
    const objs = [{ b: 1, a: { z: '✓', y: [1, 'x', true, null, false] }, 'é': 'नमस्ते' },
      { content: 'Likes tea', created: 1 }, { q: 'a"b\\c\n\t\u0001\u007f ', '\u{1F600}': 1, '￿': 2, 10: 3, 9: 4 }, {}];
    const want = ['e4b606fea5831f59b88dfb6f29edcede5ea2d95c33af07470770fabbd7e66978', '31615d2e092ed7f9c8c195c9b7ee0b0418893b7d065ae240ca3f778f1e54df4b',
      '90cd524f103f6cc7215f28bbbc803c4203a8c1104247cdc6cb1456ca464e126c', '44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a'];
    for (let i = 0; i < objs.length; i++) assert(await sc.dataHash(objs[i]) === want[i], `object #${i}`);
  });
  await check('naturalKey folds whitespace and case like Python', () => {
    assert(sc.naturalKey('memory', { content: '  lives in   PUNE ' }) === 'm|lives in pune');
    assert(sc.naturalKey('memory', { content: 'Straße' }) === 'm|strasse');
    assert(sc.naturalKey('message', { conv: 'c', role: 'user', created: 6, content: 'hi' }) === 'g|c|user|6|hi');
    assert(sc.naturalKey('conversation', {}) === null);
  });

  // ------------------------------------------------------------------- engine doubles (MemLocal / MemRemote ports)
  class MemRemote {
    constructor() { this.files = new Map(); this.failPuts = 0; this.loseReply = false; }
    listNames() { return [...this.files.keys()].sort(); }
    get(name) { if (!this.files.has(name)) throw new Error(`KeyError ${name}`); return this.files.get(name); }
    async putNew(name, data) {
      if (this.failPuts) { this.failPuts--; throw new Error('network down'); }
      if (this.files.has(name)) throw new sc.Exists(name);
      this.files.set(name, Uint8Array.from(data));
      if (this.loseReply) { this.loseReply = false; throw new Error('connection reset'); }
    }
  }
  class MemLocal {
    constructor() { this.rows = new Map(); this.meta = {}; this.failAddAfter = null; }
    readItems(kinds) {
      const out = new Map();
      for (const [r, [k, d]] of this.rows) if (kinds.includes(k)) out.set(r, [k, structuredClone(d)]);
      return out;
    }
    add(kind, rid, data) {
      if (this.failAddAfter !== null) { if (this.failAddAfter === 0) throw new Error('disk full'); this.failAddAfter--; }
      assert(!this.rows.has(rid), `add: ${rid} exists`);
      this.rows.set(rid, [kind, structuredClone(data)]);
    }
    update(kind, rid, data) { assert(this.rows.has(rid), `update: ${rid} missing`); this.rows.set(rid, [kind, structuredClone(data)]); }
    remove(kind, rid) { this.rows.delete(rid); }
    getMeta(key, dflt = null) { return structuredClone(Object.prototype.hasOwnProperty.call(this.meta, key) ? this.meta[key] : dflt); }
    putMeta(key, value) { this.meta[key] = structuredClone(value); }
    async transaction(fn) {
      const snap = structuredClone([this.rows, this.meta]);
      try { return await fn(); } catch (e) { [this.rows, this.meta] = snap; throw e; }
    }
  }
  const KEYS = new Map([[1, Uint8Array.from({ length: 32 }, (_, i) => i)]]);
  const VID = '0'.repeat(31) + '1';
  class Dev {
    constructor(remote, h, platform = 'windows', clock = null) {
      this.local = new MemLocal();
      this.local.putMeta('kinds', ['conversation', 'message', 'memory', 'pref']);
      this.t = clock || [1763900000000];
      this.e = new sc.Engine(this.local, remote, h.repeat(16), platform, `dev-${h}`, () => ++this.t[0]);
    }
    sync(kw = {}) { return this.e.apply(KEYS, VID, 1, kw); }
    preview() { return this.e.preview(KEYS, VID); }
    mem(rid, content, created = 1) { this.local.rows.set(rid, ['memory', { content, created }]); }
    rows(kind) { const o = {}; for (const [r, [k, d]] of this.local.rows) if (k === kind) o[r] = d; return o; }
  }
  const world = () => { const remote = new MemRemote(), clock = [1763900000000]; return [remote, new Dev(remote, 'a', 'windows', clock), new Dev(remote, 'b', 'android', clock), new Dev(remote, 'c', 'web', clock)]; };

  // ------------------------------------------------------------------- shared sync cases
  for (const c of CASES) {
    await check(`case: ${c.name}`, async () => {
      const remote = new MemRemote(), clock = [1763900000000];
      const devs = { a: new Dev(remote, 'a', 'windows', clock), b: new Dev(remote, 'b', 'windows', clock), c: new Dev(remote, 'c', 'windows', clock) };
      for (const [i, st] of c.steps.entries()) {
        const d = devs[st.dev], where = `step ${i}: ${JSON.stringify(st)}`;
        const eq = (got, want) => assert(deepEqual(got, want), `${where} -> got ${JSON.stringify(got)}`);
        switch (st.op) {
          case 'set': d.local.rows.set(st.rid, [st.kind, st.data]); break;
          case 'del': d.local.rows.delete(st.rid); break;
          case 'kinds': d.local.putMeta('kinds', st.kinds); break;
          case 'sync': {
            const kw = { direction: st.direction || 'both', approveDeletes: st.approve || [], keepElsewhere: st.keep || [] };
            if ('error' in st) { await throwsAsync(() => d.sync(kw), sc.SyncError, st.error); break; }
            const r = await d.sync(kw);
            for (const [k, v] of Object.entries(st.expect || {})) eq({ [k]: r[k] }, { [k]: v });
            break;
          }
          case 'rows': eq(d.rows(st.kind), st.rows); break;
          case 'contents': eq(Object.values(d.rows(st.kind)).map((x) => x.content).sort(sc.cmpStr), st.values); break;
          case 'conflicts': eq((await d.e.conflicts()).length, st.n); break;
          case 'resolve': await d.e.resolve((await d.e.conflicts())[st.index].id, st.choice); break;
          case 'restore': { const t = await d.e.recentlyDeleted(); await d.e.restoreDeleted(t[t.length - 1].id); break; }
          case 'deleted': eq((await d.e.recentlyDeleted()).map((t) => t.data), st.data); break;
          case 'redownload': await d.e.redownloadAll(); break;
          case 'revoke': await d.e.revoke(st.target.repeat(16)); break;
          case 'pending_deletions': eq((await d.preview()).local_deletions.map((x) => x.rid), st.rids); break;
          default: throw new Error(`unknown op ${st.op}`);
        }
      }
    });
  }

  // ------------------------------------------------------------------- extra engine scenarios (test_sync_core_72.py)
  const A = 'a'.repeat(16);
  await check('engine: upload, preview changes nothing, encrypted, devices learned', async () => {
    const [remote, a, b] = world();
    a.local.rows.set('conv-00000001', ['conversation', { title: 'Trip', created: 5 }]);
    a.local.rows.set('msg-00000001', ['message', { conv: 'conv-00000001', role: 'user', content: 'hi', created: 6 }]);
    a.mem('mem-00000001', 'Likes tea');
    const r = await a.sync();
    assert(r.uploaded === 3 && deepEqual([...remote.files.keys()], [vc.opName(A, 1)]));
    for (const d of remote.files.values()) assert(!dec(d).includes('Likes tea') && !dec(d).includes('Trip'));
    const p = await b.preview();
    assert(deepEqual(p.incoming.add, { conversation: 1, message: 1, memory: 1 }) && b.local.rows.size === 0);
    assert((await b.sync()).added === 3);
    await b.e.redownloadAll();
    const r2 = await b.sync();
    assert(r2.added === 0 && r2.skipped >= 4 && b.local.rows.size === 3);
    assert((await a.sync()).added === 0 && deepEqual(Object.keys(await a.e.devices()), ['b'.repeat(16)]));
  });
  await check('engine: interrupted upload resumes with identical bytes', async () => {
    const [remote, a, b] = world();
    a.mem('mem-00000001', 'x');
    remote.failPuts = 1;
    await throwsAsync(() => a.sync(), Error, 'network down');
    assert(remote.files.size === 0 && a.local.meta.outbox.length === 1);
    const pending = a.local.meta.outbox[0].data;
    a.mem('mem-00000002', 'later');
    await a.sync();
    assert(deepEqual([...remote.files.keys()].sort(), [vc.opName(A, 1), vc.opName(A, 2)]));
    assert(vc.b64e(remote.files.get(vc.opName(A, 1))) === pending);
    remote.loseReply = true;
    a.mem('mem-00000003', 'y');
    await throwsAsync(() => a.sync(), Error, 'connection reset');
    assert(await a.e.flushOutbox() === 1 && remote.files.size === 3);
    assert((await b.sync()).added === 3);
  });
  await check('engine: cloned identity is detected, never overwritten', async () => {
    const [remote, a] = world();
    const clone = new Dev(remote, 'a');
    a.mem('mem-00000001', 'x'); await a.sync();
    clone.mem('mem-00000009', 'other');
    const before = new Map(remote.files);
    await throwsAsync(() => clone.sync(), sc.SyncError, 'sync identity');
    assert(deepEqual([...remote.files], [...before]));
  });
  await check('engine: tampered or missing file stops without skipping', async () => {
    const [remote, a, b] = world();
    a.mem('mem-00000001', 'one'); await a.sync();
    a.mem('mem-00000002', 'two'); await a.sync();
    a.mem('mem-00000003', 'three'); await a.sync();
    const n2 = vc.opName(A, 2), good2 = remote.files.get(n2);
    const bad = Uint8Array.from(good2); bad[bad.length - 10] ^= 1; remote.files.set(n2, bad);
    const r = await b.sync();
    assert(deepEqual(Object.keys(b.rows('memory')), ['mem-00000001']) && r.notes.length > 0, 'stops at damaged file');
    assert(b.local.meta.seen[A] === 1);
    remote.files.delete(n2);
    await b.sync();
    assert(deepEqual(Object.keys(b.rows('memory')), ['mem-00000001']), 'gap never skipped');
    remote.files.set(n2, good2);
    await b.sync();
    assert(deepEqual(Object.keys(b.rows('memory')).sort(), ['mem-00000001', 'mem-00000002', 'mem-00000003']));
  });
  await check('engine: invalid records are refused', async () => {
    const [remote, , b] = world();
    const bad = [{ rid: 'x', kind: 'memory', ver: { dev: A, n: 1 }, base: null, data: { content: 'short rid', created: 1 } },
      { rid: 'mem-00000001', kind: 'secrets', ver: { dev: A, n: 1 }, base: null, data: {} },
      { rid: 'mem-00000002', kind: 'memory', ver: { dev: A, n: 1 }, base: null, data: { content: 5, created: 1 } },
      { rid: 'pref:api_key', kind: 'pref', ver: { dev: A, n: 1 }, base: null, data: { key: 'api_key', value: 'sk-1' } },
      { rid: 'msg-00000001', kind: 'message', ver: { dev: A, n: 1 }, base: null, data: { conv: 'c'.repeat(10), role: 'system', content: 'x', created: 1 } }];
    remote.files.set(vc.opName(A, 1), await vc.sealOps(VID, 1, KEYS.get(1), A, 1, 1, bad));
    const r = await b.sync();
    assert(r.invalid === 5 && b.local.rows.size === 0);
  });
  await check('engine: failed import rolls back', async () => {
    const [, a, b] = world();
    a.mem('mem-00000001', '1'); a.mem('mem-00000002', '2'); await a.sync();
    b.local.failAddAfter = 1;
    await throwsAsync(() => b.sync(), Error, 'disk full');
    assert(b.local.rows.size === 0 && (b.local.meta.seen === undefined || deepEqual(b.local.meta.seen, {})));
    b.local.failAddAfter = null;
    assert((await b.sync()).added === 2);
  });
  await check('engine: revoked device forged later file is ignored', async () => {
    const [remote, a, b, c] = world();
    c.mem('mem-0000000c', 'from c'); await c.sync(); await a.sync(); await b.sync();
    await a.e.revoke('c'.repeat(16)); await a.sync();
    c.mem('mem-000000c2', 'after revoke');
    const n = remote.files.size;
    await throwsAsync(() => c.sync(), sc.SyncError, 'removed');
    assert(remote.files.size === n);
    remote.files.set(vc.opName('c'.repeat(16), 2), await vc.sealOps(VID, 1, KEYS.get(1), 'c'.repeat(16), 2, 9, [
      { rid: 'mem-000000c3', kind: 'memory', ver: { dev: 'c'.repeat(16), n: 1 }, base: null, at: 9, del: false, data: { content: 'evil', created: 1 } }]));
    await b.sync();
    assert('mem-0000000c' in b.rows('memory') && !('mem-000000c3' in b.rows('memory')));
    assert((await b.e.devices())['c'.repeat(16)].revoked === true);
  });
  await check('engine: same memory typed on two devices is not duplicated', async () => {
    const [, a, b] = world();
    a.mem('mem-aaaaaaaa', 'Lives in Pune'); b.mem('mem-bbbbbbbb', '  lives in   PUNE ');
    await a.sync(); const r = await b.sync();
    assert(r.skipped >= 1 && Object.keys(b.rows('memory')).length === 1);
    await a.sync();
    assert(Object.keys(a.rows('memory')).length === 1);
  });
  await check('engine: preview does not change records or state', async () => {
    const [, a, b] = world();
    a.mem('mem-00000001', 'x'); await a.sync();
    b.mem('mem-0000000b', 'mine');
    const rows = structuredClone(b.local.rows), states = structuredClone(b.local.meta.states);
    const p = await b.preview();
    assert(deepEqual(p.upload, { memory: 1 }) && p.incoming.add.memory === 1);
    assert(deepEqual([...b.local.rows], [...rows]) && deepEqual(b.local.meta.states, states) && b.local.meta.seen === undefined);
  });
  await check('engine: categories respected; redownload; no category is an error', async () => {
    const [, a, b] = world();
    a.mem('mem-00000001', 'm'); a.local.rows.set('pref:theme', ['pref', { key: 'theme', value: 'dark' }]); await a.sync();
    b.local.putMeta('kinds', ['pref']); await b.sync();
    assert(deepEqual(b.rows('memory'), {}) && deepEqual(b.rows('pref'), { 'pref:theme': { key: 'theme', value: 'dark' } }));
    b.local.putMeta('kinds', ['pref', 'memory']); await b.sync();
    assert(deepEqual(b.rows('memory'), {}));
    await b.e.redownloadAll(); await b.sync();
    assert('mem-00000001' in b.rows('memory'));
    b.local.putMeta('kinds', []);
    await throwsAsync(() => b.sync(), sc.SyncError, 'at least one category');
  });
  await check('engine: download-only and upload-only', async () => {
    const [remote, a, b] = world();
    a.mem('mem-00000001', 'x'); await a.sync();
    b.mem('mem-0000000b', 'mine');
    const r = await b.sync({ direction: 'download' });
    const mine = () => [...remote.files.keys()].some((n) => n.startsWith('op-' + 'b'.repeat(16)));
    assert(r.added === 1 && !mine());
    await b.sync({ direction: 'upload' });
    assert(mine());
    await throwsAsync(() => b.sync({ direction: 'sideways' }), RangeError);
  });
  await check('engine: message waits for its conversation', async () => {
    const [, a, b] = world();
    a.local.rows.set('conv-00000001', ['conversation', { title: 'T', created: 1 }]); await a.sync();
    a.local.rows.set('msg-00000001', ['message', { conv: 'conv-00000009', role: 'user', content: 'orphan', created: 2 }]); await a.sync();
    const r = await b.sync();
    assert(r.held === 1 && !('msg-00000001' in b.rows('message')));
    a.local.rows.set('conv-00000009', ['conversation', { title: 'late', created: 3 }]); await a.sync();
    await b.sync();
    assert(b.rows('message')['msg-00000001'].conv === 'conv-00000009');
  });

  await check('engine: duplicate file name on Drive does not block the device', async () => {
    const [remote, a, b] = world();
    for (const i of [1, 2, 3]) { a.mem(`mem-0000000${i}`, String(i)); await a.sync(); }
    const orig = remote.listNames.bind(remote);
    remote.listNames = () => [...orig(), vc.opName(A, 1)].sort();
    const r = await b.sync();
    assert(deepEqual(Object.keys(b.rows('memory')).sort(), ['mem-00000001', 'mem-00000002', 'mem-00000003']) && r.notes.length === 0);
  });
  await check('engine: damaged body stops only that device', async () => {
    const [remote, , b, c] = world();
    const iv = new Uint8Array(12);
    const ct = await vc.seal(KEYS.get(1), iv, await vc.gz(enc('[1, 2, 3]')), `abhify-sync-ops|1|${VID}|1|${A}|1|5`);
    remote.files.set(vc.opName(A, 1), enc(JSON.stringify({ format: 'abhify-sync-ops', v: 1, vault_id: VID, epoch: 1, device: A, seq: 1, created: 5, iv: vc.b64e(iv), ct: vc.b64e(ct) })));
    c.mem('mem-0000000c', 'from c'); await c.sync();
    const r = await b.sync();
    assert(r.notes.length > 0 && 'mem-0000000c' in b.rows('memory'));
    assert(!(b.local.meta.seen || {})[A], 'damaged device not marked as read');
  });

  process.stdout.write(JSON.stringify({ passed, failed, info }) + '\n');
  process.exit(failed.length ? 1 : 0);
})().catch((e) => {
  process.stdout.write(JSON.stringify({ passed, failed: [...failed, { name: 'harness', error: String(e && e.stack || e) }], info }) + '\n');
  process.exit(1);
});
