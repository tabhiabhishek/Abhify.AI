/* =====================================================================
   vault.js: Abhify.AI vault, sync-op and backup formats, version 1 (7.2.0-dev.1).
   Normative spec: docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md. Port of backend/vault_crypto.py (the reference).

   * Only platform primitives: WebCrypto AES-256-GCM (128-bit tag appended) and PBKDF2-HMAC-SHA256,
     crypto.getRandomValues, CompressionStream / DecompressionStream('gzip'). No library, no network.
   * Works in browsers and in Node 22 (globalThis.crypto.subtle).
   * Keys, Recovery Keys and passphrases are never logged, returned in errors or stored by this module.
   * Everything is deterministic given the random source `rnd(n) => Uint8Array`, so the shared vectors
     (backend/tests/data/vault_vectors.json) can be reproduced byte for byte.
   * Functions that need WebCrypto (hashing, KDF, AEAD, gzip) are async.
   ===================================================================== */

export const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';   // Crockford base32
export const ITER_PASSPHRASE = 600000;
export const ITER_RECOVERY = 100000;
export const MIN_ITER = 100000;
export const MAX_ITER = 5000000;
export const MAX_PLAIN = 256 * 1024 * 1024;
export const MIN_BACKUP_PASSPHRASE = 10;
export const VAULT_FORMAT = 'abhify-vault';
export const OPS_FORMAT = 'abhify-sync-ops';
export const BACKUP_FORMAT = 'abhify-vault-backup';

const subtle = () => globalThis.crypto.subtle;
const ENC = new TextEncoder();
const DEC = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });

/** Base error. Messages are user-facing and never contain key material. */
export class VaultError extends Error { constructor(m) { super(m); this.name = new.target.name; } }
export class WrongKey extends VaultError {}
export class Malformed extends VaultError {}
export class Unsupported extends VaultError {}
export class RecoveryKeyTypo extends VaultError {}

/** Default random source: the platform CSPRNG. */
export function randomBytes(n) {
  return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

// --------------------------------------------------------------------------- small Python-compatible helpers
/** Python str.isspace() characters (used by str.split(), str.strip() and re's \s). */
export const PY_WS = '\\t\\n\\x0b\\x0c\\r\\x1c-\\x1f \\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000';
const WS_STRIP = new RegExp(`^[${PY_WS}]+|[${PY_WS}]+$`, 'g');
export const pyStrip = (s) => s.replace(WS_STRIP, '');

export const isObj = (o) => o !== null && typeof o === 'object' && !Array.isArray(o) && !(o instanceof Uint8Array);
export const has = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k);
/** Python dict.get(k, default). */
export const pget = (o, k, d = null) => (has(o, k) ? o[k] : d);
/** Python truthiness. */
export function truthy(v) {
  if (v === null || v === undefined || v === false || v === 0 || v === '') return false;
  if (Array.isArray(v)) return v.length > 0;
  if (v instanceof Map || v instanceof Set) return v.size > 0;
  if (v instanceof Uint8Array) return v.length > 0;
  if (typeof v === 'object') return Object.keys(v).length > 0;
  return true;
}
/** Python isinstance(v, int) without bool (JSON numbers: integral values). */
export const isInt = (v) => typeof v === 'number' && Number.isInteger(v);
/** Python str(v) for the scalar values that reach regex checks and AAD strings. */
export function pyStr(v) {
  if (v === null || v === undefined) return 'None';
  if (v === true) return 'True';
  if (v === false) return 'False';
  if (typeof v === 'string') return v;
  if (typeof v === 'number') return String(v);
  return JSON.stringify(v);
}
/** Python int(v): numbers truncate, integer strings parse, bool -> 0/1, anything else is a TypeError. */
function pyInt(v) {
  if (typeof v === 'boolean') return v ? 1 : 0;
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string') {
    const s = pyStrip(v);
    if (/^[+-]?\d(?:_?\d)*$/.test(s)) return Number(s.replace(/_/g, ''));
  }
  throw new TypeError('invalid integer');
}

export function hex(b) {
  let s = '';
  for (const x of b) s += x.toString(16).padStart(2, '0');
  return s;
}
export function fromHex(h) {
  const out = new Uint8Array(h.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(h.substr(i * 2, 2), 16);
  return out;
}
export function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
const utf8 = (s) => ENC.encode(s);

/** Python json.loads(bytes.decode('utf-8')): strict UTF-8, a BOM is refused like Python does. */
export function parseJsonBytes(data) {
  return JSON.parse(DEC.decode(data));
}
/** Compact JSON (Python json.dumps(ensure_ascii=False, separators=(",", ":"))) as UTF-8 bytes; key order as built. */
export function jsonBytes(o) {
  return utf8(JSON.stringify(o));
}

// --------------------------------------------------------------------------- encoding helpers
export function b64e(b) {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Strict Base64 like Python base64.b64decode(validate=True); Malformed with the same messages. */
export function b64d(s, what = 'value') {
  if (typeof s !== 'string') throw new Malformed(`The file is damaged (${what} missing).`);
  const m = /^([A-Za-z0-9+/]*)(={0,2})$/.exec(s);
  let ok = !!m;
  if (ok) {
    const d = m[1].length % 4, pads = m[2].length;
    if (d === 1) ok = false;
    else if (d === 2) ok = pads === 2;
    else if (d === 3) ok = pads >= 1;
    else ok = pads === 0 || m[1].length > 0;
  }
  if (!ok) throw new Malformed(`The file is damaged (${what} unreadable).`);
  const body = m[1];
  const padded = body + '='.repeat((4 - (body.length % 4)) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Crockford base32, MSB first, final bits zero-padded; optionally truncated to `chars` characters. */
export function b32c(data, chars = null) {
  let out = '', acc = 0, nacc = 0;
  for (const byte of data) {
    acc = (acc << 8) | byte; nacc += 8;
    while (nacc >= 5) { nacc -= 5; out += ALPHABET[(acc >> nacc) & 31]; }
    acc &= (1 << nacc) - 1;
  }
  if (nacc > 0) out += ALPHABET[(acc << (5 - nacc)) & 31];
  return chars ? out.slice(0, chars) : out;
}

function b32cDecode(s, nbytes) {
  let v = 0n;
  for (const ch of s) v = (v << 5n) | BigInt(ALPHABET.indexOf(ch));
  v >>= BigInt(s.length * 5 - nbytes * 8);
  const out = new Uint8Array(nbytes);
  for (let i = nbytes - 1; i >= 0; i--) { out[i] = Number(v & 255n); v >>= 8n; }
  return out;
}

export async function sha256(bytes) {
  return new Uint8Array(await subtle().digest('SHA-256', bytes));
}

// --------------------------------------------------------------------------- primitives
export async function kdf(password, salt, iterations) {
  if (!isInt(iterations) || !(MIN_ITER <= iterations && iterations <= MAX_ITER)) throw new Unsupported("The file uses key settings this app doesn't accept.");
  if (salt.length < 16) throw new Malformed('The file is damaged (salt).');
  const it = iterations;
  const base = await subtle().importKey('raw', utf8(password), 'PBKDF2', false, ['deriveBits']);
  return new Uint8Array(await subtle().deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: it }, base, 256));
}

function checkNonce(iv) {
  // same limits as Python cryptography's AESGCM (ValueError there; writers always use 12 bytes)
  if (iv.length < 8 || iv.length > 128) throw new TypeError('Nonce must be between 8 and 128 bytes');
}

async function aesKey(key) {
  return subtle().importKey('raw', key, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function seal(key, iv, plain, aad) {
  checkNonce(iv);
  const k = await aesKey(key);
  return new Uint8Array(await subtle().encrypt({ name: 'AES-GCM', iv, additionalData: utf8(aad), tagLength: 128 }, k, plain));
}

/** AES-GCM open; a failed tag check is WrongKey(wrong). Named open_ in Python. */
export async function open(key, iv, ct, aad, wrong) {
  if (iv.length !== 12 || ct.length < 16) throw new Malformed('The file is damaged (encryption header).');
  const k = await aesKey(key);
  try {
    return new Uint8Array(await subtle().decrypt({ name: 'AES-GCM', iv, additionalData: utf8(aad), tagLength: 128 }, k, ct));
  } catch {
    throw new WrongKey(wrong);
  }
}

async function readAll(stream, cap) {
  const reader = stream.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (cap !== null && total > cap) {
      reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) { out.set(c, off); off += c.length; }
  return out;
}

export async function gz(data) {
  const out = await readAll(new Blob([data]).stream().pipeThrough(new CompressionStream('gzip')), null);
  out[9] = 255;                                           // OS byte "unknown": the same header on every operating system
  return out;
}

const DAMAGED_BODY = "The file's content is damaged; it was not imported.";
/** Decrypted op/backup body: must be a JSON object. */
function body_(plain) {
  let o;
  try {
    o = parseJsonBytes(plain);
  } catch {
    throw new Malformed(DAMAGED_BODY);
  }
  if (!isObj(o)) throw new Malformed(DAMAGED_BODY);
  return o;
}

/** gunzip with the decompressed size capped while streaming (Malformed "too large" past the cap). */
export async function gunz(data, maxPlain = MAX_PLAIN) {
  let out;
  try {
    out = await readAll(new Blob([data]).stream().pipeThrough(new DecompressionStream('gzip')), maxPlain);
  } catch {
    throw new Malformed('The file is damaged (content unreadable).');
  }
  if (out === null) throw new Malformed('The file is too large to open.');
  return out;
}

// --------------------------------------------------------------------------- Recovery Key
export async function newRecoveryKey(rnd = randomBytes) {
  const raw = rnd(20);
  return b32c(raw) + b32c((await sha256(raw)).subarray(0, 3), 4);
}

const RK_STRIP = new RegExp(`[${PY_WS}\\-_]`, 'g');

/** Canonical 36-character form, or RecoveryKeyTypo (checked locally, before any network call). */
export async function normalizeRecoveryKey(text) {
  const s = String(text || '').toUpperCase().replace(RK_STRIP, '').replace(/O/g, '0').replace(/I/g, '1').replace(/L/g, '1');
  if (s.length !== 36 || [...s].some((c) => !ALPHABET.includes(c))) {
    throw new RecoveryKeyTypo("That doesn't look like a Recovery Key (36 letters and digits). Check it and try again.");
  }
  const raw = b32cDecode(s.slice(0, 32), 20);
  if (b32c((await sha256(raw)).subarray(0, 3), 4) !== s.slice(32)) {
    throw new RecoveryKeyTypo("That Recovery Key has a typo (its check doesn't match). Check each group and try again.");
  }
  return s;
}

export function displayRecoveryKey(canonical) {
  const g = [];
  for (let i = 0; i < 36; i += 4) g.push(canonical.slice(i, i + 4));
  return g.join('-');
}

export const qrPayload = (canonical) => `abhify-rk:1:${canonical}`;

export async function fromQr(payload) {
  const m = /^abhify-rk:1:([0-9A-Z]{36})$/.exec(pyStrip(String(payload || '')));
  if (!m) throw new RecoveryKeyTypo("That QR code isn't an Abhify.AI Recovery Key.");
  return normalizeRecoveryKey(m[1]);
}

// --------------------------------------------------------------------------- vault.json
const wrapAad = (vaultId, epoch, iterations, saltB64) => `abhify-vault|1|${vaultId}|${epoch}|${iterations}|${saltB64}`;
const chainAad = (vaultId, oldEpoch, epoch) => `abhify-vault-chain|1|${vaultId}|${oldEpoch}|${epoch}`;

/** A new vault: {doc: vault.json document, keys: Map(epoch -> vault key)}. The keys stay on this device only. */
export async function createVault(recoveryKey, nowMs, rnd = randomBytes) {
  const rk = await normalizeRecoveryKey(recoveryKey);
  const vaultId = hex(rnd(16));
  const vk = rnd(32);
  const doc = await wrap(vaultId, nowMs, 1, vk, rk, [], rnd);
  return { doc, keys: new Map([[1, vk]]) };
}

async function wrap(vaultId, created, epoch, vk, rk, old, rnd) {
  const salt = rnd(16);
  const saltB64 = b64e(salt);
  const kek = await kdf(rk, salt, ITER_RECOVERY);
  const iv = rnd(12);
  const chain = [];
  for (const [oldEpoch, oldKey] of [...old].sort((a, b) => a[0] - b[0])) {
    const civ = rnd(12);
    chain.push({ epoch: oldEpoch, iv: b64e(civ), ct: b64e(await seal(vk, civ, oldKey, chainAad(vaultId, oldEpoch, epoch))) });
  }
  const ct = await seal(kek, iv, vk, wrapAad(vaultId, epoch, ITER_RECOVERY, saltB64));
  return {
    format: VAULT_FORMAT, v: 1, vault_id: vaultId, created: pyInt(created), epoch,
    kdf: { alg: 'PBKDF2-SHA256', iter: ITER_RECOVERY, salt: saltB64 },
    wrap: { iv: b64e(iv), ct: b64e(ct) },
    chain,
  };
}

export function checkVaultDoc(doc) {
  if (!isObj(doc) || pget(doc, 'format') !== VAULT_FORMAT) throw new Malformed("This isn't an Abhify.AI vault file.");
  if (pget(doc, 'v') !== 1) throw new Unsupported('This vault was made by a newer Abhify.AI. Update the app first.');
  if (!/^[0-9a-f]{32}$/.test(pyStr(pget(doc, 'vault_id', ''))) || !isInt(pget(doc, 'epoch')) || doc.epoch < 1) {
    throw new Malformed('The vault file is damaged.');
  }
  const k = truthy(pget(doc, 'kdf')) ? doc.kdf : {};
  if (!isObj(k) || pget(k, 'alg') !== 'PBKDF2-SHA256' || !isInt(pget(k, 'iter')) || typeof pget(k, 'salt') !== 'string') {
    throw new Unsupported("The vault uses key settings this app doesn't accept.");
  }
  if (!isInt(pget(doc, 'created')) || !isObj(pget(doc, 'wrap')) || !Array.isArray(pget(doc, 'chain', []))) {
    throw new Malformed('The vault file is damaged.');
  }
  return doc;
}

/** All epoch keys Map(epoch -> key) from vault.json and the Recovery Key. WrongKey if it doesn't open. */
export async function unlockVault(doc, recoveryKey) {
  checkVaultDoc(doc);
  const rk = await normalizeRecoveryKey(recoveryKey);
  const k = doc.kdf;
  const kek = await kdf(rk, b64d(pget(k, 'salt'), 'salt'), pget(k, 'iter', 0));
  const w = truthy(pget(doc, 'wrap')) ? doc.wrap : {};
  const iv = b64d(pget(w, 'iv'), 'iv');
  const ct = b64d(pget(w, 'ct'), 'key');
  const vk = await open(kek, iv, ct, wrapAad(doc.vault_id, doc.epoch, pyStr(k.iter), pyStr(k.salt)), "That Recovery Key doesn't open this vault.");
  const keys = new Map([[doc.epoch, vk]]);
  for (const c of (truthy(pget(doc, 'chain')) ? doc.chain : [])) {
    const civ = b64d(pget(c, 'iv'), 'iv');
    const cct = b64d(pget(c, 'ct'), 'key');
    const ep = pyInt(c.epoch);
    keys.set(ep, await open(vk, civ, cct, chainAad(doc.vault_id, ep, doc.epoch), 'The vault file is damaged.'));
  }
  return keys;
}

/** New epoch (after revoking a device): {doc, keys}; older keys chained under the new key. */
export async function rotateVault(doc, recoveryKey, rnd = randomBytes) {
  const keys = await unlockVault(doc, recoveryKey);
  const epoch = doc.epoch + 1;
  const vk = rnd(32);
  const next = await wrap(doc.vault_id, doc.created, epoch, vk, await normalizeRecoveryKey(recoveryKey), [...keys.entries()], rnd);
  keys.set(epoch, vk);
  return { doc: next, keys };
}

// --------------------------------------------------------------------------- op files
export const opName = (device, seq) => `op-${device}-${String(seq).padStart(8, '0')}.abx`;

export function parseOpName(name) {
  const m = /^op-([0-9a-f]{16})-([0-9]{8})\.abx$/.exec(typeof name === 'string' ? name : '');
  return m ? [m[1], parseInt(m[2], 10)] : null;
}

const opsAad = (vaultId, epoch, device, seq, created) => `abhify-sync-ops|1|${vaultId}|${epoch}|${device}|${seq}|${created}`;

/** keys: Map(epoch -> key) or a plain object {epoch: key}. */
function keyFor(keys, epoch) {
  if (keys instanceof Map) return keys.has(epoch) ? keys.get(epoch) : null;
  return keys && Object.prototype.hasOwnProperty.call(keys, String(epoch)) ? keys[epoch] : null;
}

export async function sealOps(vaultId, epoch, key, device, seq, created, records, rnd = randomBytes) {
  const iv = rnd(12);
  const body = await gz(jsonBytes({ device, seq, records }));
  const c = pyInt(created);
  const ct = await seal(key, iv, body, opsAad(vaultId, epoch, device, seq, c));
  return jsonBytes({ format: OPS_FORMAT, v: 1, vault_id: vaultId, epoch, device, seq, created: c, iv: b64e(iv), ct: b64e(ct) });
}

export function opHeader(data) {
  let h;
  try {
    h = parseJsonBytes(data);
  } catch {
    throw new Malformed('A sync file is damaged.');
  }
  if (!isObj(h) || pget(h, 'format') !== OPS_FORMAT) throw new Malformed('A sync file is damaged.');
  if (pget(h, 'v') !== 1) throw new Unsupported('A sync file was made by a newer Abhify.AI. Update this app first.');
  if (!(isInt(pget(h, 'epoch')) && isInt(pget(h, 'seq')) && isInt(pget(h, 'created'))
        && /^[0-9a-f]{16}$/.test(pyStr(pget(h, 'device', ''))) && /^[0-9a-f]{32}$/.test(pyStr(pget(h, 'vault_id', ''))))) {
    throw new Malformed('A sync file is damaged.');
  }
  return h;
}

/** {device, seq, epoch, created, records} after verifying the tag (header included via AAD). */
export async function openOps(data, keys, vaultId) {
  const h = opHeader(data);
  if (h.vault_id !== vaultId) throw new Malformed('A sync file belongs to another vault.');
  const key = keyFor(keys, h.epoch);
  if (key === null || key === undefined) throw new WrongKey('A sync file uses a newer vault key. Enter your Recovery Key to continue.');
  const iv = b64d(pget(h, 'iv'), 'iv');
  const ct = b64d(pget(h, 'ct'), 'content');
  const plain = await gunz(await open(key, iv, ct, opsAad(h.vault_id, h.epoch, h.device, h.seq, h.created),
    'A sync file was changed or damaged; it was not imported.'));
  const body = body_(plain);
  if (pget(body, 'device') !== h.device || pget(body, 'seq') !== h.seq || !Array.isArray(pget(body, 'records'))) {
    throw new Malformed('A sync file is inconsistent; it was not imported.');
  }
  return { device: h.device, seq: h.seq, epoch: h.epoch, created: h.created, records: body.records };
}

// --------------------------------------------------------------------------- backup v2
const backupAad = (platform, appVersion, created, iterations, saltB64) =>
  `abhify-vault-backup|2|${platform}|${appVersion}|${created}|${iterations}|${saltB64}`;

export function backupPassphraseProblem(passphrase) {
  if (!passphrase || passphrase.length < MIN_BACKUP_PASSPHRASE) {
    return `Use a passphrase of at least ${MIN_BACKUP_PASSPHRASE} characters (or your Recovery Key).`;
  }
  return null;
}

/** records: {kind: [record...]} (plain object, insertion order kept). */
export async function sealBackup(passphrase, platform, appVersion, created, records, categories, rnd = randomBytes, iterations = ITER_PASSPHRASE) {
  const problem = backupPassphraseProblem(passphrase);
  if (problem) throw new VaultError(problem);
  if (!['windows', 'android', 'web'].includes(platform) || String(appVersion).includes('|')) throw new VaultError('Invalid backup header.');
  const salt = rnd(16);
  const saltB64 = b64e(salt);
  const iv = rnd(12);
  const c = pyInt(created);
  const counts = {};
  for (const [k, v] of Object.entries(records)) counts[k] = v.length;
  const manifest = { categories: [...categories], counts, platform, app_version: appVersion, created: c };
  const ct = await seal(await kdf(passphrase, salt, iterations), iv, await gz(jsonBytes({ manifest, records })),
    backupAad(platform, appVersion, c, iterations, saltB64));
  return jsonBytes({ format: BACKUP_FORMAT, v: 2, platform, app_version: appVersion, created: c,
    kdf: { alg: 'PBKDF2-SHA256', iter: iterations, salt: saltB64 }, iv: b64e(iv), ct: b64e(ct) });
}

export function backupHeader(data) {
  let h;
  try {
    h = parseJsonBytes(data);
  } catch {
    throw new Malformed("This isn't an Abhify.AI backup file.");
  }
  if (!isObj(h) || pget(h, 'format') !== BACKUP_FORMAT) throw new Malformed("This isn't an Abhify.AI encrypted backup (v2).");
  if (pget(h, 'v') !== 2) throw new Unsupported('This backup was made by a newer Abhify.AI. Update the app first.');
  const k = truthy(pget(h, 'kdf')) ? h.kdf : {};
  if (!isObj(k) || pget(k, 'alg') !== 'PBKDF2-SHA256' || !isInt(pget(k, 'iter')) || !isInt(pget(h, 'created')) || typeof pget(k, 'salt') !== 'string') {
    throw new Unsupported("This backup uses settings this app doesn't accept.");
  }
  return h;
}

/** {header, manifest, records} after the tag check; counts must match. Nothing is imported here. */
export async function openBackup(data, passphrase) {
  const h = backupHeader(data);
  const k = h.kdf;
  const key = await kdf(passphrase || '', b64d(pget(k, 'salt'), 'salt'), k.iter);
  const iv = b64d(pget(h, 'iv'), 'iv');
  const ct = b64d(pget(h, 'ct'), 'content');
  const plain = await gunz(await open(key, iv, ct,
    backupAad(pyStr(pget(h, 'platform')), pyStr(pget(h, 'app_version')), h.created, k.iter, pyStr(k.salt)),
    'Wrong passphrase, or the file was changed.'));
  const body = body_(plain);
  const manifest = truthy(pget(body, 'manifest')) ? body.manifest : {};
  const records = truthy(pget(body, 'records')) ? body.records : {};
  if (!isObj(manifest) || !isObj(records) || Object.values(records).some((v) => !Array.isArray(v))) throw new Malformed('The backup content is damaged.');
  const counts = truthy(pget(manifest, 'counts')) ? manifest.counts : {};
  if (!isObj(counts)) throw new Malformed('The backup content is damaged.');
  for (const [kind, n] of Object.entries(counts)) {
    if ((has(records, kind) ? records[kind] : []).length !== n) throw new Malformed("The backup content is incomplete (counts don't match).");
  }
  return { header: { platform: h.platform, app_version: h.app_version, created: h.created }, manifest, records };
}
