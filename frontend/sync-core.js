/* =====================================================================
   sync-core.js: manual, end-to-end encrypted sync engine (spec: docs/specs/ABHIFY_SYNC_PROTOCOL_V1.md §4–§6).
   Port of backend/sync_core.py (the reference): same decision rules, ordering, messages and bookkeeping.

   Pure logic over two small duck-typed interfaces (every method may return a value or a promise):
   - Local:  readItems(kinds) -> Map|object {rid: [kind, data]}; add(kind, rid, data); update(kind, rid, data);
             remove(kind, rid); getMeta(key, default); putMeta(key, value);
             transaction(async fn) -> runs fn in one transaction (all or nothing; rethrows).
   - Remote: listNames() -> [name]; get(name) -> Uint8Array; putNew(name, bytes) (throws Exists if the name is taken).

   Nothing runs on a timer. preview() downloads and verifies but changes no records; apply() imports (additive,
   deletions only into "Recently deleted"), then writes this device's changes as one new op file through a durable outbox.
   ===================================================================== */
import * as vc from './vault.js';

export const KINDS = ['device', 'pref', 'conversation', 'memory', 'message'];
export const SELECTABLE = ['conversation', 'message', 'memory', 'pref'];
export const PREF_KEYS = ['user_name', 'theme', 'answer_detail', 'answer_depth', 'answer_style', 'answer_language'];
export const ORDER = Object.freeze(Object.fromEntries(KINDS.map((k, i) => [k, i])));
export const HIST = 32;
const RID = /^[A-Za-z0-9_.:\-]{8,80}$/;
const DEV = /^[0-9a-f]{16}$/;

/** User-facing; never contains keys or personal content. */
export class SyncError extends Error { constructor(m) { super(m); this.name = 'SyncError'; } }
/** Remote.putNew: a file with that name is already there. */
export class Exists extends Error { constructor(m) { super(m); this.name = 'Exists'; } }

const { isObj, has, pget, truthy, isInt, pyStr } = vc;

// --------------------------------------------------------------------------- helpers
/** Python string ordering (by code point, not UTF-16 unit). */
export function cmpStr(a, b) {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    let x = a.charCodeAt(i), y = b.charCodeAt(i);
    if (x === y) continue;
    x = x >= 0xE000 ? x - 0x800 : (x >= 0xD800 ? x + 0x2000 : x);
    y = y >= 0xE000 ? y - 0x800 : (y >= 0xD800 ? y + 0x2000 : y);
    return x - y;
  }
  return a.length - b.length;
}
const byKey = (a, b) => cmpStr(a[0], b[0]);

function toMap(x) {
  if (x instanceof Map) return new Map(x);
  return new Map(Object.entries(x || {}));
}
const toObj = (m) => Object.fromEntries(m);
const clone = (x) => (x === undefined ? x : JSON.parse(JSON.stringify(x)));
/** Python len(str): code points. */
function pyLen(s) {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0xD800 || c > 0xDBFF || i + 1 >= s.length) n++;
    else { const d = s.charCodeAt(i + 1); if (d >= 0xDC00 && d <= 0xDFFF) i++; n++; }
  }
  return n;
}
function keysOf(keys) {
  if (keys instanceof Map) return keys;
  return new Map(Object.entries(keys || {}).map(([k, v]) => [Number(k), v]));
}

// --------------------------------------------------------------------------- records
export const vid = (v) => (v ? `${v.dev}:${v.n}` : null);

/** Python json.dumps(sort_keys=True, ensure_ascii=False, separators=(",", ":")). */
export function canonicalJson(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(',')}]`;
  if (typeof v === 'object') {
    const ks = Object.keys(v).sort(cmpStr);
    return `{${ks.map((k) => `${JSON.stringify(k)}:${canonicalJson(v[k])}`).join(',')}}`;
  }
  return JSON.stringify(v);
}

/** SHA-256 hex of the canonical JSON (UTF-8). */
export async function dataHash(data) {
  return vc.hex(await vc.sha256(new TextEncoder().encode(canonicalJson(data))));
}

const s_ = (v, n) => typeof v === 'string' && (v.length <= n || pyLen(v) <= n);
const ms_ = (v) => isInt(v) && v >= 0 && v < 1e14;

/** The record in normalized form, or null when it must not be imported (counted as invalid). */
export function validateRecord(r) {
  if (!isObj(r) || !KINDS.includes(pget(r, 'kind')) || typeof pget(r, 'rid') !== 'string' || !RID.test(r.rid)) return null;
  const ver = pget(r, 'ver');
  if (!(isObj(ver) && DEV.test(pyStr(pget(ver, 'dev', ''))) && isInt(pget(ver, 'n')) && ver.n >= 1)) return null;
  const base = pget(r, 'base');
  if (base !== null && !(isObj(base) && isInt(pget(base, 'n')) && typeof pget(base, 'dev') === 'string')) return null;
  const out = {
    rid: r.rid, kind: r.kind, ver: { dev: ver.dev, n: ver.n },
    base: truthy(base) ? { dev: base.dev, n: base.n } : null, at: ms_(pget(r, 'at')) ? r.at : 0, del: pget(r, 'del') === true,
  };
  if (out.del) { out.data = {}; return out; }
  const d = pget(r, 'data');
  if (!isObj(d)) return null;
  const g = (k) => pget(d, k);
  const k = r.kind;
  let ok, keep;
  if (k === 'conversation') {
    ok = s_(g('title'), 500) && ms_(g('created'));
    keep = ['title', 'created', 'pinned', 'archived'];
  } else if (k === 'message') {
    ok = typeof g('conv') === 'string' && RID.test(d.conv) && ['user', 'assistant'].includes(g('role'))
      && s_(g('content'), 1000000) && ms_(g('created')) && (g('model') === null || s_(g('model'), 200));
    keep = ['conv', 'role', 'content', 'created', 'model'];
  } else if (k === 'memory') {
    ok = s_(g('content'), 20000) && ms_(g('created')) && (g('category') === null || s_(g('category'), 40));
    keep = ['content', 'created', 'category', 'pinned'];
  } else if (k === 'pref') {
    ok = PREF_KEYS.includes(g('key')) && s_(g('value'), 200) && r.rid === `pref:${pyStr(g('key'))}`;
    keep = ['key', 'value'];
  } else {
    ok = (s_(g('name'), 80) && ['windows', 'android', 'web'].includes(g('platform')) && ms_(g('added')) && r.rid === ver.dev)
      || (g('revoked') === true && s_(g('name'), 80));
    keep = ['name', 'platform', 'added', 'revoked', 'revoked_after_seq'];
  }
  if (!ok) return null;
  for (const b of ['pinned', 'archived', 'revoked']) {
    if (has(d, b) && typeof d[b] !== 'boolean') return null;
  }
  out.data = {};
  for (const kk of keep) if (has(d, kk) && d[kk] !== null) out.data[kk] = d[kk];
  return out;
}

const WS_RUN = new RegExp(`[${vc.PY_WS}]+`, 'g');
/** Python str.casefold() approximation: full upper-casing then lower-casing (ß -> ss), final sigma folded. */
const casefold = (s) => s.toUpperCase().toLowerCase().replace(/ς/g, 'σ');

export function naturalKey(kind, data) {
  if (kind === 'memory') {
    const words = pyStr(pget(data, 'content', '')).split(WS_RUN).filter((w) => w !== '');
    return 'm|' + casefold(words.join(' '));
  }
  if (kind === 'message') {
    return `g|${pyStr(pget(data, 'conv'))}|${pyStr(pget(data, 'role'))}|${pyStr(pget(data, 'created'))}|${pyStr(pget(data, 'content'))}`;
  }
  return null;
}

// --------------------------------------------------------------------------- state
export class State {
  constructor(kind, o = {}) {
    this.kind = kind;
    this.ver = o.ver ?? null;            // "dev:n" last applied/created here
    this.hist = o.hist ? [...o.hist] : [];
    this.maxn = o.maxn ?? 0;
    this.hash = o.hash ?? null;          // local canonical data hash at last sync (null: nothing local to compare)
    this.deleted = o.deleted ?? false;
    this.alias_of = o.alias_of ?? null;
    this.detached = o.detached ?? false; // deleted here, user chose not to delete elsewhere
  }

  static from(o) { return new State(o.kind, o); }

  asdict() {
    return { kind: this.kind, ver: this.ver, hist: [...this.hist], maxn: this.maxn, hash: this.hash,
      deleted: this.deleted, alias_of: this.alias_of, detached: this.detached };
  }

  push(newVer) {
    const old = this.ver;
    if (old && old !== vid(newVer)) this.hist = [old, ...this.hist.filter((h) => h !== old)].slice(0, HIST);
    this.ver = vid(newVer);
    this.maxn = Math.max(this.maxn, newVer.n);
  }

  knows(v) {
    return vid(v) === this.ver || this.hist.includes(vid(v));
  }
}

function parseVer(s) {
  if (!s) return null;
  const i = s.lastIndexOf(':');
  return { dev: s.slice(0, i), n: parseInt(s.slice(i + 1), 10) };
}

const action = (op, record, detail = '') => ({ op, record, detail });

function newId() {
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  return vc.hex(b);                       // uuid4().hex
}

// --------------------------------------------------------------------------- engine
export class Engine {
  constructor(local, remote, device, platform, deviceName, now = () => Date.now()) {
    if (typeof device !== 'string' || !DEV.test(device)) throw new RangeError('device id');
    this.local = local; this.remote = remote; this.me = device; this.platform = platform; this.name = deviceName; this.now = now;
  }

  async _meta(key, dflt = null) { return this.local.getMeta(key, dflt); }

  // ---- bookkeeping in Local meta
  async _states() {
    const m = new Map();
    for (const [k, v] of Object.entries((await this._meta('states')) || {})) m.set(k, State.from(v));
    return m;
  }

  async _saveStates(st) {
    await this.local.putMeta('states', Object.fromEntries([...st].map(([k, v]) => [k, v.asdict()])));
  }

  async _seen() { return toMap(await this._meta('seen')); }

  async selected() {
    return ((await this._meta('kinds')) || []).filter((k) => SELECTABLE.includes(k));
  }

  /** {device id: info} as stored. */
  async devices() { return toObj(toMap(await this._meta('devices'))); }

  async _devicesMap() { return toMap(await this._meta('devices')); }

  // ---- remote reading
  /** Verified op bodies for every other device, contiguous after what was applied. Staged locally (resumable). */
  async _incoming(keys, vaultId) {
    const seen = await this._seen();
    const names = await this.remote.listNames();
    const per = new Map();
    for (const n of names) {
      const p = vc.parseOpName(n);
      if (p && p[0] !== this.me) {
        if (!per.has(p[0])) per.set(p[0], []);
        per.get(p[0]).push(p[1]);
      }
    }
    const staged = toMap(await this._meta('staged'));
    const ops = [], notes = [];
    for (const [dev, seqs] of [...per].sort(byKey)) {
      let want = (seen.has(dev) ? seen.get(dev) : 0) + 1;
      for (const s of [...new Set(seqs.filter((x) => x >= want))].sort((a, b) => a - b)) {   // a name listed twice on Drive counts once
        if (s !== want) { notes.push(`waiting for an earlier sync file from device ${dev.slice(0, 6)}`); break; }
        const name = vc.opName(dev, s);
        let body;
        if (staged.has(name)) {
          body = staged.get(name);
        } else {
          try {
            body = await vc.openOps(await this.remote.get(name), keys, vaultId);
          } catch (e) {
            if (!(e instanceof vc.VaultError)) throw e;
            notes.push(e.message);
            break;
          }
          if (body.device !== dev || body.seq !== s) { notes.push('a sync file has the wrong name; it was not imported'); break; }
          staged.set(name, body);
        }
        ops.push(body);
        want += 1;
      }
    }
    await this.local.putMeta('staged', toObj(staged));
    return [ops, notes];
  }

  // ---- planning (pure simulation over copies)
  async _plan(ops, states, items, kinds) {
    const devices = await this._devicesMap();
    const nat = new Map();
    for (const [rid, [k, d]] of items) { const nk = naturalKey(k, d); if (nk) nat.set(nk, rid); }
    const knownConv = new Set();
    for (const [rid, [k]] of items) if (k === 'conversation') knownConv.add(rid);
    for (const [r, s] of states) if (s.kind === 'conversation' && !s.deleted) knownConv.add(r);
    const acts = [];
    const held = [...((await this._meta('held')) || [])];
    const queue = [...ops, { device: 'held', seq: 0, records: held }];     // held messages after the files that may bring their conversation
    for (const op of ops) {                                                  // revocations first, whatever device order
      for (const r of op.records) {
        const v = validateRecord(r);
        if (v && v.kind === 'device' && v.data.revoked === true && v.rid !== op.device) {
          devices.set(v.rid, { ...(devices.get(v.rid) || {}), ...v.data });
        }
      }
    }
    for (const op of queue) {
      const dev = op.device;
      const info = devices.get(dev) || {};
      const cut = has(info, 'revoked_after_seq') ? info.revoked_after_seq : null;
      if (dev !== 'held' && cut !== null && op.seq > cut) {
        for (const _ of op.records) acts.push(action('revoked', null, `from removed device ${dev.slice(0, 6)}`));
        continue;
      }
      const recs = op.records.map(validateRecord);
      for (const r of recs) if (r === null) acts.push(action('invalid', null, 'a record failed validation'));
      const sorted = recs.filter((x) => x).sort((a, b) => (ORDER[a.kind] - ORDER[b.kind]) || ((a.base ? 0 : 1) - (b.base ? 0 : 1)));
      for (const r of sorted) acts.push(await this._decide(r, states, items, nat, knownConv, kinds));   // changes before new records
    }
    return acts;
  }

  async _decide(r, states, items, nat, knownConv, kinds) {
    const rid = r.rid, kind = r.kind;
    if (kind === 'device') {                                    // bookkeeping, never a user conflict; "revoked" is sticky
      let s = states.get(rid);
      if (s === undefined) { s = new State('device'); states.set(rid, s); }
      else if (s.knows(r.ver)) return action('skip', r, 'already here');
      if (r.ver.n > s.maxn || s.ver === null) s.push(r.ver);
      else { s.hist = [vid(r.ver), ...s.hist].slice(0, HIST); s.maxn = Math.max(s.maxn, r.ver.n); }
      return action('update', r);
    }
    if (!kinds.has(kind)) return action('skip', r, 'category not selected');
    const s = states.get(rid);
    const local = items.has(rid) ? items.get(rid) : null;
    if (s === undefined) {
      if (r.del) {
        const st = new State(kind, { deleted: true });
        states.set(rid, st);
        st.push(r.ver);
        return action('tombstone', r);
      }
      if (kind === 'message' && !knownConv.has(r.data.conv)) return action('hold', r, "its conversation hasn't arrived yet");
      const nk = naturalKey(kind, r.data);
      if (nk && nat.has(nk) && nat.get(nk) !== rid) {
        const st = new State(kind, { alias_of: nat.get(nk) });
        states.set(rid, st);
        st.push(r.ver);
        return action('alias', r, 'same as an item already here');
      }
      const st = new State(kind);
      st.push(r.ver);
      st.hash = await dataHash(r.data);
      states.set(rid, st);
      items.set(rid, [kind, r.data]);
      if (kind === 'conversation') knownConv.add(rid);
      if (nk) nat.set(nk, rid);
      return action('add', r);
    }
    if (s.knows(r.ver)) return action('skip', r, 'already here');
    s.maxn = Math.max(s.maxn, r.ver.n);
    if (s.alias_of) { s.push(r.ver); return action('skip', r, 'duplicate of an item already here'); }
    const localChanged = local !== null && s.hash !== null && (await dataHash(local[1])) !== s.hash;
    const locallyGone = local === null && !s.deleted && !s.detached && s.hash !== null && kind !== 'device';
    if (vid(r.base) === s.ver) {
      if (localChanged || (locallyGone && !r.del)) {
        return action('conflict', r, localChanged ? 'changed here and on another device' : 'deleted here, changed on another device');
      }
      s.push(r.ver);
      if (r.del) {
        s.deleted = true; s.hash = null;
        if (local === null && kind !== 'device') return action('tombstone', r);
        Engine._renat(nat, kind, rid, local ? local[1] : null, null);
        items.delete(rid);
        return action('delete', r);
      }
      s.deleted = false; s.detached = false;
      s.hash = await dataHash(r.data);
      if (local === null && kind !== 'device') {
        items.set(rid, [kind, r.data]);
        return action('add', r, 'restored from another device');
      }
      Engine._renat(nat, kind, rid, local ? local[1] : null, r.data);
      items.set(rid, [kind, r.data]);
      return action('update', r);
    }
    // concurrent
    const curN = (parseVer(s.ver) || { n: 0 }).n;
    if (r.del && (local === null || s.deleted)) {
      if (r.ver.n >= curN) s.push(r.ver); else s.hist = [vid(r.ver), ...s.hist].slice(0, HIST);
      s.deleted = true;
      return action('adopt', r, 'already deleted here');
    }
    if (!r.del && local !== null && (await dataHash(local[1])) === (await dataHash(r.data))) {
      if (r.ver.n > curN) s.push(r.ver);
      else s.hist = [vid(r.ver), ...s.hist].slice(0, HIST);
      return action('adopt', r, 'same content');
    }
    return action('conflict', r, 'changed on two devices');
  }

  static _renat(nat, kind, rid, old, neu) {
    if (old !== null && old !== undefined) {
      const k = naturalKey(kind, old);
      if (k && nat.get(k) === rid) nat.delete(k);
    }
    if (neu !== null && neu !== undefined) {
      const k = naturalKey(kind, neu);
      if (k) nat.set(k, rid);
    }
  }

  // ---- local changes
  /** This device's changes as new versions. Records with an open conflict wait until the user resolves it. */
  async _collect(states, items, kinds, approveDeletes, blocked = new Set()) {
    const out = [], pendingDel = [];
    const now = this.now();
    const entries = [...items].sort((a, b) => ORDER[a[1][0]] - ORDER[b[1][0]]);
    for (const [rid, [kind, data]] of entries) {
      if ((kind !== 'device' && !kinds.has(kind)) || blocked.has(rid) || (kind === 'device' && rid !== this.me)) continue;
      let s = states.get(rid);
      const h = await dataHash(data);
      if (s !== undefined && (s.alias_of || s.hash === h)) continue;
      if (s === undefined) { s = new State(kind); states.set(rid, s); }
      const base = parseVer(s.ver);
      const v = { dev: this.me, n: s.maxn + 1 };
      s.push(v);
      s.hash = h; s.deleted = false; s.detached = false;
      out.push({ rid, kind, ver: v, base, at: now, del: false, data });
    }
    for (const [rid, s] of [...states].sort(byKey)) {
      if (kinds.has(s.kind) && !items.has(rid) && !blocked.has(rid) && !s.deleted && !s.detached && !s.alias_of && s.hash !== null) {
        if (approveDeletes.has(rid)) {
          const base = parseVer(s.ver);
          const v = { dev: this.me, n: s.maxn + 1 };
          s.push(v);
          s.deleted = true; s.hash = null;
          out.push({ rid, kind: s.kind, ver: v, base, at: now, del: true, data: {} });
        } else {
          pendingDel.push({ rid, kind: s.kind });
        }
      }
    }
    return [out, pendingDel];
  }

  async _checkNotRevoked() {
    if (truthy(pget((await this._devicesMap()).get(this.me) || {}, 'revoked'))) {
      throw new SyncError("This device was removed from sync on another device. Your data here is unchanged; to sync again, reset this device's sync identity.");
    }
  }

  /** Marks another device removed (its files after the last one applied here are ignored everywhere). The caller
   *  rotates the vault key with the Recovery Key; the record uploads on the next Sync Now. */
  async revoke(device) {
    if (device === this.me) throw new SyncError('To stop syncing this device, use Disconnect instead.');
    let d;
    await this.local.transaction(async () => {
      const devices = await this._devicesMap(), states = await this._states();
      d = { ...(truthy(devices.get(device)) ? devices.get(device) : { name: 'device', platform: 'windows', added: 0 }) };
      const seen = await this._seen();
      Object.assign(d, { revoked: true, revoked_after_seq: seen.has(device) ? seen.get(device) : 0 });
      devices.set(device, d);
      if (!states.has(device)) states.set(device, new State('device'));
      const s = states.get(device);
      const base = parseVer(s.ver);
      const v = { dev: this.me, n: s.maxn + 1 };
      s.push(v);
      const extra = [...((await this._meta('extra')) || [])];
      extra.push({ rid: device, kind: 'device', ver: v, base, at: this.now(), del: false, data: d });
      await this.local.putMeta('extra', extra);
      await this.local.putMeta('devices', toObj(devices));
      await this._saveStates(states);
    });
    return d;
  }

  async _items(kinds) {
    const items = toMap(await this.local.readItems([...kinds].sort(cmpStr)));
    let info = await this._meta('device_info');
    if (!truthy(info)) info = { name: this.name, platform: this.platform, added: this.now() };
    await this.local.putMeta('device_info', info);
    items.set(this.me, ['device', info]);
    return items;
  }

  // ---- public API
  /** What Sync Now would do. Downloads and verifies; changes no records. keys: Map(epoch -> key) or {epoch: key}. */
  async preview(keys, vaultId) {
    keys = keysOf(keys);
    const kinds = new Set(await this.selected());
    if (!kinds.size) throw new SyncError('Choose at least one category to sync.');
    await this._checkNotRevoked();
    const [ops, notes] = await this._incoming(keys, vaultId);
    const states = await this._states(), items = await this._items(kinds);
    const acts = await this._plan(ops, states, items, kinds);
    const blocked = new Set([...(await this.conflicts()).map((c) => c.rid), ...acts.filter((a) => a.op === 'conflict').map((a) => a.record.rid)]);
    const [local, pendingDel] = await this._collect(states, items, kinds, new Set(), blocked);
    const summary = {};
    for (const a of acts) {
      const k = a.record ? a.record.kind : 'other';
      if (k === 'device') continue;
      summary[a.op] = summary[a.op] || {};
      summary[a.op][k] = (summary[a.op][k] || 0) + 1;
    }
    const up = {};
    for (const r of local) if (r.kind !== 'device') up[r.kind] = (up[r.kind] || 0) + 1;
    return { incoming: summary, files: ops.length, upload: up, local_deletions: pendingDel,
      conflicts_waiting: ((await this._meta('conflicts')) || []).length, notes, outbox: ((await this._meta('outbox')) || []).length };
  }

  /** opts: {direction: 'both'|'upload'|'download', approveDeletes: [rid], keepElsewhere: [rid]}. */
  async apply(keys, vaultId, epoch, { direction = 'both', approveDeletes = [], keepElsewhere = [] } = {}) {
    keys = keysOf(keys);
    if (!['both', 'upload', 'download'].includes(direction)) throw new RangeError(direction);
    const kinds = new Set(await this.selected());
    if (!kinds.size) throw new SyncError('Choose at least one category to sync.');
    await this._checkNotRevoked();
    const result = { added: 0, updated: 0, deleted: 0, conflicts: 0, held: 0, skipped: 0, invalid: 0, uploaded: 0, notes: [] };
    let ops = [];
    if (direction === 'both' || direction === 'download') {                // network first, outside the database transaction
      const [o, notes] = await this._incoming(keys, vaultId);
      ops = o;
      result.notes.push(...notes);
    }
    let revokedHere = false;
    await this.local.transaction(async () => {
      const states = await this._states();
      if (direction === 'both' || direction === 'download') {
        const items = await this._items(kinds);
        const planStates = new Map([...states].map(([k, v]) => [k, State.from(v.asdict())]));
        const acts = await this._plan(ops, planStates, new Map(items), kinds);
        await this._execute(acts, states, items, result);
        const seen = await this._seen();
        for (const op of ops) seen.set(op.device, op.seq);
        await this.local.putMeta('seen', toObj(seen));
        const staged = toMap(await this._meta('staged'));
        for (const op of ops) staged.delete(vc.opName(op.device, op.seq));
        await this.local.putMeta('staged', toObj(staged));
      }
      for (const rid of keepElsewhere) if (states.has(rid)) states.get(rid).detached = true;
      revokedHere = truthy(pget((await this._devicesMap()).get(this.me) || {}, 'revoked'));
      if ((direction === 'both' || direction === 'upload') && !revokedHere) {
        const items = await this._items(kinds);
        let [records] = await this._collect(states, items, kinds, new Set(approveDeletes), new Set((await this.conflicts()).map((c) => c.rid)));
        records = records.concat((await this._meta('extra')) || []);
        await this.local.putMeta('extra', []);
        if (records.length) {
          const seq = Number((await this._meta('seq')) || 0) + 1;
          const data = await vc.sealOps(vaultId, epoch, keys.get(epoch), this.me, seq, this.now(), records);
          const outbox = [...((await this._meta('outbox')) || [])];
          outbox.push({ name: vc.opName(this.me, seq), data: vc.b64e(data), count: records.filter((x) => x.kind !== 'device').length });
          await this.local.putMeta('outbox', outbox);
          await this.local.putMeta('seq', seq);
        }
      }
      await this._saveStates(states);
    });
    if (revokedHere) {
      await this._history(direction, result);
      throw new SyncError('This device was removed from sync on another device. Nothing more was uploaded; your data here is unchanged.');
    }
    result.uploaded = await this.flushOutbox();
    await this._history(direction, result);
    return result;
  }

  /** Uploads waiting op files in order. Same bytes are retried after an interruption; never overwrites. */
  async flushOutbox() {
    let sent = 0;
    const outbox = [...((await this._meta('outbox')) || [])];
    while (outbox.length) {
      const item = outbox[0];
      const data = vc.b64d(item.data);
      try {
        await this.remote.putNew(item.name, data);
      } catch (e) {
        if (!(e instanceof Exists || (e && e.name === 'Exists'))) throw e;
        if (!vc.bytesEqual(await this.remote.get(item.name), data)) {
          throw new SyncError("Another installation is using this device's sync identity (for example a copied data folder). "
            + "Nothing was overwritten. Reset this device's sync identity in Data & Sync.");
        }
      }
      sent += has(item, 'count') ? item.count : 0;
      outbox.shift();
      await this.local.putMeta('outbox', outbox);
    }
    return sent;
  }

  async _execute(acts, states, items, result) {
    const held = [];
    const conflicts = [...((await this._meta('conflicts')) || [])];
    const trash = [...((await this._meta('trash')) || [])];
    const devices = await this._devicesMap();
    const nat = new Map();
    for (const [rid, [k, d]] of items) { const nk = naturalKey(k, d); if (nk) nat.set(nk, rid); }
    const knownConv = new Set();
    for (const [rid, [k]] of items) if (k === 'conversation') knownConv.add(rid);
    for (const [r, s] of states) if (s.kind === 'conversation' && !s.deleted) knownConv.add(r);
    for (const a of acts) {
      const r = a.record;
      if (a.op === 'invalid' || a.op === 'revoked') { result[a.op === 'invalid' ? 'invalid' : 'skipped'] += 1; continue; }
      // re-decide against the live state (the plan ran on copies; this keeps one code path for both)
      const act = await this._decide(r, states, new Map(items), nat, knownConv, new Set(await this.selected()));
      const rid = r.rid, kind = r.kind;
      if (kind === 'device' && (act.op === 'add' || act.op === 'update')) {
        const old = devices.get(rid) || {};
        const merged = { ...old, ...r.data };
        if (truthy(pget(old, 'revoked'))) {
          merged.revoked = true;
          merged.revoked_after_seq = has(old, 'revoked_after_seq') ? old.revoked_after_seq : (has(r.data, 'revoked_after_seq') ? r.data.revoked_after_seq : 0);
        }
        if (rid !== this.me || truthy(merged.revoked)) devices.set(rid, merged);
        continue;                                            // bookkeeping: not counted as the user's data
      }
      if (act.op === 'add') {
        if (kind === 'device') devices.set(rid, r.data);
        else {
          await this.local.add(kind, rid, r.data);
          items.set(rid, [kind, r.data]);
          states.get(rid).hash = await this._rehash(kind, rid);
        }
        result.added += 1;
      } else if (act.op === 'update') {
        if (kind === 'device') devices.set(rid, r.data);
        else {
          await this.local.update(kind, rid, r.data);
          items.set(rid, [kind, r.data]);
          states.get(rid).hash = await this._rehash(kind, rid);
        }
        result.updated += 1;
      } else if (act.op === 'delete') {
        if (kind === 'device') devices.delete(rid);
        else {
          const old = items.has(rid) ? items.get(rid) : null;
          items.delete(rid);
          if (old) {
            trash.push({ id: newId(), rid, kind, data: old[1], reason: 'deleted on another device', at: this.now() });
            await this.local.remove(kind, rid);
          }
        }
        result.deleted += 1;
      } else if (act.op === 'conflict') {
        conflicts.push({ id: newId(), rid, kind, local: items.has(rid) ? items.get(rid)[1] : null, remote: r, why: act.detail, at: this.now() });
        result.conflicts += 1;
      } else if (act.op === 'hold') {
        held.push(r);
        result.held += 1;
      } else {
        result.skipped += 1;
      }
    }
    await this.local.putMeta('held', held);
    await this.local.putMeta('conflicts', conflicts);
    await this.local.putMeta('trash', trash);
    await this.local.putMeta('devices', toObj(devices));
  }

  async _rehash(kind, rid) {
    const got = toMap(await this.local.readItems([kind])).get(rid);
    return got ? dataHash(got[1]) : null;
  }

  // ---- conflicts, recently deleted, history
  async conflicts() { return [...((await this._meta('conflicts')) || [])]; }

  /** keep_mine | keep_theirs | keep_both. The result uploads on the next Sync Now. */
  async resolve(conflictId, choice) {
    if (!['keep_mine', 'keep_theirs', 'keep_both'].includes(choice)) throw new RangeError(choice);
    await this.local.transaction(async () => {
      const cs = await this.conflicts();
      const c = cs.find((x) => x.id === conflictId);
      if (c === undefined) throw new SyncError('That conflict was already resolved.');
      const states = await this._states(), r = c.remote, rid = c.rid, kind = c.kind;
      if (!states.has(rid)) states.set(rid, new State(kind));
      const s = states.get(rid);
      const items = toMap(await this.local.readItems([kind]));
      let local = items.has(rid) ? items.get(rid) : null;
      s.push(r.ver);                                        // the other version is now known here
      if (choice === 'keep_theirs') {
        if (local) await this._trash(rid, kind, local[1], "replaced by the other device's version");
        if (r.del) {
          if (local) await this.local.remove(kind, rid);
          s.deleted = true; s.hash = null;
        } else {
          if (local) await this.local.update(kind, rid, r.data); else await this.local.add(kind, rid, r.data);
          s.deleted = false; s.detached = false; s.hash = await this._rehash(kind, rid);
        }
      } else {
        if (choice === 'keep_both' && !r.del) {
          const newRid = newId();
          await this.local.add(kind, newRid, { ...r.data });   // new record: uploads as new next time
        }
        if (local === null && truthy(pget(c, 'local'))) {
          await this.local.add(kind, rid, c.local);
          local = [kind, c.local];
        }
        if (local === null && !r.del) {
          s.hash = await dataHash(r.data); s.deleted = false; s.detached = false;   // kept deleted here: offered as a deletion in the next preview
        } else {
          s.hash = null; s.deleted = false; s.detached = false;                     // forces a new local version based on the other one
        }
      }
      await this._saveStates(states);
      await this.local.putMeta('conflicts', cs.filter((x) => x.id !== conflictId));
    });
  }

  async _trash(rid, kind, data, reason) {
    const trash = [...((await this._meta('trash')) || [])];
    trash.push({ id: newId(), rid, kind, data, reason, at: this.now() });
    await this.local.putMeta('trash', trash);
  }

  async recentlyDeleted(days = 30) {
    const cutoff = this.now() - days * 86400000;
    return ((await this._meta('trash')) || []).filter((t) => t.at >= cutoff);
  }

  async restoreDeleted(trashId) {
    await this.local.transaction(async () => {
      const trash = [...((await this._meta('trash')) || [])];
      const t = trash.find((x) => x.id === trashId);
      if (t === undefined) throw new SyncError('That item is no longer in Recently deleted.');
      if (toMap(await this.local.readItems([t.kind])).has(t.rid)) throw new SyncError('That item is already here.');
      await this.local.add(t.kind, t.rid, t.data);
      const states = await this._states();
      if (!states.has(t.rid)) states.set(t.rid, new State(t.kind));
      const s = states.get(t.rid);
      s.deleted = false; s.detached = false; s.hash = null;  // uploads as a new version (undelete) next time
      await this._saveStates(states);
      await this.local.putMeta('trash', trash.filter((x) => x.id !== trashId));
    });
  }

  /** After choosing more categories: read every device's files again (already-known records are skipped). */
  async redownloadAll() { await this.local.putMeta('seen', {}); }

  async _history(direction, result) {
    const h = [...((await this._meta('history')) || [])];
    const entry = { at: this.now(), direction };
    for (const [k, v] of Object.entries(result)) if (k !== 'notes') entry[k] = v;
    entry.notes = result.notes.slice(0, 5);
    h.unshift(clone(entry));
    await this.local.putMeta('history', h.slice(0, 100));
  }
}
