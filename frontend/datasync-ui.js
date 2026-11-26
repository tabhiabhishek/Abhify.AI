/* =====================================================================
   datasync-ui.js: More → Settings → Data & Sync (7.2.0-dev.1).
   Manual, end-to-end encrypted sync and backups through the user's own Google Drive.
   Windows: talks to this computer's backend (/datasync/*, this computer only).
   Standalone Web: browser-side engine (vault.js + sync-core.js) is wired in the next phase; until then this
   screen says so honestly. Nothing here runs on a timer; every network action is a button.
   Uses app.js globals: $, api, esc, toast, state, PLATFORM, stateHtml, skeleton.
   ===================================================================== */
const ds = { status: null, vault: null, preview: null, conflicts: [], deleted: [], history: [], devices: null, backups: [], drive: null,
  restore: null, created: null, busy: '', err: '', interop: null, polling: false };

const CAT_LABELS = { conversation: 'Conversations (with their messages)', memory: 'Personal memory', pref: 'Portable preferences (your name, answer style)' };

function when(ms) { try { return new Date(ms).toLocaleString(); } catch { return ''; } }
async function call(path, opts) {
  try { return await api('/datasync' + path, { timeout: 300000, ...opts }); }
  catch (e) { throw new Error(e.message === 'offline' ? 'The Abhify.AI app on this computer is not reachable.' : e.message); }
}

export async function open() {
  render();
  if (PLATFORM === 'web') return;
  await refresh();
}

async function refresh() {
  try {
    ds.status = await call('/status');
    if (ds.status.connected) {
      ds.history = await call('/history');
      ds.conflicts = await call('/conflicts');
      ds.deleted = await call('/deleted');
      ds.devices = await call('/devices');
    }
    ds.backups = (await call('/backups')).local;
    ds.err = '';
  } catch (e) { ds.err = e.message; }
  render();
}

async function act(name, fn, okMsg) {
  ds.busy = name; ds.err = ''; render();
  try { const r = await fn(); if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg, 4500); return r; }
  catch (e) { ds.err = e.message; return null; }
  finally { ds.busy = ''; render(); }
}

/* ---------------------------------------------------------------- rendering */
function render() {
  const body = $('#dataSyncBody');
  if (!body) return;
  if (PLATFORM === 'web') {
    body.innerHTML = `<section class="pers"><h4>Data &amp; Sync in the browser</h4>
      <p class="muted small">This Web app is standalone: your chats and memories stay in this browser. Encrypted Google Drive sync and backups for the browser
      are being added in this development line and aren't enabled in this build yet. Nothing is sent anywhere.</p></section>`;
    return;
  }
  const s = ds.status;
  if (!s) { body.innerHTML = ds.err ? stateHtml('error', "Couldn't open Data & Sync", ds.err) : skeleton(4); return; }
  const busy = (n) => ds.busy === n ? ' disabled aria-busy="true"' : (ds.busy ? ' disabled' : '');
  const err = ds.err ? `<p class="err" role="alert">${esc(ds.err)}</p>` : '';
  body.innerHTML = `${err}
  <section class="pers" aria-labelledby="dsDriveH"><h4 id="dsDriveH">A · Google Drive</h4>${driveHtml(s, busy)}</section>
  <section class="pers" aria-labelledby="dsSyncH"><h4 id="dsSyncH">B · Manual synchronization</h4>${syncHtml(s, busy)}</section>
  <section class="pers" aria-labelledby="dsBackupH"><h4 id="dsBackupH">C · Backup &amp; restore</h4>${backupHtml(s, busy)}</section>
  <section class="pers" aria-labelledby="dsPrivH"><h4 id="dsPrivH">D · Privacy &amp; security</h4>${privacyHtml(s, busy)}</section>`;
}

function driveHtml(s, busy) {
  if (!s.oauth_client) {
    return `<p class="muted small">Optional. To use your own Google Drive, this computer needs the <b>Desktop app</b> OAuth client from the Abhify.AI Google Cloud project
      (one-time owner setup: docs/GOOGLE_CLOUD_OAUTH_SETUP.md). Abhify asks Google only for <code>drive.file</code>: it sees only files it creates.</p>
      <label class="field">OAuth client file (client_secret_….json) <input type="file" id="dsClientFile" accept=".json,application/json"></label>`;
  }
  const acct = s.account?.email ? esc(s.account.email) : 'your Google account';
  if (!s.connected) {
    const flow = s.flow?.status === 'waiting' ? '<p class="muted small" aria-live="polite">Waiting for Google in your browser… finish there, then come back.</p>'
      : s.flow?.message ? `<p class="muted small">${esc(s.flow.message)}</p>` : '';
    return `<p class="muted small">Not connected. Connecting opens Google's page in your browser; you never type your Google password into Abhify.</p>${flow}
      <div class="row-tight"><button type="button" class="btn btn-primary btn-sm" data-ds="connect"${busy('connect')}>Connect Google Drive</button></div>`;
  }
  const interop = ds.interop ? `<div class="muted small" aria-live="polite"><p><b>Drive access check:</b> ${ds.interop.root_visible_before
      ? 'this computer can see the Abhify.AI folder' + (Object.keys(ds.interop.folders_seen).length ? ` (${esc(Object.keys(ds.interop.folders_seen).join(', '))})` : '')
      : 'this computer could <b>not</b> see an existing Abhify.AI folder (it may not exist yet, or Google keeps each app client separate)'}.
      Wrote <code>${esc(ds.interop.probe)}</code> to Abhify.AI/Imports. ${esc(ds.interop.next)}</p></div>` : '';
  return `<p>✓ Connected as <b>${acct}</b>.</p>
    <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds="interop"${busy('interop')}>Check Drive access with the phone</button>
      <button type="button" class="btn btn-ghost btn-sm" data-ds="reconnect"${busy('connect')}>Reauthorize</button>
      <button type="button" class="btn btn-ghost btn-sm" data-ds="disconnect"${busy('disconnect')}>Disconnect</button></div>${interop}`;
}

function syncHtml(s, busy) {
  if (!s.connected) return '<p class="muted small">Connect Google Drive first. Sync is always manual: nothing happens until you press Sync Now.</p>';
  if (ds.created) {
    return `<div class="ds-rk" role="group" aria-label="Your Recovery Key"><p><b>Your Recovery Key</b>. Write it down or print it and keep it safe. It is shown only now.
      Without it, synced data can't be recovered on a new device, and Abhify can't recover it for you.</p>
      <p class="ds-rk-code" aria-label="Recovery Key">${esc(ds.created.recovery_key)}</p>
      <label class="field">Type the last group of 4 characters to confirm you saved it <input id="dsRkConfirm" maxlength="4" autocomplete="off" spellcheck="false"></label>
      <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds="print-rk">Print</button>
        <button type="button" class="btn btn-primary btn-sm" data-ds="confirm-vault"${busy('confirm-vault')}>I saved it: create the vault</button></div></div>`;
  }
  if (!s.vault || !s.has_keys) {
    const v = ds.vault;
    return `<p class="muted small">Sync needs an encrypted vault in your Drive. Its key never leaves your devices; only you hold the Recovery Key.</p>
      ${v ? (v.exists ? `<p>A vault already exists in this Drive (created ${esc(when(v.created))}). Join it with your Recovery Key:</p>
        <label class="field">Recovery Key <input id="dsRkJoin" autocomplete="off" spellcheck="false" placeholder="XXXX-XXXX-…"></label>
        <button type="button" class="btn btn-primary btn-sm" data-ds="join"${busy('join')}>Join vault</button>`
        : `<p>No vault yet in this Drive.</p><button type="button" class="btn btn-primary btn-sm" data-ds="create-vault"${busy('create-vault')}>Create vault and Recovery Key</button>`)
        : `<button type="button" class="btn btn-ghost btn-sm" data-ds="probe"${busy('probe')}>Look for a vault in my Drive</button>`}`;
  }
  const kinds = new Set(s.kinds);
  const cats = ['conversation', 'memory', 'pref'].map((k) => `<label class="ds-cat"><input type="checkbox" data-ds-kind="${k}"${kinds.has(k) ? ' checked' : ''}> ${esc(CAT_LABELS[k])}</label>`).join('');
  const p = ds.preview;
  const fmt = (o) => Object.entries(o || {}).map(([k, n]) => `${n} ${k}`).join(', ') || 'nothing';
  const prev = p ? `<div class="ds-preview" aria-live="polite"><p><b>Preview</b> (nothing has changed yet): from other devices: add ${esc(fmt(p.incoming.add))}; update ${esc(fmt(p.incoming.update))};
      deleted elsewhere ${esc(fmt(p.incoming.delete))}; conflicts ${esc(fmt(p.incoming.conflict))}. To upload from this computer: ${esc(fmt(p.upload))}.</p>
      ${p.local_deletions.length ? `<p>Deleted on this computer since the last sync. Tick the ones to delete on your other devices too (they go to Recently deleted there):</p>
        <div class="ds-dels">${p.local_deletions.slice(0, 200).map((d) => `<label><input type="checkbox" data-ds-del="${esc(d.rid)}"> ${esc(d.kind)} ${esc(d.rid.slice(0, 8))}…</label>`).join('')}</div>` : ''}
      ${p.notes.length ? `<p class="muted small">${p.notes.map(esc).join(' · ')}</p>` : ''}</div>` : '';
  const last = s.last ? `<p class="muted small">Last sync: ${esc(when(s.last.at))} · ${s.last.added} added, ${s.last.updated} updated, ${s.last.deleted} deleted, ${s.last.conflicts} conflicts, ${s.last.uploaded} uploaded.</p>` : '<p class="muted small">Not synced yet.</p>';
  const conf = ds.conflicts.length ? `<h5>Resolve conflicts (${ds.conflicts.length})</h5>${ds.conflicts.map((c) => `<div class="ds-conflict card">
      <p class="small"><b>${esc(c.kind)}</b>: ${esc(c.why)}</p>
      <div class="ds-two"><div><small class="muted">This computer</small><p>${esc(summary(c.local))}</p></div><div><small class="muted">Other device</small><p>${esc(c.remote.del ? '(deleted there)' : summary(c.remote.data))}</p></div></div>
      <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds-resolve="${esc(c.id)}:keep_mine">Keep this computer's</button>
        <button type="button" class="btn btn-ghost btn-sm" data-ds-resolve="${esc(c.id)}:keep_theirs">Keep the other</button>
        ${c.remote.del ? '' : `<button type="button" class="btn btn-ghost btn-sm" data-ds-resolve="${esc(c.id)}:keep_both">Keep both</button>`}</div></div>`).join('')}` : '';
  const del = ds.deleted.length ? `<details><summary>Recently deleted by sync or restore (${ds.deleted.length}, kept 30 days)</summary>${ds.deleted.slice(-50).reverse().map((t) =>
      `<div class="ds-trash"><span class="small">${esc(t.kind)}: ${esc(summary(t.data))} <span class="muted">(${esc(t.reason)}, ${esc(when(t.at))})</span></span>
      <button type="button" class="btn btn-ghost btn-sm" data-ds-restore="${esc(t.id)}">Restore</button></div>`).join('')}</details>` : '';
  const hist = ds.history.length ? `<details><summary>Sync history</summary><ul class="small">${ds.history.slice(0, 20).map((h) =>
      `<li>${esc(when(h.at))}: ${esc(h.direction)} · +${h.added} ~${h.updated} −${h.deleted} · ${h.conflicts} conflicts · ${h.uploaded} uploaded</li>`).join('')}</ul></details>` : '';
  return `<p class="muted small">Choose what to sync (all off until you choose). AI models, model settings, hardware settings, API keys and passwords are never synced.</p>
    <div class="ds-cats">${cats}</div>
    <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds="preview"${busy('preview')}>Preview changes</button>
      <button type="button" class="btn btn-primary btn-sm" data-ds="sync"${busy('sync')}>Sync Now</button>
      <button type="button" class="btn btn-ghost btn-sm" data-ds="upload"${busy('sync')}>Upload only</button>
      <button type="button" class="btn btn-ghost btn-sm" data-ds="download"${busy('sync')}>Download only</button></div>
    ${prev}${last}${s.outbox ? `<p class="muted small">${s.outbox} change set(s) waiting to upload; they go first next time.</p>` : ''}${conf}${del}${hist}`;
}

function summary(d) {
  if (!d) return '(not on this device)';
  return String(d.content ?? d.title ?? (d.key ? `${d.key} = ${d.value}` : JSON.stringify(d))).slice(0, 160);
}

function backupHtml(s, busy) {
  const r = ds.restore;
  const list = ds.backups.length ? `<ul class="ds-blist">${ds.backups.slice(0, 10).map((b) => `<li><span class="small">${esc(b.name)} · ${esc(b.platform || '')} · ${Math.round(b.bytes / 1024)} KB</span>
      <a class="btn btn-ghost btn-sm" href="${esc(state.apiBase)}/datasync/backups/${encodeURIComponent(b.name)}" download>Download</a>
      <button type="button" class="btn btn-ghost btn-sm" data-ds-pick="${esc(b.name)}">Restore…</button></li>`).join('')}</ul>` : '<p class="muted small">No encrypted backups yet.</p>';
  const drive = ds.drive ? (ds.drive.length ? `<ul class="ds-blist">${ds.drive.map((b) => `<li><span class="small">${esc(b.name)}</span>
      <button type="button" class="btn btn-ghost btn-sm" data-ds-fetch="${esc(b.id)}">Download to this computer</button></li>`).join('')}</ul>` : '<p class="muted small">No backups in Drive/Abhify.AI/Backups.</p>') : '';
  const restore = r ? (r.preview ? `<div class="ds-preview" aria-live="polite"><p><b>Restore preview</b> (nothing has changed yet): backup from ${esc(r.preview.header.platform)} · ${esc(r.preview.header.app_version)} · ${esc(when(r.preview.header.created))}.</p>
      <ul class="small">${Object.entries(r.preview.plan).map(([k, p]) => `<li><label><input type="checkbox" data-ds-rkind="${k}" checked> ${esc(k)}: ${p.new} new, ${p.same} already here, ${p.different} differ${p.invalid ? `, ${p.invalid} unreadable` : ''}</label></li>`).join('')}</ul>
      <label class="ds-cat"><input type="checkbox" id="dsReplace"> Replace records that differ (the current versions go to Recently deleted)</label>
      <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds="restore-cancel">Cancel</button>
        <button type="button" class="btn btn-primary btn-sm" data-ds="restore-apply"${busy('restore-apply')}>Restore selected</button></div>
      <p class="muted small">Adds what's missing; never deletes. A safety copy of this computer's database is made first.</p></div>`
      : `<div class="ds-preview"><p>Open <b>${esc(r.label)}</b></p><label class="field">Backup passphrase <input type="password" id="dsRestorePass" autocomplete="off"></label>
        <div class="row-tight"><button type="button" class="btn btn-ghost btn-sm" data-ds="restore-cancel">Cancel</button>
        <button type="button" class="btn btn-primary btn-sm" data-ds="restore-preview"${busy('restore-preview')}>Check and preview</button></div></div>`) : '';
  return `<p class="muted small">Encrypted on this computer with your passphrase (AES-256-GCM). A backup works without sync and without Google, and opens on Windows, Android and the Web app.</p>
    <div class="ds-cats">${Object.keys(s.categories).map((c) => `<label class="ds-cat"><input type="checkbox" data-ds-bcat="${esc(c)}" checked> ${esc(c)}</label>`).join('')}</div>
    <label class="field">Passphrase (10+ characters, or your Recovery Key) <input type="password" id="dsBackupPass" autocomplete="new-password"></label>
    <label class="field">Repeat passphrase <input type="password" id="dsBackupPass2" autocomplete="new-password"></label>
    ${s.connected ? '<label class="ds-cat"><input type="checkbox" id="dsBackupDrive"> Also upload to Drive/Abhify.AI/Backups (encrypted)</label>' : ''}
    <div class="row-tight"><button type="button" class="btn btn-primary btn-sm" data-ds="backup"${busy('backup')}>Create encrypted backup</button>
      <label class="btn btn-ghost btn-sm">Restore from a file…<input type="file" id="dsRestoreFile" accept=".abx" hidden></label>
      ${s.connected ? `<button type="button" class="btn btn-ghost btn-sm" data-ds="drive-list"${busy('drive-list')}>Backups on Drive</button>` : ''}</div>
    ${restore}<h5>Backup history (this computer)</h5>${list}${drive}`;
}

function privacyHtml(s, busy) {
  const devs = ds.devices ? Object.entries(ds.devices.others || {}) : [];
  return `<ul class="small">
      <li>Encryption: AES-256-GCM on this computer before upload. ${s.vault ? `Vault ${esc(s.vault.vault_id.slice(0, 8))}…, key version ${s.vault.epoch}.` : 'No vault yet.'}</li>
      <li>Automatic sync: <b>off</b> (Abhify.AI never syncs or uploads on its own).</li>
      <li>Synced (only what you tick): conversations and messages, personal memory, portable preferences. Never: AI models, model and hardware settings, API keys, passwords, Google tokens, vault keys, the Recovery Key.</li>
      <li>Google sees file names, sizes and times in Drive/Abhify.AI, never their content.</li>
      <li>Recovery Key: needed to add a device or after removing one. If every device and the Recovery Key are lost, synced data can't be decrypted by anyone, including Abhify. Backups use their own passphrase.</li></ul>
    <h5>Devices in this vault</h5><ul class="small"><li>${esc(ds.devices?.this?.name || 'This computer')} (this computer)</li>
      ${devs.map(([id, d]) => `<li>${esc(d.name || id)} · ${esc(d.platform || '')}${d.revoked ? ' · removed' : ` <button type="button" class="btn btn-ghost btn-sm" data-ds-revoke="${esc(id)}">Remove device…</button>`}</li>`).join('')}</ul>
    ${s.connected ? `<button type="button" class="btn btn-ghost btn-sm" data-ds="disconnect-delete"${busy('disconnect')}>Disconnect and delete sync data on Drive…</button>` : ''}`;
}

/* ---------------------------------------------------------------- actions */
async function pollConnect() {
  if (ds.polling) return;
  ds.polling = true;
  try {
    for (let i = 0; i < 200; i++) {                                    // up to ~10 minutes, only while this flow is waiting
      await new Promise((r) => setTimeout(r, 3000));
      const s = await call('/status').catch(() => null);
      if (!s) continue;
      ds.status = s; render();
      if (s.flow?.status !== 'waiting') {
        if (s.connected) { await act('connect', () => call('/connect/finish', { method: 'POST' }), (a) => `✓ Google Drive connected (${a.email || 'account'})`); await refresh(); }
        break;
      }
    }
  } finally { ds.polling = false; }
}

function selectedDeletes() { return [...document.querySelectorAll('[data-ds-del]:checked')].map((x) => x.dataset.dsDel); }

async function onClick(e) {
  const b = e.target.closest('[data-ds],[data-ds-resolve],[data-ds-restore],[data-ds-revoke],[data-ds-pick],[data-ds-fetch]');
  if (!b) return;
  const a = b.dataset.ds;
  if (b.dataset.dsResolve) {
    const [id, choice] = b.dataset.dsResolve.split(':');
    await act('resolve', () => call(`/conflicts/${id}`, { method: 'POST', body: { choice } }), 'Saved. Press Sync Now to send your choice to your other devices.');
    return refresh();
  }
  if (b.dataset.dsRestore) { await act('restore', () => call(`/deleted/${b.dataset.dsRestore}/restore`, { method: 'POST' }), 'Restored on this computer.'); return refresh(); }
  if (b.dataset.dsRevoke) {
    const rk = prompt('Removing a device changes the vault key so it can no longer read new data.\nEach of your other devices will ask for the Recovery Key once.\n\nEnter your Recovery Key to continue:');
    if (!rk) return;
    await act('revoke', () => call(`/devices/${b.dataset.dsRevoke}/revoke`, { method: 'POST', body: { recovery_key: rk } }), (r) => r.note);
    return refresh();
  }
  if (b.dataset.dsPick) { ds.restore = { name: b.dataset.dsPick, label: b.dataset.dsPick }; return render(); }
  if (b.dataset.dsFetch) { await act('fetch', () => call(`/backups/drive/${b.dataset.dsFetch}/fetch`, { method: 'POST' }), (r) => `Saved ${r.name} on this computer.`); return refresh(); }
  switch (a) {
    case 'connect': case 'reconnect': {
      const r = await act('connect', () => call('/connect', { method: 'POST' }));
      if (r?.auth_url) { toast('Google opened in your browser. Finish there.', 6000); pollConnect(); }
      return;
    }
    case 'disconnect':
      if (!confirm('Disconnect Google Drive on this computer?\n\nAbhify stops using your Drive and revokes its access. Files on Drive and on this computer are not touched.')) return;
      await act('disconnect', () => call('/disconnect', { method: 'POST', body: {} }), 'Disconnected.'); return refresh();
    case 'disconnect-delete': {
      const t = prompt('This moves Abhify\'s encrypted sync files in Drive/Abhify.AI/Sync to Google Drive\'s trash (recoverable there for 30 days), then disconnects.\nYour other devices will no longer be able to sync until a new vault is created.\n\nType DELETE SYNC DATA to confirm:');
      if (t !== 'DELETE SYNC DATA') return;
      await act('disconnect', () => call('/disconnect', { method: 'POST', body: { remove_remote: true, confirm: t } }), (r) => `Disconnected; ${r.trashed} sync file(s) moved to Drive's trash.`);
      return refresh();
    }
    case 'interop': ds.interop = await act('interop', () => call('/interop-check', { method: 'POST' })); return render();
    case 'probe': ds.vault = await act('probe', () => call('/vault')); return render();
    case 'create-vault': ds.created = await act('create-vault', () => call('/vault/create', { method: 'POST' })); return render();
    case 'print-rk': {
      const w = window.open('', '_blank', 'noopener=no,width=600,height=400');
      if (w) { w.document.write(`<title>Abhify.AI Recovery Key</title><body style="font-family:system-ui;padding:2rem"><h2>Abhify.AI Recovery Key</h2><p style="font:20px monospace">${esc(ds.created.recovery_key)}</p><p>Keep this safe. Anyone with it and access to your Google Drive can read your synced data.</p></body>`); w.document.close(); w.print(); }
      return;
    }
    case 'confirm-vault': {
      const r = await act('confirm-vault', () => call('/vault/confirm', { method: 'POST', body: { last_group: $('#dsRkConfirm').value } }), '✓ Vault created.');
      if (r) { ds.created = null; ds.vault = null; await refresh(); }
      return;
    }
    case 'join': { const r = await act('join', () => call('/vault/join', { method: 'POST', body: { recovery_key: $('#dsRkJoin').value } }), '✓ Joined the vault.'); if (r) await refresh(); return; }
    case 'preview': ds.preview = await act('preview', () => call('/preview', { method: 'POST' })); return render();
    case 'sync': case 'upload': case 'download': {
      const direction = a === 'sync' ? 'both' : a;
      const r = await act('sync', () => call('/apply', { method: 'POST', body: { direction, approve_deletes: selectedDeletes() } }),
        (x) => `Sync done: ${x.added} added, ${x.updated} updated, ${x.deleted} deleted, ${x.conflicts} conflicts, ${x.uploaded} uploaded.`);
      if (r) { ds.preview = null; await refresh(); }
      return;
    }
    case 'backup': {
      const p1 = $('#dsBackupPass').value, p2 = $('#dsBackupPass2').value;
      if (p1 !== p2) { ds.err = "The passphrases don't match."; return render(); }
      const categories = [...document.querySelectorAll('[data-ds-bcat]:checked')].map((x) => x.dataset.dsBcat);
      const r = await act('backup', () => call('/backups', { method: 'POST', body: { passphrase: p1, categories, to_drive: !!$('#dsBackupDrive')?.checked } }),
        (x) => `Encrypted backup saved: ${x.name}${x.drive ? ' (and uploaded to Drive)' : ''}`);
      if (r) await refresh();
      return;
    }
    case 'drive-list': ds.drive = (await act('drive-list', () => call('/backups/drive')))?.drive ?? ds.drive; return render();
    case 'restore-cancel': ds.restore = null; return render();
    case 'restore-preview': {
      const form = new FormData();
      form.append('passphrase', $('#dsRestorePass').value);
      if (ds.restore.file) form.append('file', ds.restore.file); else form.append('name', ds.restore.name);
      const p = await act('restore-preview', () => call('/restore/preview', { method: 'POST', form }));
      if (p) ds.restore.preview = p;
      return render();
    }
    case 'restore-apply': {
      const kinds = [...document.querySelectorAll('[data-ds-rkind]:checked')].map((x) => x.dataset.dsRkind);
      const replace = !!$('#dsReplace')?.checked;
      if (replace && !confirm('Replace records on this computer that differ from the backup?\nThe current versions are kept in Recently deleted.')) return;
      const r = await act('restore-apply', () => call('/restore/apply', { method: 'POST', body: { token: ds.restore.preview.token, kinds, replace_different: replace, confirm_replace: replace } }),
        (x) => `Restored: ${x.added} added, ${x.replaced} replaced, ${x.skipped} already here. Safety copy: ${x.safety_copy}`);
      if (r) { ds.restore = null; await refresh(); }
      return;
    }
    default:
  }
}

async function onChange(e) {
  const t = e.target;
  if (t.id === 'dsClientFile' && t.files[0]) {
    const text = await t.files[0].text();
    await act('client', () => call('/oauth-client', { method: 'PUT', body: { text } }), '✓ OAuth client saved on this computer (encrypted).');
    return refresh();
  }
  if (t.dataset?.dsKind) {
    const kinds = [...document.querySelectorAll('[data-ds-kind]:checked')].map((x) => x.dataset.dsKind).flatMap((k) => k === 'conversation' ? ['conversation', 'message'] : [k]);
    await act('kinds', () => call('/kinds', { method: 'PUT', body: { kinds } }));
    ds.preview = null;
    return refresh();
  }
  if (t.id === 'dsRestoreFile' && t.files[0]) { ds.restore = { file: t.files[0], label: t.files[0].name }; t.value = ''; return render(); }
}

export function bind() {
  const body = $('#dataSyncBody');
  if (!body || body.dataset.bound) return;
  body.dataset.bound = '1';
  body.addEventListener('click', onClick);
  body.addEventListener('change', onChange);
}
