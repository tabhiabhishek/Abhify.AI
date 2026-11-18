/* =====================================================================
   Abhify.AI: frontend logic (vanilla JS, no build step)

   Sections
     1. Config & state           6. MCQ quiz
     2. Utilities                7. Chat + knowledge base upload
     3. API client + offline     8. Voice (Web Speech API / Whisper fallback)
     4. News                     9. Progress, settings, PWA install
     5. Interview questions     10. Boot
    11. Setup wizard, memory manager, system & data (v2)

   Browser storage holds only preferences, caches and the offline queue.
   Progress, conversations and memories live in the backend's SQLite database.
   ===================================================================== */
'use strict';

/* ---------- storage helpers (defined first: state below depends on them) ---------- */
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch { /* private mode / quota */ } },
  json: (k, fallback) => { try { return JSON.parse(localStorage.getItem(k)) ?? fallback; } catch { return fallback; } },
  setJson: (k, v) => store.set(k, JSON.stringify(v)),
};
function newId() { return (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()).replace(/-/g, ''); }

/* ---------- 1. CONFIG & STATE ---------- */
const LS = {                                   // localStorage keys
  api: 'abhify_api', name: 'abhify_name', user: 'abhify_user', whisper: 'abhify_whisper', token: 'abhify_token',
  news: 'abhify_cache_news', questions: 'abhify_cache_questions', mcqs: 'abhify_cache_mcqs',
  progress: 'abhify_progress', queue: 'abhify_queue', thread: 'abhify_thread',
  migrated: 'abhify_migrated_v1',
  engine: 'abhify_engine', deviceThread: 'abhify_device_thread',   // on-device mode (Abhify Lite)
  threadModels: 'abhify_thread_models',        // dev.8: chat model chosen per conversation (this browser only)
};

/** One-time, idempotent copy of v1 ("aitp_*") browser data to the new keys.
 *  Never overwrites a value that already exists under the new key. */
(function migrateBrowserStorage() {
  if (store.get(LS.migrated)) return;
  for (const [k, newKey] of Object.entries(LS)) {
    if (k === 'migrated') continue;
    const oldVal = store.get(newKey.replace(/^abhify_/, 'aitp_'));
    if (oldVal !== null && store.get(newKey) === null) store.set(newKey, oldVal);
  }
  store.set(LS.migrated, new Date().toISOString());
})();

const CATEGORIES = [
  ['all', 'All'], ['python', 'Python'], ['ml', 'ML'], ['dl', 'Deep Learning'], ['nlp', 'NLP'],
  ['cv', 'Computer Vision'], ['llm', 'LLMs & RAG'], ['mlops', 'MLOps'], ['ai', 'AI Fundamentals'],
];

const state = {
  apiBase: null,            // resolved backend URL ('' = same origin)
  online: false,
  userId: store.get(LS.user) || newId(),
  userName: ['there', 'Abhi'].includes(store.get(LS.name)) ? '' : (store.get(LS.name) || ''),   // 5.6: only a configured name
  threadId: store.get(LS.thread) || null,
  usePrivateWhisper: store.get(LS.whisper) === '1',
  category: 'all',
  mcqCategory: 'all',
  questions: store.json(LS.questions, []),
  quiz: { items: [], index: 0, answers: {} },   // answers[i] = chosen option index
  progress: store.json(LS.progress, { mcq_total: 0, mcq_correct: 0, interview_viewed: 0, streak_days: 0 }),
  busy: { news: false, questions: false, mcq: false, chat: false },
  ready: Promise.resolve(),   // resolves after the first backend connection check
  remote: !['localhost', '127.0.0.1', '[::1]', ''].includes(location.hostname),   // refined by /health
  everConnected: false,       // used to tell "connection lost" from "never connected"
  engine: store.get(LS.engine) === 'device' ? 'device' : 'pc',   // where the AI runs: 'pc' (default) or 'device'
  device: { support: null, downloaded: false, ready: false, loading: null, progress: null, error: null },
  // dev.8 chat model picker: what /models reports (installed Ollama chat models) and this chat's choice (null = default)
  models: { list: [], def: null, ollama: null, error: null, locked: false, loaded: false, loading: false, forbidden: false },
  chatModel: null,
};
store.set(LS.user, state.userId);

/* ---------- 2. UTILITIES ---------- */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/* 6.1 lightweight code highlighting (no library): comments, strings, numbers, keywords and calls for common languages.
   Works on the raw code and escapes every piece, so it can never inject markup. Unknown languages are shown plain. */
const HL_SETS = {
  py: 'and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self',
  js: 'async await break case catch class const continue default delete do else export extends false finally for from function if import in instanceof let new null of return static super switch this throw true try typeof undefined var void while yield interface type enum implements',
  c: 'abstract break case catch char class const continue default do double else enum extends false final finally float for fun if implements import in int interface internal is long new null object override package private protected public return short static String struct super switch this throw true try val var void when while func go let mut fn impl use pub match self Self namespace using',
  sql: 'select from where join inner left right full outer on group by order having limit offset insert into values update set delete create table view index primary key foreign references as and or not null is in between like distinct union all case when then else end count sum avg min max with drop alter add asc desc',
  sh: 'if then else elif fi for do done while until case esac function in return export local echo cd ls sudo pip python npm git set unset source',
};
const HL_ALIAS = { python: 'py', py: 'py', javascript: 'js', js: 'js', jsx: 'js', ts: 'js', tsx: 'js', typescript: 'js', json: 'js', node: 'js',
  java: 'c', kotlin: 'c', kt: 'c', c: 'c', cpp: 'c', cc: 'c', h: 'c', cs: 'c', csharp: 'c', go: 'c', golang: 'c', rust: 'c', rs: 'c', swift: 'c', dart: 'c', scala: 'c',
  sql: 'sql', mysql: 'sql', postgres: 'sql', postgresql: 'sql', sqlite: 'sql', bash: 'sh', sh: 'sh', shell: 'sh', zsh: 'sh', console: 'sh', powershell: 'sh', ps1: 'sh', ps: 'sh', yaml: 'sh', yml: 'sh', toml: 'sh', dockerfile: 'sh' };
const HL_KW = Object.fromEntries(Object.entries(HL_SETS).map(([k, v]) => [k, new Set(v.split(' '))]));
const HL_RE = {
  py: /(#[^\n]*)|("{3}[\s\S]*?"{3}|'{3}[\s\S]*?'{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d[\d_]*(?:\.\d+)?\b)|([A-Za-z_]\w*)/g,
  sh: /(#[^\n]*)|("(?:\\.|[^"\\])*"|'[^'\n]*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][\w-]*)/g,
  sql: /(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:''|[^'])*'|"(?:[^"])*")|(\b\d+(?:\.\d+)?\b)|([A-Za-z_]\w*)/g,
  js: /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`)|(\b\d[\d_]*(?:\.\d+)?\b)|([A-Za-z_$][\w$]*)/g,
  c: /(\/\/[^\n]*|\/\*[\s\S]*?\*\/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d[\d_]*(?:\.\d+)?[fFlLuU]?\b)|([A-Za-z_]\w*)/g,
};
function highlight(raw, lang) {
  const fam = HL_ALIAS[String(lang || '').toLowerCase()];
  if (!fam) return esc(raw);
  const re = new RegExp(HL_RE[fam].source, 'g'), kw = HL_KW[fam];
  let out = '', last = 0, m;
  while ((m = re.exec(raw))) {
    out += esc(raw.slice(last, m.index)); last = re.lastIndex;
    const t = m[0];
    if (m[1]) out += `<span class="tk-c">${esc(t)}</span>`;
    else if (m[2]) out += `<span class="tk-s">${esc(t)}</span>`;
    else if (m[3]) out += `<span class="tk-n">${esc(t)}</span>`;
    else if (kw.has(fam === 'sql' ? t.toLowerCase() : t)) out += `<span class="tk-k">${esc(t)}</span>`;
    else if (raw[last] === '(') out += `<span class="tk-f">${esc(t)}</span>`;
    else out += esc(t);
  }
  return out + esc(raw.slice(last));
}
const unesc = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
function codeBlockHtml(code, lang) {
  return `<div class="code-block"><div class="code-head"><span>${esc(lang || 'code')}</span>`
    + `<button type="button" class="code-copy" data-copy="code" aria-label="Copy code">Copy</button></div>`
    + `<pre><code${lang ? ` class="lang-${esc(lang)}"` : ''}>${highlight(code, lang)}</code></pre></div>`;
}

/** Tiny, safe Markdown renderer for chat answers (escape first, then format). 6.1: headings, simple tables, and code blocks
 *  with the language, a Copy button and highlighting. */
function md(text) {
  const blocks = [];
  let s = esc(text).replace(/```([\w+#.-]*)[^\n]*\n?([\s\S]*?)```/g, (_, lang, code) => {
    blocks.push(codeBlockHtml(unesc(code.replace(/^\n+|\s+$/g, '')), lang)); return `\u0000${blocks.length - 1}\u0000`;
  });
  s = s
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>')
    .replace(/\[([WL]\d+)\]\((?!https?:\/\/)[^)\s]*\)/g, '[$1]')   // 6.2: a model's "[W4](#)" is just the citation (the real link is the chip)
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener">$2</a>');
  // headings, tables, lists + paragraphs
  const out = []; let list = null; const lines = s.split('\n');
  const cells = (l) => l.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());
  for (let k = 0; k < lines.length; k++) {
    const line = lines[k];
    const ul = line.match(/^\s*[-*•]\s+(.*)/), ol = line.match(/^\s*\d+[.)]\s+(.*)/), h = line.match(/^\s*(#{1,4})\s+(.*)/);
    if (ul || ol) {
      const type = ul ? 'ul' : 'ol';
      if (list !== type) { if (list) out.push(`</${list}>`); out.push(`<${type}>`); list = type; }
      out.push(`<li>${(ul || ol)[1]}</li>`);
      continue;
    }
    if (list) { out.push(`</${list}>`); list = null; }
    if (h) { out.push(`<h4 class="md-h">${h[2].replace(/#+\s*$/, '')}</h4>`); continue; }
    if (/^\s*\|.*\|\s*$/.test(line) && /^\s*\|?\s*:?-{2,}/.test(lines[k + 1] || '')) {          // | a | b | then |---|---|
      const head = cells(line); const rows = []; k += 2;
      while (k < lines.length && /^\s*\|.*\|\s*$/.test(lines[k])) rows.push(cells(lines[k++]));
      k--;
      out.push(`<div class="md-table"><table><thead><tr>${head.map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody>`
        + rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('') + '</tbody></table></div>');
      continue;
    }
    if (/^\u0000\d+\u0000$/.test(line.trim())) out.push(line.trim());   // a code block stands alone (no <p> around a <div>)
    else if (line.trim()) out.push(`<p>${line}</p>`);
  }
  if (list) out.push(`</${list}>`);
  return out.join('').replace(/\u0000(\d+)\u0000/g, (_, i) => blocks[i]);
}

/** 6.1: copy text (Clipboard API, or the older execCommand fallback on plain-http phone connections). */
async function copyText(t) {
  try { await navigator.clipboard.writeText(t); return true; } catch { /* fall back */ }
  const ta = document.createElement('textarea');
  ta.value = t; ta.setAttribute('readonly', ''); ta.style.cssText = 'position:fixed;opacity:0;left:-9999px';
  document.body.appendChild(ta); ta.select();
  let ok = false; try { ok = document.execCommand('copy'); } catch { /* not allowed */ }
  ta.remove(); return ok;
}

function toast(msg, ms = 2800) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}
function stars(diff) { const n = { easy: 2, medium: 3, hard: 5 }[diff] || 3; return '★'.repeat(n) + '☆'.repeat(5 - n); }
/** 5.6: the configured name, or '' (then greetings stay neutral). */
function displayName() { const n = (state.userName || '').trim(); return n && n !== 'there' ? n : ''; }
function setNameUI() { const n = displayName(), el = document.getElementById('userName'); if (el) el.textContent = n ? `Hi, ${n}` : 'Your local AI workspace'; }
/** 5.6 shared empty / error state (one look everywhere). kind: 'empty' | 'error'. Text is escaped here. */
function stateHtml(kind, title, text = '', compact = false) {
  return `<div class="state ${kind === 'error' ? 'error' : ''}${compact ? ' compact' : ''}" role="${kind === 'error' ? 'alert' : 'status'}">`
    + `${compact ? '' : `<span class="state-ico" aria-hidden="true">${kind === 'error' ? '!' : '∅'}</span>`}<b>${esc(title)}</b>${text ? `<span>${esc(text)}</span>` : ''}</div>`;
}
function skeleton(n = 3) { return Array.from({ length: n }, () => '<div class="skeleton"></div>').join(''); }
function timeAgo(ts) {
  const m = Math.round((Date.now() - ts) / 60000);
  return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`;
}

/* ---------- 3. API CLIENT + OFFLINE DETECTION ---------- */
/** Candidate backends: saved URL > same origin (if served by FastAPI) > localhost:8000. */
function apiCandidates() {
  const saved = (store.get(LS.api) || '').trim().replace(/\/+$/, '');
  if (saved) return [saved];
  const list = [];
  if (location.protocol.startsWith('http')) list.push('');      // same origin
  list.push('http://localhost:8000');
  return [...new Set(list)];
}

/** Adds the companion access token when this device talks to another computer (Mode B). */
function authHeaders(h = {}) {
  const t = store.get(LS.token);
  return t ? { ...h, Authorization: `Bearer ${t}` } : h;
}

async function api(path, { method = 'GET', body, form, timeout = 120000 } = {}) {
  if (state.apiBase === null) throw new Error('offline');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(state.apiBase + path, {
      method,
      signal: ctrl.signal,
      headers: authHeaders(body ? { 'Content-Type': 'application/json' } : {}),
      body: form || (body ? JSON.stringify(body) : undefined),
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401) recheckSoon();                         // token missing / revoked: show "Not paired"
    if (!res.ok) throw new Error(data.detail || `HTTP ${res.status}`);
    return data;
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('The request timed out');
    if (e instanceof TypeError) { setOnline(false); throw new Error('offline'); }  // network failure
    throw e;
  } finally { clearTimeout(timer); }
}

/** POST [body] to a newline-delimited JSON stream (e.g. /chat/stream) and call onEvent for every event as it arrives.
 *  Same base URL and credentials as api(). Throws an Error with .status when the server refuses the stream. */
async function apiStream(path, body, onEvent, signal) {
  if (state.apiBase === null) throw new Error('offline');
  let res;
  try {
    res = await fetch(state.apiBase + path, { method: 'POST', signal, headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(body) });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    setOnline(false); throw new Error('offline');
  }
  if (!res.ok || !res.body) {
    const data = await res.json().catch(() => ({}));
    if (res.status === 401) recheckSoon();
    throw Object.assign(new Error(data.detail || `HTTP ${res.status}`), { status: res.status });
  }
  const reader = res.body.getReader(), dec = new TextDecoder();
  let buf = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let nl;
    while ((nl = buf.indexOf('\n')) >= 0) { const line = buf.slice(0, nl).trim(); buf = buf.slice(nl + 1); if (line) onEvent(JSON.parse(line)); }
  }
  if (buf.trim()) onEvent(JSON.parse(buf));
}

/** Probe /health on each candidate. The AI counts as usable only when the model is ready AND, on a
 *  phone, this device is paired (the backend reports `authorized` for the token we send). */
async function checkConnection() {
  if (state.engine === 'device') return checkDevice();
  for (const base of apiCandidates()) {
    try {
      const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), 4000);
      const res = await fetch(base + '/health', { signal: ctrl.signal, cache: 'no-store', headers: authHeaders() });
      clearTimeout(t);
      if (!res.ok) continue;
      const h = await res.json();
      state.apiBase = base;
      if (typeof h.remote === 'boolean') state.remote = h.remote;
      state.everConnected = true;
      setOnline(Boolean(h.ollama && h.llm_ready && h.authorized !== false), h);
      return state.online;
    } catch { /* try next candidate */ }
  }
  state.apiBase = state.apiBase ?? null;
  setOnline(false);
  return false;
}

let recheckTimer = null;
function recheckSoon() { clearTimeout(recheckTimer); recheckTimer = setTimeout(checkConnection, 300); }

/** One honest status line. Inference always runs on the computer, never on the phone. Returns
 *  [status text, dot class, banner text (shown when the AI isn't usable)]. */
function connectionStatus(on, h) {
  const paired = Boolean(store.get(LS.token));
  if (!h) {
    if (!navigator.onLine) return ['Offline mode', 'off', '📴 <strong>Offline mode</strong>: this device has no network. Showing saved content.'];
    if (state.remote) {
      return paired
        ? [state.everConnected ? 'Connection to your computer lost · retrying' : 'Paired · your computer isn\'t reachable', 'off',
          '📴 <strong>Your computer isn\'t reachable.</strong> Check that it\'s on, Abhify.AI is running, and both devices are on the same network or Tailscale. Showing saved content.']
        : ['Not paired', 'off', '🔗 <strong>Not paired.</strong> Open the pairing link from your computer, or enter its address and access token in Settings.'];
    }
    return [state.everConnected ? 'Connection lost · retrying' : 'Offline', 'off',
      '📴 <strong>Offline mode</strong>: showing saved content. AI features return when the backend is reachable.'];
  }
  if (h.remote) {
    if (h.authorized === false) {
      if (h.companion_enabled === false) return ['Companion access is off on your computer', 'off',
        '🔒 <strong>Companion access is turned off</strong> on your computer. Turn it on there (Settings → System & data).'];
      if (!paired && h.registration === 'public') return ['Not paired', 'off',
        '🔓 <strong>This computer accepts new guest devices.</strong> <button class="btn btn-sm btn-primary" data-join="1">Join as guest</button> Guests can use news, quizzes and a private guest chat; they never see the owner\'s data.'];
      return [paired ? 'Not paired · access token rejected' : 'Not paired', 'off',
        paired ? '🔗 <strong>This device\'s access was rejected</strong> (revoked, expired or replaced). Ask the owner for a new invitation.'
          : '🔗 <strong>Not paired.</strong> Open the invitation link from the computer\'s owner, or enter an access token in Settings.'];
    }
    if (!h.ollama) return ['Computer connected · Ollama not running', 'warn',
      '⚠️ <strong>Your computer is connected, but Ollama isn\'t running there.</strong> Start Ollama on the computer.'];
    if (!h.llm_ready) return ['Computer connected · AI model not installed', 'warn',
      '⚠️ <strong>Your computer is connected, but its AI model isn\'t installed.</strong> Finish setup on the computer.'];
    return [h.role === 'member' ? 'AI ready · runs on the owner\'s computer (guest)' : 'AI ready · runs on your computer', 'on', ''];
  }
  if (on) return [h.internet ? 'Online · AI ready' : 'Local AI ready · no internet', 'on', ''];
  return [h.ollama ? 'Backend up · model not installed' : 'Backend up · Ollama offline', 'off',
    '📴 <strong>Local AI unavailable</strong>: showing saved content. Open Settings → System & data → Models & setup.'];
}

let lastStatusKey = null;
function setOnline(on, health) {
  if (state.engine === 'device') { renderDevice(); return; }
  const was = state.online;
  state.online = on;
  state.health = health || null;
  const [text, dot, banner] = connectionStatus(on, health || null);
  // 6.1: a failed request reports "offline" again and again (every Home card's request does). Re-render Home only when the
  // status really changed or fresh health arrived; before, Home's own failing requests re-rendered Home, which sent the
  // requests again: an endless, growing request loop (gigabytes of memory) whenever the backend stopped with Home open.
  const key = `${on}|${text}|${banner}`, changed = key !== lastStatusKey;
  lastStatusKey = key;
  document.body.classList.toggle('guest', Boolean(health && health.role === 'member'));   // cosmetic; the server enforces
  $('#statusDot').className = 'dot ' + dot;
  $('#statusText').textContent = text;
  $('#offlineText').innerHTML = banner;
  $('#offlineBanner').classList.toggle('hidden', on);
  if (health && health.knowledge_chunks != null) $('#kbInfo').textContent = `📚 ${health.knowledge_chunks} chunks`;
  if (on && !was) { flushQueue(); refreshProgress(); if (!state.remote) loadModels().then(() => current === 'home' && renderHome()); else { state.models.forbidden = true; state.models.loaded = true; renderModelChip(); } }
  if (current === 'home' && (changed || health)) renderHome();
  updateOrb();
}

/* ---------- 4. NEWS ---------- */
async function loadNews(refresh = false) {
  if (state.busy.news) return;
  state.busy.news = true;
  const list = $('#newsList'), meta = $('#newsMeta'), btn = $('#newsRefresh');
  const cached = store.json(LS.news, null);
  if (cached?.items?.length) renderNews(cached.items, `Saved ${timeAgo(cached.at)}`);
  else list.innerHTML = skeleton(4);
  btn.disabled = true;

  try {
    if (!state.online) throw new Error('offline');
    meta.textContent = 'Fetching & summarizing with AI…';
    const data = await api(`/news?limit=8${refresh ? '&refresh=true' : ''}`, { timeout: 240000 });
    store.setJson(LS.news, { at: Date.now(), items: data.items });
    renderNews(data.items, 'Live · AI-summarized');
  } catch (e) {
    if (cached?.items?.length) renderNews(cached.items, `Offline · saved ${timeAgo(cached.at)}`);
    else renderNews(window.MOCK.news, 'Offline · sample stories');
    if (refresh && e.message !== 'offline') toast(`News unavailable: ${e.message}`);
  } finally { state.busy.news = false; btn.disabled = false; }
}

function renderNews(items, label) {
  $('#newsMeta').textContent = label;
  $('#newsList').innerHTML = items.map((n) => `
    <article class="card">
      <h3 class="news-title">${n.url ? `<a href="${esc(n.url)}" target="_blank" rel="noopener">${esc(n.title)}</a>` : esc(n.title)}</h3>
      <p class="news-summary">${esc(n.summary)}</p>
      <button class="link-btn" data-action="expand">Read more</button>
      <div class="news-meta">
        <span class="muted">${esc(n.time || '')}</span>
        <span>
          <span class="source">${esc(n.source)}</span>
          ${n.isBreaking ? '<span class="breaking">🔥 BREAKING</span>' : ''}
        </span>
      </div>
    </article>`).join('') || stateHtml('empty', 'No news right now');
}

/* ---------- 4b. NEWS CENTER (dev.8) ----------
   /news/feed, /news/search and /news/ai (backend news_center.py): publisher RSS feeds and Google News by category, region
   and search, cached in News's own folder. Nothing here touches memory, knowledge or chat history; "Save" keeps a
   reading list in this browser only. If the News Center isn't available (older backend, guest device, offline with no
   cache) the classic /news view below is used. */
const NEWS_ACC = { foryou: 'chat', ai: 'chat', local: 'career', india: 'career', world: 'docs', technology: 'docs', business: 'know',
  startups: 'know', sports: 'study', health: 'study', environment: 'study', science: 'system', education: 'system' };
const news = { cats: [], prefs: null, cat: 'foryou', result: null, query: '', saved: store.json('abhify_news_saved', []) };
async function newsCenterInit() {
  try {
    const r = await api('/news/categories', { timeout: 8000 });
    news.cats = r.categories; news.prefs = r.prefs; markNewsLang();
    $('#newsCats').innerHTML = news.cats.map((c) => `<button class="chip${c.id === news.cat ? ' active' : ''}" role="tab" data-cat="${esc(c.id)}" data-acc="${NEWS_ACC[c.id] || 'memory'}" aria-selected="${c.id === news.cat}">${esc(c.label)}</button>`).join('');
    return true;
  } catch { return false; }
}
/* Refresh rules: one refresh at a time (a second tap or pull while one runs is ignored); only the latest request may
   update the screen (a slow answer for a category you left is dropped); a failed refresh keeps the stories on screen
   and says so. It fetches stories only: no AI briefing is made, and Listen is not touched. */
let newsSeq = 0;
async function newsOpen(cat = news.cat, refresh = false) {
  if (refresh && news.refreshing && cat === news.cat) return news.refreshing;
  news.cat = cat; news.query = '';
  $$('#newsCats .chip').forEach((b) => { const on = b.dataset.cat === cat; b.classList.toggle('active', on); b.setAttribute('aria-selected', on); });
  if (!news.cats.length && !(await newsCenterInit())) return loadNews(refresh);          // classic view
  const seq = ++newsSeq, before = new Set((news.result?.articles || []).map((a) => a.id));
  $('#newsMeta').textContent = refresh ? 'Refreshing…' : 'Getting the latest stories…';
  if (!news.result) $('#newsList').innerHTML = skeleton(4);
  $('#newsRefresh').disabled = true;
  const run = (async () => {
    try {
      const r = await api(`/news/feed?category=${encodeURIComponent(cat)}${refresh ? '&refresh=true' : ''}`, { timeout: 60000 });
      if (seq !== newsSeq) return;                                                       // a newer request owns the screen
      const failed = (r.from_cache && (r.failed || []).length > 0) || !r.articles.length;   // a recent cached copy is not a failure
      if (refresh && failed && news.result?.articles?.length) {
        $('#newsMeta').textContent = `⚠ ${r.problem || "Couldn't reach the news sources"}: the previous stories stay. Pull down or tap Refresh to try again.`;
        return;
      }
      renderNewsCenter(r);
      if (refresh) $('#newsMeta').textContent = failed ? `⚠ ${r.problem || "Couldn't reach the news sources"}.`
        : `Updated just now · ${r.articles.length} stories, ${r.articles.filter((a) => !before.has(a.id)).length} new` + (r.failed?.length ? ` · not reachable: ${r.failed.join(', ')}` : '');
    } catch (e) {
      if (seq !== newsSeq) return;
      if (!news.result) loadNews(refresh);
      else $('#newsMeta').textContent = `⚠ News: ${e.message === 'offline' ? 'no connection to the app' : e.message}. The previous stories stay.`;
    } finally { if (seq === newsSeq) { $('#newsRefresh').disabled = false; news.refreshing = null; } }
  })();
  if (refresh) news.refreshing = run;
  return run;
}
async function newsSearch(q) {
  q = q.trim(); if (!q) return newsOpen(news.cat);
  news.query = q; $('#newsMeta').textContent = `Searching for "${q}"…`; $('#newsList').innerHTML = skeleton(3);
  try { renderNewsCenter(await api(`/news/search?q=${encodeURIComponent(q)}`, { timeout: 60000 })); } catch (e) { toast(`Search failed: ${e.message}`); }
}
/** 5.6: a story's image is keyed to its own id (data-for) and built only from that story's image_url; a missing and a
 *  broken image get the same placeholder (class "nimg ph"), so no story ever shows another story's picture. */
function newsImg(x, cls) {
  return x.image_url ? `<img class="${cls}" data-for="${esc(x.id)}" src="${esc(x.image_url)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="newsImgFallback(this)">`
    : `<div class="${cls} ph" data-for="${esc(x.id)}" aria-hidden="true"></div>`;
}
function newsImgFallback(el) {
  const ph = document.createElement('div');
  ph.className = `${String(el.className).split(' ')[0]} ph`; ph.setAttribute('data-for', el.getAttribute('data-for') || ''); ph.setAttribute('aria-hidden', 'true');
  el.replaceWith(ph);
}
function newsCatLabel(id) { return ((news.cats || []).find((c) => c.id === id) || {}).label || ''; }
function newsTime(ms) { return ms ? `${timeAgo(ms)} · ${new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}` : 'date not given'; }
function renderNewsCenter(r) {
  news.result = r;
  const a = r.articles || [];
  $('#newsMeta').textContent = news.query ? `Results for "${news.query}" · last 7 days, newest first (check each date)`
    : !r.retrieved_at ? 'Latest stories from the publishers' : r.from_cache && (r.failed || []).length ? `Saved ${timeAgo(r.retrieved_at)} · offline copy`
    : r.from_cache ? `Updated ${timeAgo(r.retrieved_at)} · saved copy` : `Updated ${timeAgo(r.retrieved_at)}`;
  const acc = (x) => NEWS_ACC[x.category] || 'memory';
  const card = (x, featured) => `<article class="ncard${featured ? ' featured' : ''}" data-acc="${acc(x)}" data-id="${esc(x.id)}">
      ${newsImg(x, 'nimg')}
      <div class="nbody">
        <span class="nmeta"><span class="npub">${esc(x.publisher)}</span><span>· ${newsTime(x.published_at)}</span>${newsCatLabel(x.category) ? `<span class="ncat">· ${esc(newsCatLabel(x.category))}</span>` : ''}${x.provider === 'google-news' ? '<span>· via Google News</span>' : ''}</span>
        <h3>${esc(x.title)}</h3>
        ${x.summary ? `<p>${esc(x.summary)}</p>` : ''}
        <div class="nacts">
          <button class="btn btn-ghost btn-sm" data-nact="read">Read in app</button>
          <button class="act" data-nact="summarize">Summarize</button><button class="act" data-nact="explain">Explain</button>
          <button class="act" data-nact="save">${news.saved.some((s) => s.id === x.id) ? 'Saved' : 'Save'}</button><button class="act" data-nact="share">Share</button>
        </div>
      </div></article>`;
  const feat = a.find((x) => x.image_url);
  $('#newsList').innerHTML = (r.problem && !a.length) ? stateHtml('error', "Couldn't load the news", r.problem)
    : a.length ? (feat ? card(feat, true) : '') + a.filter((x) => x !== feat).map((x) => card(x, false)).join('')
    : stateHtml('empty', 'No stories right now', 'Try another category or search.');
  $('#newsFoot').textContent = (r.failed && r.failed.length ? `Not reachable right now: ${r.failed.join(', ')}. ` : '') + (a.length
    ? 'Headlines and descriptions come from each publisher (or Google News); times are their publication times. Read more opens the original story. News is never added to your memory.' : '');
  $('#newsBrief').classList.toggle('hidden', !a.length);
}
async function newsAi(mode, articleId) {
  const box = $('#newsAi'); box.classList.remove('hidden');
  if (reader.owner === 'news') reader.stop();                          // new content: the old one stops being read
  news.audioText = '';
  box.innerHTML = '<span class="typing"><span></span><span></span><span></span></span> Writing from the headlines and descriptions…';
  $('#newsOrb').dataset.state = 'thinking';
  try {
    const body = { mode, article_id: articleId || null };
    if (news.query) body.query = news.query; else body.category = news.cat;
    if (state.chatModel) body.model = state.chatModel;
    const r = await api('/news/ai', { method: 'POST', body, timeout: 240000 });
    const title = mode === 'briefing' ? `Briefing from ${r.articles.length} stories` : mode === 'explain' ? 'Explained simply' : 'Summary';
    news.audioText = `${title}.\n\n${r.text}`;                          // Listen reads this text: no new AI request
    box.innerHTML = `<div class="news-ai-head"><b>${title}</b>
      <button class="mini-btn" data-nact="close-ai" aria-label="Close">✕</button></div>${newsAudioBar()}${md(r.text)}
      <p class="muted small">${esc(r.label)} Model: ${esc(r.model || 'default')}.</p>
      <ol class="news-src">${r.articles.map((x) => `<li><a href="${esc(x.url)}" target="_blank" rel="noopener noreferrer">${esc(x.title)}</a> <span class="muted small">${esc(x.publisher)} · ${newsTime(x.published_at)}</span></li>`).join('')}</ol>`;
  } catch (e) { box.innerHTML = `<p class="small err">${esc(e.message)}</p>`; }
  finally { $('#newsOrb').dataset.state = 'idle'; renderNewsAudio(); }
}
/* News audio: Listen reads the briefing (or summary) already on screen with the browser's own speech synthesis.
   Nothing is generated again and nothing is downloaded; one item (bullet) at a time, with pause, skip and speed. */
function newsAudioBar() {
  if (!reader.ok) return '<p class="small muted news-audio-off">🔇 Read aloud isn\'t available in this browser.</p>';
  return `<div class="news-audio" role="group" aria-label="Listen to this briefing">
    <button class="btn btn-primary btn-sm" data-naud="toggle"></button>
    <button class="mini-btn" data-naud="prev" aria-label="Previous item" title="Previous item">⏮</button>
    <button class="mini-btn" data-naud="next" aria-label="Next item" title="Next item">⏭</button>
    <button class="mini-btn" data-naud="stop" aria-label="Stop" title="Stop">■</button>
    <select class="select select-sm" data-naud="rate" aria-label="Reading speed">${[0.8, 1, 1.25, 1.5].map((x) => `<option value="${x}" ${x === reader.rate ? 'selected' : ''}>${x}×</option>`).join('')}</select>
    <span class="small news-audio-status" data-naud="status" role="status" aria-live="polite"></span></div>`;
}
function renderNewsAudio() {
  const bar = $('#newsAi .news-audio'); if (!bar) return;
  const mine = reader.owner === 'news', st = mine ? reader.state : 'idle';
  const label = { playing: ['⏸ Pause', 'Pause reading'], paused: ['▶ Resume', 'Resume reading'], ended: ['⟲ Replay', 'Listen again from the start'] }[st] || ['🔊 Listen', 'Listen to this'];
  const t = bar.querySelector('[data-naud=toggle]'); t.textContent = label[0]; t.setAttribute('aria-label', label[1]);
  bar.querySelectorAll('[data-naud=prev],[data-naud=next],[data-naud=stop]').forEach((b) => { b.disabled = !mine || !['playing', 'paused'].includes(st); });
  bar.dataset.state = st;
  bar.querySelector('[data-naud=status]').textContent = !mine ? '' : st === 'error' ? `⚠ ${reader.error}`
    : st === 'ended' ? 'Finished.' : ['playing', 'paused'].includes(st) ? `${st === 'paused' ? 'Paused at' : 'Reading'} item ${reader.item() + 1} of ${reader.items()}` : '';
}
function newsAudio(action, value) {
  if (action === 'toggle') {
    if (reader.owner !== 'news' || !reader.parts.length) {
      const parts = speechChunks(news.audioText);
      if (!parts.length) { toast('There is nothing to read yet.'); return; }
      reader.load(parts, 'news'); reader.play(0); return;
    }
    reader.toggle(); return;
  }
  if (reader.owner !== 'news') return;
  if (action === 'stop') reader.stop();
  if (action === 'prev') reader.skip(-1);
  if (action === 'next') reader.skip(1);
  if (action === 'rate') reader.setRate(+value);
}
/* In-app reading. Each story opens in its own reader (its id, title, URL, summary and Read more stage); the state belongs
   to that story only and resets when another opens. A slow summary for a story you left never replaces the one shown.
   Read more: the 1st tap shows the story's own paragraphs (or says there are none), the 2nd opens the original, exactly
   that story's https link, in a new tab. Opening a story never opens the site, refreshes News or makes a briefing. */
const articleView = { id: null, stage: 0, seq: 0, data: null, pushed: false };
function articleUrlOk(u) { try { const x = new URL(u); return x.protocol === 'https:' && !!x.hostname; } catch { return false; } }
async function openArticle(id, retry = false) {
  const a = news.result?.articles.find((x) => x.id === id);
  if (!a) { toast('That story isn\'t in the list any more. Refresh News and try again.'); return; }
  if (articleView.id !== id) { if (reader.owner === 'news') reader.stop(); Object.assign(articleView, { id, stage: 0, data: null }); }
  const seq = ++articleView.seq;
  openSheet('newsReaderSheet');
  if (!articleView.pushed) { history.pushState({ abhifyReader: id }, ''); articleView.pushed = true; }
  renderArticle(a, null, true);
  try {
    const q = state.chatModel ? `?model=${encodeURIComponent(state.chatModel)}` : '';
    const d = await api(`/news/article/${encodeURIComponent(id)}${q}`, { timeout: 180000 });
    if (seq !== articleView.seq || articleView.id !== id) return;           // you opened another story meanwhile
    articleView.data = d; renderArticle(a, d, false);
  } catch (e) {
    if (seq !== articleView.seq || articleView.id !== id) return;
    renderArticle(a, { summary: null, summary_error: e.message === 'offline' ? 'The summary needs the Abhify.AI app; the description is shown.' : e.message, paragraphs: [], full_text: false }, false);
  }
}
function renderArticle(a, d, loading) {
  const st = articleView.stage, paras = d?.paragraphs || [];
  const summary = d?.summary ? md(d.summary) : `<p>${esc(a.summary || 'No description was given for this story.')}</p>`;
  const more = st >= 1 ? (paras.length ? paras.map((p) => `<p>${esc(p)}</p>`).join('')
    : `<p class="muted">There is no more text for this story here${d?.fetch_note ? `: ${esc(d.fetch_note)}` : '.'} Tap Read more again to open the original article.</p>`) : '';
  $('#newsReader').innerHTML = `<article class="news-read" data-acc="docs" data-article="${esc(a.id)}">
    <div class="task-top"><button class="btn btn-ghost btn-sm" data-rd="back">← News</button>
      ${reader.ok ? '<button class="btn btn-ghost btn-sm" data-rd="listen">🔊 Listen</button>' : ''}</div>
    <span class="nmeta">${esc(a.publisher)} · ${a.published_at ? newsTime(a.published_at) : 'date not given'}${a.provider === 'google-news' ? ' · via Google News' : ''}</span>
    <h2>${esc(a.title)}</h2>
    ${loading ? `<p class="muted"><span class="typing"><span></span><span></span><span></span></span> Reading the story and writing a summary…</p>${summary}` : summary}
    ${d?.summary_error ? `<p class="small err">${esc(d.summary_error)} <button class="act" data-rd="retry">Retry</button></p>` : ''}
    ${!loading && d ? `<p class="small muted">${esc(d.summary ? d.label : 'The publisher\'s own description.')}${d.full_text ? '' : ' Based on limited source content.'}</p>` : ''}
    <div class="read-more">${more}</div>
    <div class="row-tight">
      <button class="btn btn-primary btn-sm" data-rd="more" ${loading ? 'disabled' : ''}>${st === 0 ? 'Read more' : 'Read more on ' + esc(a.publisher) + ' ↗'}</button>
      ${articleUrlOk(a.url) ? `<a class="btn btn-ghost btn-sm" href="${esc(a.url)}" target="_blank" rel="noopener noreferrer">Original article ↗</a>` : '<span class="small err">This story has no valid link.</span>'}
    </div></article>`;
}
function closeArticle(fromHistory = false) {
  if ($('#newsReaderSheet').classList.contains('hidden')) { if (fromHistory) articleView.pushed = false; return; }
  if (reader.owner === 'news') reader.stop();
  closeSheet('newsReaderSheet'); articleView.seq++;                    // late answers are ignored
  if (articleView.pushed && !fromHistory) { articleView.pushed = false; history.back(); } else articleView.pushed = false;
}
function articleAction(act) {
  const a = news.result?.articles.find((x) => x.id === articleView.id);
  if (!a) { toast('This story left the list after a refresh. Open it again from News.'); return closeArticle(); }
  if (act === 'back') return closeArticle();
  if (act === 'retry') return openArticle(a.id, true);
  if (act === 'listen') { reader.load(speechChunks(`${a.title}.\n\n${articleView.data?.summary || a.summary || ''}`), 'news'); reader.play(0); return; }
  if (act === 'more') {
    if (articleView.stage === 0) { articleView.stage = 1; renderArticle(a, articleView.data, false); return; }
    if (!articleUrlOk(a.url)) { toast('This story has no valid https link to open.'); return; }
    window.open(a.url, '_blank', 'noopener,noreferrer');                // the selected story's own link, only now
  }
}
function newsSources() {
  const p = news.prefs || {};
  $('#nsCity').value = p.city || ''; $('#nsRegion').value = p.region || 'IN'; $('#nsLang').value = p.language || 'en';
  $('#nsTopics').value = (p.topics || []).join(', ');
  $('#nsForYou').innerHTML = news.cats.filter((c) => !['foryou', 'local'].includes(c.id)).map((c) =>
    `<button class="chip${(p.for_you || []).includes(c.id) ? ' active' : ''}" data-fy="${esc(c.id)}">${esc(c.label)}</button>`).join('');
  const pubs = [...new Set(news.cats.flatMap((c) => c.publishers))].sort();
  const mark = (x) => (p.prioritized || []).includes(x) ? '★ ' : (p.hidden || []).includes(x) ? '✕ ' : '';
  $('#nsPubs').innerHTML = pubs.map((x) => `<button class="chip${mark(x) ? ' active' : ''}" data-pub="${esc(x)}">${mark(x)}${esc(x)}</button>`).join('');
  openSheet('newsSourcesSheet');
}
async function newsSaveSources() {
  const p = news.prefs || {};
  const body = { city: $('#nsCity').value, region: $('#nsRegion').value, language: $('#nsLang').value,
    for_you: [...$$('#nsForYou .chip.active')].map((b) => b.dataset.fy),
    topics: $('#nsTopics').value.split(',').map((t) => t.trim()).filter(Boolean),
    prioritized: p.prioritized || [], hidden: p.hidden || [] };
  try { news.prefs = await api('/news/prefs', { method: 'PUT', body }); closeSheet('newsSourcesSheet'); newsOpen(news.cat, true); }
  catch (e) { toast(`Not saved: ${e.message}`); }
}
function bindNews() {
  $('#newsCats').addEventListener('click', (e) => { const b = e.target.closest('[data-cat]'); if (b) { $('#newsQ').value = ''; newsOpen(b.dataset.cat); } });
  $('#newsSearchForm').addEventListener('submit', (e) => { e.preventDefault(); newsSearch($('#newsQ').value); });
  $('#newsBriefBtn').addEventListener('click', () => newsAi('briefing'));
  $('#newsAi').addEventListener('click', (e) => { const b = e.target.closest('button[data-naud]'); if (b) newsAudio(b.dataset.naud); });
  $('#newsReader').addEventListener('click', (e) => { const b = e.target.closest('[data-rd]'); if (b) articleAction(b.dataset.rd); });
  window.addEventListener('popstate', () => closeArticle(true));        // browser/phone Back closes the reader
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#newsReaderSheet').classList.contains('hidden')) closeArticle(); });
  $('#newsAi').addEventListener('change', (e) => { if (e.target.dataset.naud === 'rate') newsAudio('rate', e.target.value); });
  reader.onchange = () => { renderNewsAudio(); updateOrb(); };
  $('#newsSourcesBtn').addEventListener('click', () => (news.cats.length ? newsSources() : toast('News sources need the Abhify.AI app on your computer')));
  $('#nsCancel').addEventListener('click', () => closeSheet('newsSourcesSheet'));
  $('#nsSave').addEventListener('click', newsSaveSources);
  $('#nsForYou').addEventListener('click', (e) => e.target.closest('.chip')?.classList.toggle('active'));
  $('#nsPubs').addEventListener('click', (e) => {
    const b = e.target.closest('[data-pub]'); if (!b) return; const x = b.dataset.pub, p = news.prefs;
    p.prioritized = p.prioritized || []; p.hidden = p.hidden || [];
    if (p.prioritized.includes(x)) { p.prioritized = p.prioritized.filter((y) => y !== x); p.hidden.push(x); }
    else if (p.hidden.includes(x)) p.hidden = p.hidden.filter((y) => y !== x); else p.prioritized.push(x);
    newsSources();
  });
  $('#tab-news').addEventListener('click', (e) => {
    // a tap on a story (not on one of its buttons or links) opens it in the app: the publisher's site never opens by itself
    const card = e.target.closest('.ncard[data-id]');
    if (card && !e.target.closest('button, a, [data-nact]')) { openArticle(card.dataset.id); return; }
    const b = e.target.closest('[data-nact]'); if (!b) return;
    if (b.dataset.nact === 'read') { openArticle(b.closest('[data-id]').dataset.id); return; }
    if (b.dataset.nact === 'close-ai') { if (reader.owner === 'news') reader.stop(); $('#newsAi').classList.add('hidden'); return; }
    const id = b.closest('[data-id]')?.dataset.id; const x = news.result?.articles.find((a) => a.id === id); if (!x) return;
    if (b.dataset.nact === 'summarize' || b.dataset.nact === 'explain') newsAi(b.dataset.nact, id);
    if (b.dataset.nact === 'save' && !news.saved.some((s) => s.id === id)) {
      news.saved.unshift({ id, title: x.title, url: x.url, publisher: x.publisher, published_at: x.published_at });
      store.setJson('abhify_news_saved', news.saved.slice(0, 200)); b.textContent = 'Saved'; toast('Saved to your reading list (this browser only)');
    }
    if (b.dataset.nact === 'share') { if (navigator.share) navigator.share({ title: x.title, url: x.url }).catch(() => {}); else navigator.clipboard?.writeText(`${x.title}\n${x.url}`).then(() => toast('Link copied')); }
  });
}

/* ---------- 5. INTERVIEW QUESTIONS ---------- */
function renderCategoryChips(containerSel, current, onPick) {
  const box = $(containerSel);
  box.innerHTML = CATEGORIES.map(([k, label]) =>
    `<button class="chip ${k === current ? 'active' : ''}" data-cat="${k}">${label}</button>`).join('');
  box.onclick = (e) => {
    const b = e.target.closest('[data-cat]'); if (!b) return;
    box.querySelectorAll('.chip').forEach((c) => c.classList.toggle('active', c === b));
    onPick(b.dataset.cat);
  };
}

/** The Question Bank: the questions written by the AI (newest first), then the curated ones. Generated questions are
 *  added to the bank, never instead of it. [state.qOrder] is the curated order (a refresh shuffles it). */
function visibleQuestions() {
  const curated = (state.qOrder || window.MOCK.questions).filter((c) => !state.questions.some((g) => g.question === c.question));
  const pool = [...state.questions.map((q) => ({ ...q, ai: true })), ...curated];
  return state.category === 'all' ? pool : pool.filter((q) => q.category === state.category);
}
/** Refresh: the curated questions in a new order and the list re-drawn; category, difficulty and generated questions stay. */
let qRefreshing = false;
function refreshQuestions() {
  if (qRefreshing) return Promise.resolve();
  qRefreshing = true; $('#qRefresh').disabled = true;
  state.qOrder = [...window.MOCK.questions].sort(() => Math.random() - 0.5);
  renderQuestions();
  $('#qStatus').textContent = `Refreshed · ${visibleQuestions().length} questions${state.category === 'all' ? '' : ' in this category'}.`;
  return new Promise((r) => setTimeout(() => { qRefreshing = false; $('#qRefresh').disabled = false; r(); }, 300));
}

function renderQuestions() {
  const qs = visibleQuestions();
  $('#questionList').innerHTML = qs.length ? qs.map((q, i) => `
    <article class="card">
      <div class="q-head">
        <span class="q-cat">${esc((CATEGORIES.find(([k]) => k === q.category) || [0, q.category])[1]).toUpperCase()}</span>
        <span class="stars" title="${esc(q.difficulty)}">${stars(q.difficulty)}</span>${q.ai ? ' <span class="q-ai" title="Written by the AI: check the answer">AI</span>' : ''}
      </div>
      <p class="q-text">${esc(q.question)}</p>
      <button class="btn btn-primary btn-block" data-action="answer" data-i="${i}">Show answer</button>
      <div class="answer" id="ans-${i}">
        ${esc(q.answer)}
        ${q.keyPoints?.length ? `<ul>${q.keyPoints.map((k) => `<li>${esc(k)}</li>`).join('')}</ul>` : ''}
      </div>
    </article>`).join('')
    : `<p class="empty">No questions in this category yet.<br>Tap <b>✨ Generate</b> to create some with AI.</p>`;
}

async function generateQuestions() {
  if (state.busy.questions) return;
  if (!state.online) { toast('📴 Offline: showing saved questions'); renderQuestions(); return; }
  state.busy.questions = true;
  const btn = $('#genQuestions'); btn.disabled = true; btn.textContent = '⏳ Thinking…';
  $('#questionList').insertAdjacentHTML('afterbegin', skeleton(1));
  try {
    const data = await api('/generate-question', {
      method: 'POST', timeout: 240000,
      body: {
        category: state.category, difficulty: $('#difficulty').value || null, count: 3,
        user_id: state.userId, exclude: state.questions.slice(0, 20).map((q) => q.question),
      },
    });
    state.questions = [...data.questions, ...state.questions].slice(0, 40);   // newest first
    store.setJson(LS.questions, state.questions);
  } catch (e) {
    toast(`Couldn't generate questions: ${e.message}`);
  } finally {
    state.busy.questions = false; btn.disabled = false; btn.textContent = '✨ Generate';
    renderQuestions();
  }
}

/* ---------- 5b. PULL TO REFRESH (career milestone) ----------
   On touch screens, pulling down at the top of News, the Question Bank or MCQs refreshes only that section. The browser's
   own pull-to-reload is switched off in CSS (overscroll-behavior), so the page, navigation, filters and progress stay.
   Not started from text fields, horizontal chip rows or a scrolled inner list; never while text is selected; one at a time. */
const PTR_TARGETS = {
  news: () => (news.query ? newsSearch(news.query) : newsOpen(news.cat, true)),
  interview: () => refreshQuestions(),
  mcq: () => startQuiz('refresh'),
};
const PTR_DISTANCE = 90;
/** True when nothing between [el] and the page is scrolled down (a pull inside a scrolled list scrolls the list). */
function ptrAtTop(el) {
  for (let n = el; n && n !== document.body; n = n.parentElement) if (n.scrollTop > 0) return false;
  return (document.scrollingElement?.scrollTop || 0) <= 0;
}
function bindPullToRefresh() {
  const ind = $('#ptr'), txt = $('#ptrText');
  let y0 = null, dy = 0, busy = false;
  const reset = () => { y0 = null; dy = 0; if (!busy) { ind.classList.add('hidden'); ind.style.transform = ''; } };
  document.addEventListener('touchstart', (e) => {
    const ok = PTR_TARGETS[current] && !busy && e.touches.length === 1 && !document.querySelector('.sheet-backdrop:not(.hidden)')
      && !e.target.closest('input, textarea, select, [contenteditable], .scroll-x, .chips, .launcher') && ptrAtTop(e.target);
    y0 = ok ? e.touches[0].clientY : null; dy = 0;
  }, { passive: true });
  document.addEventListener('touchmove', (e) => {
    if (y0 == null) return;
    dy = e.touches[0].clientY - y0;
    if (dy <= 0 || String(window.getSelection?.() || '')) { reset(); return; }
    // pulling down at the very top: nothing to scroll, so the gesture is ours. Without this the browser takes it over
    // (its own pull-to-reload, or a touchcancel that ended our pull: found in the live test). Scrolling up is untouched.
    if (e.cancelable) e.preventDefault();
    ind.classList.remove('hidden');
    ind.style.transform = `translateY(${Math.min(dy / 2, 60)}px)`;
    txt.textContent = dy > PTR_DISTANCE ? 'Release to refresh' : 'Pull to refresh';
  }, { passive: false });
  document.addEventListener('touchend', async () => {
    if (y0 == null) return;
    const go = dy > PTR_DISTANCE, target = PTR_TARGETS[current];
    y0 = null;
    if (!go || !target) { reset(); return; }
    busy = true; ind.classList.add('busy'); txt.textContent = 'Refreshing…';
    try { await target(); txt.textContent = 'Updated'; } catch { txt.textContent = "Couldn't refresh: pull again to retry"; }
    setTimeout(() => { busy = false; ind.classList.remove('busy'); reset(); }, 700);
  });
  document.addEventListener('touchcancel', reset);
}

/* ---------- 6. MCQ PRACTICE (career milestone) ----------
   Learning topics (built-in categories, /generate-mcq) or personalised practice (/career/mcq: your resume or a document
   from the knowledge folder, any topic, or both). Batches on demand: "Generate more" adds one, "Refresh" replaces the
   questions not answered yet; answers and progress always stay. A tap on an option is the answer: feedback right away.
   Every AI question says its answer is AI-generated, not independently verified. */
const MCQ_AI = 'AI-generated answer · not independently verified: check it if it matters';
function mcqSettings() {
  const mode = $('#mcqMode').value;
  return { mode, document: $('#mcqDoc').value || null, topic: $('#mcqTopic').value.trim(), level: $('#mcqLevel').value,
    difficulty: $('#mcqDiff').value, count: +$('#mcqCount').value };
}
async function mcqSources() {
  if (!state.online) return;
  try {
    const { documents } = await api('/tasks/sources');
    const keep = $('#mcqDoc').value;
    $('#mcqDoc').innerHTML = documents.length ? documents.map((d) => `<option ${d === keep ? 'selected' : ''}>${esc(d)}</option>`).join('')
      : '<option value="">Add your resume to the knowledge folder first</option>';
  } catch { /* phone or offline: learning topics still work */ }
}
function mcqModeChanged() {
  const m = $('#mcqMode').value, personal = m !== 'category';
  $('#mcqDoc').classList.toggle('hidden', !['document', 'mixed'].includes(m));
  $('#mcqTopic').classList.toggle('hidden', !['topic', 'mixed'].includes(m));
  ['#mcqLevel', '#mcqDiff', '#mcqCount'].forEach((id) => $(id).classList.toggle('hidden', !personal));
  $('#mcqChips').classList.toggle('hidden', personal);
  if (personal) mcqSources();
}
/** One batch from the right source. [avoid]: questions already shown (never repeated). */
async function mcqBatch(avoid) {
  const s = mcqSettings();
  if (s.mode === 'category') {
    const data = await api('/generate-mcq', { method: 'POST', timeout: 240000, body: { category: state.mcqCategory, count: 5, user_id: state.userId, exclude: avoid } });
    return { items: data.mcqs.map((m) => ({ ...m, ai: true, verification: MCQ_AI })), dropped: {} };
  }
  const body = { ...s, avoid, model: state.chatModel || null };
  const r = await api('/career/mcq', { method: 'POST', timeout: 240000, body });
  state.quiz.scope = r.scope;
  return { items: r.questions.map((q) => ({ key: q.key, question: q.q, options: q.options, correctAnswer: q.answer, explanation: q.explanation,
    category: q.topic, topic: q.topic, difficulty: q.difficulty, quote: q.quote, verification: q.verification, ai: true, career: true })), dropped: r.dropped };
}
/** [how]: 'new' (start), 'more' (add a batch), 'refresh' (replace unanswered). One request at a time; a failure keeps what's there. */
async function startQuiz(how = 'new') {
  if (state.busy.mcq) return;
  const q = state.quiz;
  if (how !== 'new' && !q.items?.length) how = 'new';
  state.busy.mcq = true; $('#mcqStart').disabled = $('#mcqRefresh').disabled = true;
  $('#mcqStatus').textContent = how === 'refresh' ? 'Refreshing the questions you haven\'t answered…' : 'Writing questions with AI…';
  if (how === 'new') $('#mcqBox').innerHTML = skeleton(1);
  let batch = null, err = null;
  if (state.online) {
    try { batch = await mcqBatch(how === 'new' ? [] : q.items.map((m) => m.question)); } catch (e) { err = e.message; }
  } else err = 'offline';
  if (batch && !batch.items.length) { err = 'No usable question in this batch' + mcqDropped(batch.dropped) + '. Try again, or choose another model.'; batch = null; }
  if (batch) {
    if (how === 'new') state.quiz = { items: batch.items, index: 0, answers: {}, selected: null, scope: state.quiz.scope };
    else {
      const keep = q.items.filter((_, i) => i in q.answers), answers = {};
      keep.forEach((m) => { answers[keep.indexOf(m)] = q.answers[q.items.indexOf(m)]; });
      const base = how === 'refresh' ? keep : q.items;
      state.quiz = { ...q, items: [...base, ...batch.items], answers: how === 'refresh' ? answers : q.answers,
        index: how === 'refresh' ? keep.length : (q.index in q.answers ? q.items.length : q.index), selected: null };
    }
    $('#mcqStatus').textContent = `${batch.items.length} new question${batch.items.length === 1 ? '' : 's'}${mcqDropped(batch.dropped, true)}. Your progress is kept.`
      + (batch.items.length < Math.ceil(mcqSettings().count / 2) && mcqSettings().mode === 'document'
        ? ' This document has been practised a lot: try "Resume + topic" or "Any topic" for more variety.' : '');
  } else if (how === 'new' && $('#mcqMode').value === 'category') {              // offline or AI down: saved or built-in questions
    const saved = store.json(LS.mcqs, []);
    state.quiz = { items: saved.length ? saved : window.MOCK.mcqs, index: 0, answers: {}, selected: null };
    $('#mcqStatus').textContent = `Using saved questions (${err === 'offline' ? 'offline' : err}).`;
  } else $('#mcqStatus').textContent = `⚠ ${err === 'offline' ? 'Personalised questions need the Abhify.AI app on this computer.' : err} Your questions and progress are unchanged.`;
  if (batch && $('#mcqMode').value === 'category') store.setJson(LS.mcqs, state.quiz.items.slice(-20));
  state.busy.mcq = false; $('#mcqStart').disabled = $('#mcqRefresh').disabled = false;
  renderMCQ();
}
function mcqDropped(d, short = false) {
  const n = Object.values(d || {}).reduce((a, b) => a + b, 0);
  return n ? (short ? ` (${n} failed the checks and were left out)` : ` (${Object.entries(d).map(([k, v]) => `${v} ${k}`).join(', ')})`) : '';
}

function renderMCQ() {
  const { items = [], index = 0, answers = {} } = state.quiz;
  const box = $('#mcqBox');
  if (!items.length) { box.innerHTML = '<p class="empty">Choose what to practise and press <b>Start</b>.</p>'; return; }
  if (index >= items.length) {                                   // end of the questions so far
    const right = items.filter((m, i) => answers[i] === m.correctAnswer).length;
    box.innerHTML = `
      <div class="card done">
        <div class="big">${right}/${items.length}</div>
        <p>${right === items.length ? `Perfect score${displayName() ? `, ${esc(displayName())}` : ''}! 🎉` : `Nice work${displayName() ? `, ${esc(displayName())}` : ''}! Keep going 💪`}</p>
        <div class="mcq-controls"><button class="btn btn-primary" data-action="more-quiz">✨ Generate more</button><button class="btn btn-ghost" data-action="new-quiz">Start over</button></div>
      </div>`;
    return;
  }
  const m = items[index], answered = index in answers, sel = answered ? answers[index] : null, shown = answered || $('#mcqReview').checked;
  box.innerHTML = `
    <div class="card">
      <div class="mcq-stats"><span>Question ${index + 1} of ${items.length}${m.topic ? ` · ${esc(m.topic)}` : ''}</span><span>Score: ${state.progress.mcq_correct}/${state.progress.mcq_total}</span></div>
      <div class="bar"><span style="width:${((index + (answered ? 1 : 0)) / items.length) * 100}%"></span></div>
      <p class="mcq-q">${esc(m.question)}</p>
      <div class="options">
        ${m.options.map((o, i) => {
          let cls = 'option';
          if (shown) cls += i === m.correctAnswer ? ' correct' : i === sel ? ' wrong' : '';
          return `<button class="${cls}" data-action="pick" data-i="${i}" ${answered ? 'disabled' : ''} aria-label="Answer ${'ABCD'[i]}: ${esc(o)}">
                    <span class="letter">${'ABCD'[i]}</span><span>${esc(o)}</span></button>`;
        }).join('')}
      </div>
      ${shown ? `<div class="feedback ${!answered ? '' : sel === m.correctAnswer ? 'ok' : 'bad'}" role="status">
          <strong>${!answered ? `Answer: ${'ABCD'[m.correctAnswer]}` : sel === m.correctAnswer ? '✅ Correct!' : `❌ Incorrect: you chose ${'ABCD'[sel]}. The answer is ${'ABCD'[m.correctAnswer]}) ${esc(m.options[m.correctAnswer])}.`}</strong>
          ${esc(m.explanation)}
          ${m.quote ? `<blockquote>From your document: “${esc(m.quote)}”</blockquote>` : ''}
          ${m.ai ? `<small class="mcq-ai">${esc(m.verification || MCQ_AI)}</small>` : '<small class="muted">Built-in question.</small>'}</div>` : ''}
      <div class="mcq-controls">
        <button class="btn btn-ghost" data-action="prev" ${index === 0 ? 'disabled' : ''}>← Prev</button>
        ${answered ? `<button class="btn btn-primary" data-action="next">${index === items.length - 1 ? 'Next batch →' : 'Next →'}</button>`
          : '<span class="muted small">Tap an option to answer</span>'}
      </div>
    </div>`;
}

/** A tap on an option: recorded once, feedback shown immediately. */
function answerMCQ(i) {
  const q = state.quiz, m = q.items[q.index];
  if (!m || q.index in q.answers) return;                       // count each question once
  q.answers[q.index] = i;
  const correct = i === m.correctAnswer;
  recordProgress({ kind: 'mcq', category: m.category, correct, item: m.question });
  if (m.career) api('/career/mcq/answer', { method: 'POST', body: { key: m.key, topic: m.topic || '', difficulty: m.difficulty || 'medium', scope: q.scope || '', correct } }).catch(() => {});
  renderMCQ();
}
function submitMCQ() { if (state.quiz.selected != null) answerMCQ(state.quiz.selected); }

/* ---------- 7. CHAT + KNOWLEDGE BASE ---------- */
function addMsg(role, html) {
  const log = $('#chatLog');
  const el = document.createElement('div');
  el.className = `msg ${role}`;
  el.innerHTML = `<div class="bubble">${html}</div>`;
  log.appendChild(el);
  log.scrollTop = log.scrollHeight;
  return el;
}

/** 6.1: an error or a stopped answer offers Retry (the same question again, in the same place). */
function chatRetryHtml(text, regenerate = false) {
  return `<button type="button" class="tool-btn chat-retry" data-retry="${esc(text)}"${regenerate ? ' data-regen="1"' : ''}>↻ Retry</button>`;
}

async function sendChat(text, { speakAnswer = false, regenerate = false, reuse = null, refreshLive = false } = {}) {
  text = String(text || '').trim();
  if (!text || state.busy.chat) return;
  switchTab('chat');
  if (!reuse) addMsg('user', esc(text));
  if (state.engine === 'device') return deviceChat(text, { speakAnswer });
  $$('#chatLog .msg-regen').forEach((b) => b.remove());                 // 6.1: only the newest answer offers Regenerate
  if (!state.online) {
    const el = reuse || addMsg('bot', '');
    el.querySelector('.bubble').innerHTML = '<p>📴 I\'m offline right now. Chat needs the AI backend. News, interview questions and quizzes still work from saved content.</p>'
      + chatRetryHtml(text, regenerate);
    return;
  }
  state.busy.chat = true; updateOrb();
  const typing = reuse || addMsg('bot', '');
  if (reuse) $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
  const bubble = typing.querySelector('.bubble');
  bubble.innerHTML = '<span class="typing"><span></span><span></span><span></span></span> <button type="button" class="mini-btn chat-stop" aria-label="Stop the answer">■</button>';
  const ctrl = new AbortController();
  const stopBtn = () => '<button type="button" class="mini-btn chat-stop" aria-label="Stop the answer" title="Stop">■</button>';
  bubble.onclick = (e) => { if (e.target.closest('.chat-stop')) ctrl.abort(); };   // one handler per bubble, also while streaming
  let data = null, acc = '', paint = 0, liveTop = '';
  try {
    const body = { message: text, thread_id: state.threadId };
    if (refreshLive) body.refresh_live = true;                             // Refresh on an answer: fetch live sources again
    if (state.chatModel) body.model = state.chatModel;                     // dev.8: checked by the backend, never swapped
    if (state.project) body.project_id = state.project.id;                 // a project chat: its instructions and documents only
    const focusSel = $('#focusSel'); if (focusSel && focusSel.value !== 'auto') body.focus = focusSel.value;   // 5.2: same focuses as Android (6.1: + modes)
    if (regenerate && state.threadId) body.regenerate = true;              // 6.1: the new answer replaces the last one in history
    // The answer is shown while it is written (/chat/stream); a server that can't stream (guest device, older app) answers in one piece.
    try {
      await apiStream('/chat/stream', body, (ev) => {
        if (ev.type === 'live') {                                         // real-time: shown before the answer is written
          liveTop = liveIndicatorHtml(ev);
          if (!acc) bubble.innerHTML = liveTop + '<span class="typing"><span></span><span></span><span></span></span> ' + stopBtn();
        } else if (ev.type === 'token') {
          acc += ev.text;
          if (!paint) paint = requestAnimationFrame(() => {
            paint = 0;
            const open = ((acc.match(/```/g) || []).length % 2) ? '\n```' : '';        // an unfinished code block renders as one
            bubble.innerHTML = liveTop + md(acc + open) + '<span class="caret" aria-hidden="true"></span>' + stopBtn();
            $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
          });
        } else if (ev.type === 'done') data = ev;
        else if (ev.type === 'error') throw new Error(ev.message);
      }, ctrl.signal);
    } catch (e) {
      if ([403, 404, 405].includes(e.status)) data = await api('/chat', { method: 'POST', timeout: 240000, body });
      else throw e;
    }
    cancelAnimationFrame(paint);
    if (!data) throw new Error(acc ? 'The answer was interrupted before it finished.' : 'No answer was received.');
    state.threadId = data.thread_id; store.set(LS.thread, data.thread_id);
    rememberThreadModel();
    bubble.innerHTML = botHtml(data.answer, data.tools_used, data.sources, data.model, data.stats, data.focus, data.context, data.live, text)
      + msgTools(data.answer, { text, regen: !data.guest }) + followUps(data.answer, data.focus) + actionBar(data.answer);
    if (speakAnswer) speak(data.answer);
  } catch (e) {
    cancelAnimationFrame(paint); paint = 0;                              // a pending paint must not overwrite the message below
    bubble.innerHTML = (e.name === 'AbortError'
      ? `<p>■ Stopped. Nothing from this answer was saved${regenerate ? '; the previous answer is still in your history' : ''}.</p>`
      : `<p>⚠️ ${esc(e.message === 'offline' ? 'Lost connection to the backend.' : e.message)}</p>`) + chatRetryHtml(text, regenerate);
  } finally {
    state.busy.chat = false; updateOrb();
    $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
  }
}

/* Voice warm-up: when the microphone starts, the model that will answer (the chat's own choice, never another) and, in
   Private voice mode, Whisper are loaded while you speak, so the answer doesn't wait for a cold load. At most once a minute. */
let lastWarm = 0;
function warmForVoice(whisper) {
  if (state.engine === 'device' || state.apiBase === null || Date.now() - lastWarm < 60000) return;
  lastWarm = Date.now();
  api('/chat/warm', { method: 'POST', body: { model: state.chatModel || null, whisper }, timeout: 120000 }).catch(() => { lastWarm = 0; });
}

/* Project chat: the chat uses one project's instructions and documents (shown as a chip; ✕ leaves the project). */
function setChatProject(p) {
  state.project = p ? { id: p.id, name: p.name } : null;
  store.set('abhify_project', p ? JSON.stringify(state.project) : '');
  $('#projectChip').classList.toggle('hidden', !p);
  $('#projectLabel').textContent = p ? p.name : '';
}

async function uploadKnowledge(file) {
  if (!file) return;
  if (!state.online) { toast('📴 Uploading needs the backend'); return; }
  toast(`Indexing ${file.name}…`, 60000);
  const form = new FormData(); form.append('file', file);
  try {
    const r = await api('/knowledge/upload', { method: 'POST', form, timeout: 300000 });
    toast(`✅ Added ${r.filename} (${r.chunks} chunks)`);
    $('#kbInfo').textContent = `📚 ${r.total_chunks} chunks`;
    addMsg('bot', `📚 I've learned <b>${esc(r.filename)}</b>. Ask me anything about it!`);
  } catch (e) { toast(`Upload failed: ${e.message}`); }
}

/* ---------- 7b. ON-DEVICE AI (Abhify Lite): the AI runs in this browser, no computer needed ----------
   ondevice.js (and the WebLLM engine it vendors) is loaded only when this mode is chosen. News,
   interview questions and quizzes use saved content in this mode; chats and memories stay in this
   browser's storage on this device. */
let ondeviceModule = null;
async function onDevice() { ondeviceModule ??= await import('./ondevice.js'); return ondeviceModule; }

/** Pure: [status text, dot class, banner html] for on-device mode, from state.device. */
function deviceStatus(d) {
  const s = d.support;
  if (!s) return ['Checking this device…', 'warn', ''];
  if (!s.ok) return ['On-device AI not supported here', 'off', `📵 <strong>This device can't run the AI itself.</strong> ${esc(s.reason)}`];
  if (d.loading) {
    const pct = Math.round((d.progress?.progress || 0) * 100);
    return [d.downloaded ? 'Starting the on-device AI…' : `Downloading the AI model · ${pct}%`, 'warn',
      `⏳ <strong>${d.downloaded ? 'Loading the AI model into memory' : `Downloading ${esc(s.label)} (one time)`}…</strong> ${esc(d.progress?.text || '')}`];
  }
  if (d.error) return ['On-device AI failed to start', 'off',
    `⚠️ <strong>The on-device AI couldn't start:</strong> ${esc(d.error)} <button class="btn btn-sm btn-primary" data-device-setup="1">Try again</button>`];
  if (d.ready) return ['AI ready · runs on this device', 'on', ''];
  if (d.downloaded) return ['AI on this device · starts when you chat', 'on', ''];
  return ['On-device AI not set up', 'off',
    `📲 <strong>Run the AI on this device, no computer needed.</strong> One-time download of ${esc(s.label)}, about ${s.downloadMB} MB (Wi-Fi recommended); after that it works offline. <button class="btn btn-sm btn-primary" data-device-setup="1">Set up…</button>`];
}

function renderDevice() {
  state.online = false;                               // the computer's API isn't used in this mode
  state.health = null;
  const [text, dot, banner] = deviceStatus(state.device);
  document.body.classList.remove('guest');
  $('#statusDot').className = 'dot ' + dot;
  $('#statusText').textContent = text;
  $('#offlineText').innerHTML = banner;
  $('#offlineBanner').classList.toggle('hidden', !banner);
}

async function checkDevice() {
  const d = state.device;
  try {
    const od = await onDevice();
    d.support ??= await od.support();
    if (d.support.ok && !d.ready && !d.loading) d.downloaded = await od.isDownloaded(d.support.modelId);
  } catch (e) { d.support ??= { ok: false, reason: `The on-device engine couldn't load (${e.message}).` }; }
  renderDevice();
  return false;
}

/** Download (only after the user confirms, with the exact size) and/or load the model. Shared promise,
 *  so the banner button and a chat message never start two loads. */
async function setupDevice() {
  const d = state.device, s = d.support;
  if (d.loading) return d.loading;
  if (!s?.ok) return false;
  if (!d.downloaded) {
    const est = await navigator.storage?.estimate?.().catch(() => null);
    const freeMB = est?.quota ? Math.floor((est.quota - (est.usage || 0)) / 1e6) : null;
    if (freeMB !== null && freeMB < s.downloadMB * 1.1) {
      toast(`Not enough storage for the AI model: needs about ${s.downloadMB} MB, this browser can use ${freeMB} MB. Free some space and try again.`, 8000);
      return false;
    }
    if (!confirm(`Download the on-device AI model?\n\n${s.label}: about ${s.downloadMB} MB, one time. Wi-Fi recommended.\n`
      + `It uses about ${s.memoryMB} MB of graphics memory while running.\nSource: huggingface.co/mlc-ai (the model publisher), over HTTPS.\n\n`
      + 'After this the AI works offline. Your chats stay in this browser on this device and are not sent anywhere. '
      + 'You can remove the model any time in Settings.')) return false;
    await navigator.storage?.persist?.().catch(() => false);   // ask the browser not to evict the model
  }
  d.error = null; d.progress = { progress: 0, text: '' };
  d.loading = (async () => {
    try {
      const od = await onDevice();
      await od.load(s.modelId, (p) => { d.progress = p; renderDevice(); });
      d.ready = d.downloaded = true;
    } catch (e) { d.error = e.message || String(e); }
    d.loading = null; renderDevice();
    return d.ready;
  })();
  renderDevice();
  return d.loading;
}

async function deviceChat(text, { speakAnswer = false } = {}) {
  const d = state.device;
  if (!d.support?.ok) { addMsg('bot', `📵 ${esc(d.support?.reason || 'The on-device AI isn\'t available on this device.')}`); return; }
  if (!d.ready && !d.downloaded && !d.loading) {
    addMsg('bot', '📲 The on-device AI isn\'t set up yet. Tap <b>Set up…</b> in the banner above (a one-time download), or switch to your computer in Settings.');
    return;
  }
  state.busy.chat = true; updateOrb();
  const typing = addMsg('bot', '<span class="typing"><span></span><span></span><span></span></span>');
  try {
    if (!d.ready && !(await setupDevice())) throw new Error(d.error || 'The on-device AI couldn\'t start.');
    const od = await onDevice();
    let cid = store.get(LS.deviceThread);
    if (!cid) { cid = newId(); store.set(LS.deviceThread, cid); }
    const fact = od.memoryCommand(text);
    if (fact) await od.local.addMemory(fact);
    const [history, memories] = await Promise.all([od.local.history(cid), od.local.memories()]);
    const answer = await od.chat(od.buildMessages(text, history, memories, displayName()));
    await od.local.addMessage(cid, 'user', text);
    await od.local.addMessage(cid, 'assistant', answer);
    typing.querySelector('.bubble').innerHTML = botHtml(answer)
      + (fact ? '<div class="sources">🧠 Saved to memory on this device</div>' : '');
    if (speakAnswer) speak(answer);
  } catch (e) {
    typing.querySelector('.bubble').innerHTML = `⚠️ ${esc(e.message)}`;
  } finally {
    state.busy.chat = false; updateOrb();
    $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
  }
}

async function loadDeviceHistory() {
  const cid = store.get(LS.deviceThread);
  if (!cid) return;
  try {
    const od = await onDevice();
    for (const m of (await od.local.history(cid)).slice(-50)) addMsg(m.role === 'user' ? 'user' : 'bot', m.role === 'user' ? esc(m.content) : botHtml(m.content));
  } catch { /* storage unavailable: keep the welcome message only */ }
}

function renderDeviceSettings() {
  const device = $('#setEngine').value === 'device', s = state.device.support;
  $('#deviceInfo').textContent = device
    ? 'Runs a small AI model on this device\'s graphics chip: a one-time download of about 700 to 870 MB, then it works offline. '
      + 'Chat and memories only; no document search, fresh news or new quiz generation. Your chats stay in this browser.'
      + (s && !s.ok ? ` ⚠️ ${s.reason}` : '')
    : 'The full app runs on your computer; this device connects to it.';
  $('#deviceRow').classList.toggle('hidden', !(device && (state.device.downloaded || state.device.ready)));
}

async function removeDeviceModel() {
  const s = state.device.support;
  if (!s?.ok || !confirm(`Remove the on-device AI model (${s.label}, about ${s.downloadMB} MB) from this device?\n\nYour on-device chats and memories are kept. You can download the model again later.`)) return;
  try {
    await (await onDevice()).remove(s.modelId);
    Object.assign(state.device, { downloaded: false, ready: false, error: null });
    renderDeviceSettings(); renderDevice(); toast('On-device AI model removed');
  } catch (e) { toast(`Couldn't remove the model: ${e.message}`); }
}

async function clearDeviceChats() {
  if (!confirm('Delete all on-device chats and memories from this browser? This cannot be undone.')) return;
  try { await (await onDevice()).local.clearAll(); newChat(); toast('On-device chats and memories deleted'); }
  catch (e) { toast(`Couldn't delete: ${e.message}`); }
}

/* ---------- 8. VOICE ---------- */
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const voice = { rec: null, mediaRec: null, chunks: [], listening: false, mode: 'command' };

/** Markdown into what is read aloud: one item per bullet/paragraph, each split at sentence ends into parts of at most
 *  [max] characters (long utterances stall in some browsers). Citations like [1], links and code are not read. */
function speechChunks(markdown, max = 220) {
  const items = String(markdown || '').replace(/```[\s\S]*?```/g, ' (code example) ').split(/\n\s*\n|\n(?=\s*(?:[-*•]|\d+[.)])\s)/)
    .map((s) => s.replace(/\s*https?:\/\/\S+/g, '').replace(/\s*\bwww\.\S+/gi, '').replace(/\s*\[(\d+(?:\s*[,–-]\s*\d+)*)\]/g, '')
      .replace(/\s*\[\s*[WL]\d+(?:\s*[,;–-]\s*[WL]?\d+)*\s*\]/gi, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
      .replace(/^\s*(?:[-*•]|\d+[.)]|#{1,6})\s*/gm, '').replace(/[*_`#>|]/g, '').replace(/\s+/g, ' ').trim())
    .filter((s) => /[\p{L}\p{N}]/u.test(s));
  const out = [];
  items.forEach((text, item) => {
    let rest = text;
    while (rest.length > max) {
      const win = rest.slice(0, max);
      let cut = Math.max(win.lastIndexOf('. '), win.lastIndexOf('! '), win.lastIndexOf('? '), win.lastIndexOf('; '));
      cut = cut > max / 3 ? cut + 1 : (win.lastIndexOf(' ') > max / 3 ? win.lastIndexOf(' ') : max);
      out.push({ text: rest.slice(0, cut).trim(), item }); rest = rest.slice(cut).trim();
    }
    if (rest) out.push({ text: rest, item });
  });
  return out;
}

/** A read-aloud player over the browser's speech synthesis (injected so tests can use a fake engine).
 *  Plays parts in order; Pause remembers the part (browsers can't pause mid-sentence reliably), Resume restarts it.
 *  Every start cancels what was playing and bumps a generation number, so a late event from older speech can't
 *  advance or duplicate the new one. onchange(reader) after every state change. */
function makeReader(synth, Utterance) {
  const r = { ok: Boolean(synth && Utterance), parts: [], i: 0, state: 'idle', rate: 1, lang: 'en-US', error: null, gen: 0, onchange: null, owner: null };
  const emit = () => r.onchange?.(r);
  const next = (g) => {
    if (g !== r.gen) return;
    if (r.i >= r.parts.length) { r.state = 'ended'; r.i = 0; emit(); return; }
    const u = new Utterance(r.parts[r.i].text); u.rate = r.rate; u.lang = r.lang;
    u.onend = () => { if (g === r.gen) { r.i++; next(g); } };
    u.onerror = (e) => {
      if (g !== r.gen || ['interrupted', 'canceled'].includes(e?.error)) return;
      const why = { 'not-allowed': 'The browser blocked read-aloud. Click Listen again.', 'synthesis-unavailable': 'No speech voice is available in this browser.',
        'audio-busy': 'The audio output is busy. Try again.', 'network': 'This voice needs the internet. Choose another voice in your system settings.' };
      r.gen++; r.state = 'error'; r.error = why[e?.error] || e?.error || 'speech failed'; emit();
    };
    synth.speak(u); emit();
  };
  r.load = (parts, owner = null) => { r.stop(); r.parts = parts; r.i = 0; r.error = null; r.owner = owner; emit(); };
  r.play = (from = r.i) => {
    if (!r.ok || !r.parts.length) { r.state = 'error'; r.error = r.ok ? 'Nothing to read.' : 'Read aloud is not available in this browser.'; emit(); return; }
    const g = ++r.gen; synth.cancel(); r.i = Math.min(Math.max(0, from), r.parts.length - 1); r.state = 'playing'; r.error = null; next(g);
  };
  r.pause = () => { if (r.state !== 'playing') return; r.gen++; synth.cancel(); r.state = 'paused'; emit(); };
  r.resume = () => { if (r.state === 'paused') r.play(r.i); };
  r.stop = () => { if (r.state === 'idle') return; r.gen++; if (r.ok) synth.cancel(); r.state = 'idle'; r.i = 0; emit(); };
  r.toggle = () => (r.state === 'playing' ? r.pause() : r.state === 'paused' ? r.resume() : r.play(0));
  r.item = () => r.parts[r.i]?.item ?? 0;
  r.items = () => (r.parts.length ? r.parts[r.parts.length - 1].item + 1 : 0);
  r.skip = (delta) => {                                          // to the first part of the previous/next item
    const want = Math.min(Math.max(0, r.item() + delta), r.items() - 1), at = r.parts.findIndex((p) => p.item === want);
    if (at >= 0) r.play(at);
  };
  r.setRate = (x) => { r.rate = x; if (r.state === 'playing') r.play(r.i); else emit(); };
  return r;
}

const reader = makeReader(window.speechSynthesis, window.SpeechSynthesisUtterance);

/** Reads text aloud (voice answers, voice commands): the whole text, in parts, through the shared reader. */
function speak(text) {
  if (!reader.ok) return;
  reader.load(speechChunks(text), 'voice'); reader.play(0);
}

/** mode: 'command' (floating button) or 'dictate' (chat mic) */
async function startListening(mode) {
  if (voice.listening) { stopListening(); return; }
  voice.mode = mode;
  const useWhisper = state.usePrivateWhisper || !SR;
  if (useWhisper && !state.online) { toast(SR ? 'Private voice mode needs the backend' : 'Voice needs the backend in this browser'); return; }
  warmForVoice(useWhisper);                                         // the model loads while you speak
  showVoiceUI(true, 'Listening…');
  if (!useWhisper) return startWebSpeech();
  return startRecorder();
}

function startWebSpeech() {
  voice.rec = new SR();
  voice.rec.lang = 'en-US'; voice.rec.interimResults = true; voice.rec.maxAlternatives = 1;
  let finalText = '';
  voice.rec.onresult = (e) => {
    const t = Array.from(e.results).map((r) => r[0].transcript).join('');
    $('#voiceText').textContent = t || 'Listening…';
    if (e.results[e.results.length - 1].isFinal) finalText = t;
  };
  voice.rec.onerror = (e) => { if (e.error !== 'aborted' && e.error !== 'no-speech') toast(`Mic error: ${e.error}`); };
  voice.rec.onend = () => { showVoiceUI(false); if (finalText) handleTranscript(finalText); };
  voice.listening = true;
  voice.rec.start();
}

async function startRecorder() {
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
  catch { showVoiceUI(false); toast('Microphone permission denied'); return; }
  voice.chunks = [];
  voice.mediaRec = new MediaRecorder(stream);
  voice.mediaRec.ondataavailable = (e) => e.data.size && voice.chunks.push(e.data);
  voice.mediaRec.onstop = async () => {
    stream.getTracks().forEach((t) => t.stop());
    $('#voiceText').textContent = 'Transcribing with Whisper…';
    const blob = new Blob(voice.chunks, { type: voice.mediaRec.mimeType || 'audio/webm' });
    const form = new FormData(); form.append('file', blob, 'speech.webm');
    try {
      const r = await api('/transcribe', { method: 'POST', form, timeout: 120000 });
      showVoiceUI(false);
      if (r.text) handleTranscript(r.text); else toast("Didn't catch that. Try again.");
    } catch (e) { showVoiceUI(false); toast(`Transcription failed: ${e.message}`); }
  };
  voice.listening = true;
  voice.mediaRec.start();
  $('#voiceText').textContent = 'Recording… tap Stop when done';
  clearTimeout(voice.maxTimer);
  voice.maxTimer = setTimeout(stopListening, 15000);             // safety limit
}

function stopListening() {
  clearTimeout(voice.maxTimer);
  voice.listening = false;
  if (voice.rec) { try { voice.rec.stop(); } catch { /* already stopped */ } voice.rec = null; }
  if (voice.mediaRec && voice.mediaRec.state === 'recording') voice.mediaRec.stop();
  else showVoiceUI(false);
}

function showVoiceUI(on, text) {
  $('#voiceOverlay').classList.toggle('hidden', !on);
  $('#voiceFab').classList.toggle('rec', on);
  $('#chatMic').classList.toggle('rec', on);
  if (text) $('#voiceText').textContent = text;
  if (!on) voice.listening = false;
  updateOrb();
}

/** Voice commands navigate; anything else becomes a question for the AI. */
function handleTranscript(raw) {
  const t = raw.toLowerCase().trim();
  if (voice.mode === 'dictate') return sendChat(raw, { speakAnswer: true });
  const has = (...w) => w.some((x) => new RegExp(`\\b${x}\\b`).test(t));
  if (t.split(/\s+/).length <= 4) {                              // short phrase: likely a command
    if (has('news', 'headlines')) { switchTab('news'); return speak('Here are the latest AI headlines'); }
    if (has('interview')) { switchTab('interview'); return speak('Opening interview questions'); }
    if (has('quiz', 'mcq', 'test')) { switchTab('mcq'); return speak('Starting the quiz'); }
    if (has('chat', 'assistant')) { switchTab('chat'); return speak('How can I help?'); }
    if (has('next')) { if (current === 'mcq') { state.quiz.index++; state.quiz.selected = null; renderMCQ(); } return; }
    if (has('previous', 'back')) { if (current === 'mcq' && state.quiz.index > 0) { state.quiz.index--; renderMCQ(); } return; }
    if (has('progress', 'score')) {
      const p = state.progress;
      return speak(`You've answered ${p.mcq_correct} out of ${p.mcq_total} questions correctly, with a ${p.streak_days || 0} day streak.`);
    }
    if (has('help', 'commands')) return speak('Say news, interview, quiz, chat, next, previous, or my progress. Or just ask me any AI question.');
    if (has('refresh')) { loadNews(true); return speak('Refreshing the news'); }
  }
  sendChat(raw, { speakAnswer: true });                          // free-form question
}

/* ---------- 9. PROGRESS, SETTINGS, PWA INSTALL ---------- */
function updateProgressPill() {
  const p = state.progress;
  $('#progressPill').textContent = `${p.mcq_correct}/${p.mcq_total} · 🔥${p.streak_days || 0}`;
}

async function recordProgress(ev) {
  // Optimistic local update so the UI responds instantly, even offline.
  if (ev.kind === 'mcq') { state.progress.mcq_total++; if (ev.correct) state.progress.mcq_correct++; }
  if (ev.kind === 'interview_view') state.progress.interview_viewed++;
  store.setJson(LS.progress, state.progress); updateProgressPill();

  const payload = { user_id: state.userId, ...ev };
  if (!state.online) { const q = store.json(LS.queue, []); q.push(payload); store.setJson(LS.queue, q.slice(-200)); return; }
  try { applyServerProgress(await api('/progress', { method: 'POST', body: payload })); }
  catch { const q = store.json(LS.queue, []); q.push(payload); store.setJson(LS.queue, q); }
}

async function flushQueue() {                                     // sync offline answers
  const q = store.json(LS.queue, []);
  if (!q.length) return;
  store.setJson(LS.queue, []);
  for (const ev of q) { try { await api('/progress', { method: 'POST', body: ev }); } catch { /* drop */ } }
}

async function refreshProgress() {
  try { applyServerProgress(await api(`/progress/${state.userId}`)); } catch { /* keep local */ }
}
function applyServerProgress(p) {
  if (!p || typeof p.mcq_total !== 'number') return;
  state.progress = { ...state.progress, ...p };
  store.setJson(LS.progress, state.progress); updateProgressPill();
}

function openSettings() {
  $('#setName').value = state.userName;
  $('#setApi').value = store.get(LS.api) || '';
  $('#setToken').value = store.get(LS.token) || '';
  $('#setWhisper').checked = state.usePrivateWhisper;
  $('#setEngine').value = state.engine; renderDeviceSettings();
  renderDefaultModelSetting(); if (!state.models.loaded) loadModels().then(renderDefaultModelSetting);
  if (state.online) api('/preferences').then((p) => {                       // 5.2 answer style (desktop backend)
    $('#setDetail').value = p.answer_detail || 'auto'; $('#setDepth').value = p.answer_depth || 'auto';
    $('#setStyle').value = p.answer_style || 'auto'; $('#setLanguage').value = p.answer_language || '';
  }).catch(() => {});
  $('#testResult').textContent = '';
  loadLiveSettings();
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) && !navigator.standalone;
  const android = /android/i.test(navigator.userAgent);
  $('#installHint').textContent = ios ? '📱 To install on iPhone/iPad: open this page in Safari, tap Share, then "Add to Home Screen".'
    : android ? '📱 To install on Android: in Chrome tap ⋮ then "Install app" (needs HTTPS, see the mobile guide).' : '';
  $('#settingsSheet').classList.remove('hidden');
}
function closeSettings() { $('#settingsSheet').classList.add('hidden'); }
/** Settings → Live information (this computer only): saved as soon as a control changes; the connector list comes from
 *  GET /realtime/status (states and reasons, never keys). */
function renderLiveSettings(st) {
  const s = st.settings || {};
  $('#rtEnabled').checked = s.enabled !== false; $('#rtProvider').value = s.web_provider || 'ddgs';
  $('#rtWeather').checked = Boolean(s.weather); $('#rtFx').checked = Boolean(s.fx);
  if (document.activeElement !== $('#rtPlace')) $('#rtPlace').value = s.place || '';
  const prov = (st.providers || []).find((p) => p.id === s.web_provider);
  $('#rtProviderInfo').textContent = prov && prov.requires_key && !prov.key_set ? `needs key: set ${prov.requires_key} in the environment of the Abhify.AI app, then restart it`
    : s.web_provider === 'ddgs' ? 'No key needed. Questions are sent to DuckDuckGo.' : s.web_provider === 'none' ? 'General web search is off. News still uses Google News.' : `Key found in the environment (${prov?.requires_key || ''}).`;
  $('#rtStatus').innerHTML = (st.connectors || []).map((c) => `<li data-state="${esc(c.state)}"><span class="rt-dot" aria-hidden="true"></span>`
    + `<span class="rt-name">${esc(c.label)}${c.external ? '' : ' <small class="muted">· local</small>'}</span><span class="rt-state">${esc(LIVE_STATE_LABEL[c.state] || c.state)}</span>`
    + (c.why && c.state !== 'ready' ? `<small class="rt-why">${esc(c.why)}</small>` : '') + '</li>').join('');
}
async function loadLiveSettings() {
  const box = $('#liveSettings'); if (!box) return;
  const here = state.online && !state.remote && state.engine !== 'device';
  box.classList.toggle('hidden', !here); $('#liveSettingsOff').classList.toggle('hidden', here);
  if (!here) return;
  try { renderLiveSettings(await api('/realtime/status', { timeout: 8000 })); } catch (e) { $('#rtStatus').innerHTML = `<li data-state="error">${esc(e.message)}</li>`; }
}
async function saveLiveSetting(change) {
  try { renderLiveSettings(await api('/realtime/settings', { method: 'PUT', body: change, timeout: 8000 })); }
  catch (e) { toast(`Live information: ${e.message}`); loadLiveSettings(); }
}
function bindLiveSettings() {
  if (!$('#liveSettings')) return;
  $('#rtEnabled').addEventListener('change', (e) => saveLiveSetting({ enabled: e.target.checked }));
  $('#rtProvider').addEventListener('change', (e) => saveLiveSetting({ web_provider: e.target.value }));
  $('#rtWeather').addEventListener('change', (e) => saveLiveSetting({ weather: e.target.checked }));
  $('#rtFx').addEventListener('change', (e) => saveLiveSetting({ fx: e.target.checked }));
  $('#rtPlace').addEventListener('change', (e) => saveLiveSetting({ place: e.target.value.trim().slice(0, 80) }));
}
async function saveSettings() {
  state.userName = $('#setName').value.trim();
  store.set(LS.name, state.userName);
  if (state.apiBase !== null && state.userName) api('/preferences', { method: 'PUT', body: { user_name: state.userName } }).catch(() => {});
  store.set(LS.api, $('#setApi').value.trim());
  store.set(LS.token, $('#setToken').value.trim());
  state.usePrivateWhisper = $('#setWhisper').checked;
  store.set(LS.whisper, state.usePrivateWhisper ? '1' : '0');
  setNameUI(); if (current === 'home') renderHome();
  await saveDefaultModel();
  closeSettings();
  state.engine = $('#setEngine').value === 'device' ? 'device' : 'pc'; store.set(LS.engine, state.engine);
  if (state.engine === 'device') { await checkConnection(); toast('📲 The AI now runs on this device'); return; }
  state.apiBase = null;
  toast((await checkConnection()) ? '✅ Connected to backend' : '📴 Backend not reachable, using offline mode');
}
async function testApi() {
  const url = $('#setApi').value.trim().replace(/\/+$/, '') || (location.protocol.startsWith('http') ? location.origin : 'http://localhost:8000');
  $('#testResult').textContent = 'Testing…';
  try {
    const t = $('#setToken').value.trim();
    const h = await (await fetch(url + '/health', { cache: 'no-store', headers: t ? { Authorization: `Bearer ${t}` } : {} })).json();
    $('#testResult').textContent = h.remote && h.authorized === false
      ? (h.companion_enabled === false ? '🔒 Reachable, but companion access is off on that computer' : '🔗 Reachable, but not paired: the token is missing or wrong')
      : h.ollama ? `✅ OK · ${h.llm_model}${h.llm_ready ? '' : ' (model not pulled)'}${h.remote ? ' · AI runs on that computer' : ''}` : '⚠️ Backend up, Ollama not reachable';
  } catch { $('#testResult').textContent = '❌ Not reachable (check URL / CORS)'; }
}

let deferredInstall = null;
window.addEventListener('beforeinstallprompt', (e) => {           // Chrome / Edge / Android
  e.preventDefault(); deferredInstall = e; $('#installBtn').classList.remove('hidden');
});
window.addEventListener('appinstalled', () => { $('#installBtn').classList.add('hidden'); toast('🎉 Installed!'); });
async function installApp() {
  if (!deferredInstall) return;
  deferredInstall.prompt(); await deferredInstall.userChoice; deferredInstall = null;
  $('#installBtn').classList.add('hidden');
}

/* ---------- 10. NAVIGATION + BOOT ---------- */
let current = 'home';
const loaded = { news: false, interview: false, mcq: false, chat: false };

/* 6.1: the address keeps the open page (?tab=…), so a refresh or a shared link reopens it; /home, /assistant, /study and /more
   (served as the app by the backend) and #chat-style hashes open their page too. Pairing/invite hashes are left alone. */
const TAB_NAMES = ['home', 'chat', 'study', 'news', 'more', 'interview', 'mcq'];
const PATH_TABS = { home: 'home', assistant: 'chat', study: 'study', more: 'more' };
function tabFromUrl(loc = location) {
  const q = new URLSearchParams(loc.search).get('tab');
  if (TAB_NAMES.includes(q)) return q;
  const p = PATH_TABS[loc.pathname.replace(/^\/+|\/+$/g, '')];
  if (p) return p;
  const h = (loc.hash || '').slice(1);
  return TAB_NAMES.includes(h) ? h : null;
}
function syncTabUrl(name) {
  try {
    const u = new URL(location.href);
    if (u.searchParams.get('tab') === name || !/^https?:/.test(u.protocol)) return;
    if (name === 'home') u.searchParams.delete('tab'); else u.searchParams.set('tab', name);
    if (TAB_NAMES.includes(u.hash.slice(1))) u.hash = '';
    if (PATH_TABS[u.pathname.replace(/^\/+|\/+$/g, '')]) u.pathname = u.pathname.replace(/[^/]*$/, '');
    history.replaceState(history.state, '', u.pathname + u.search + u.hash);
  } catch { /* file:// or a locked-down browser: the page still works */ }
}

function switchTab(name) {
  if (name !== 'news' && reader.owner === 'news') reader.stop();   // leaving News ends its read-aloud (documented behaviour)
  current = name;
  syncTabUrl(name);
  const primary = (name === 'interview' || name === 'mcq') ? 'study' : name;   // next build: QB and MCQs live under Study
  $$('.tab').forEach((b) => { const on = b.dataset.tab === primary; b.classList.toggle('active', on); b.setAttribute('aria-selected', on); });
  $$('.panel').forEach((p) => p.classList.toggle('active', p.id === `tab-${name}`));
  document.body.classList.toggle('on-chat', name === 'chat');
  closePages();                                                   // a tab click leaves a full-page section
  $('#content').scrollTop = 0;
  if (name === 'home') renderHome();
  if (name === 'interview' && !loaded.interview) {
    loaded.interview = true; renderQuestions();
    if (!state.questions.length && state.online) generateQuestions();
  }
  if (name === 'mcq' && !loaded.mcq) { loaded.mcq = true; startQuiz(); }
  if (name === 'more') renderMore();
  if (name === 'study') { setTimeout(() => $('#discoverQ')?.focus({ preventScroll: true }), 50); renderSkills(); }
  if (name === 'chat') {
    if (!loaded.chat) { loaded.chat = true; loadChatHistory(); }
    setTimeout(() => $('#chatText').focus({ preventScroll: true }), 50);
  }
}

function bindEvents() {
  $$('.tab').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  bindStudyMore();
  bindShell(); bindHome(); watchPages();
  $('#newsRefresh').addEventListener('click', () => (news.query ? newsSearch(news.query) : newsOpen(news.cat, true)));
  bindNews(); bindPullToRefresh();
  $('#genQuestions').addEventListener('click', generateQuestions);
  renderCategoryChips('#categoryChips', state.category, (c) => { state.category = c; renderQuestions(); });
  renderCategoryChips('#mcqChips', state.mcqCategory, (c) => { state.mcqCategory = c; startQuiz(); });
  $('#mcqMode').addEventListener('change', mcqModeChanged);
  $('#mcqStart').addEventListener('click', () => startQuiz('new'));
  $('#mcqRefresh').addEventListener('click', () => startQuiz('refresh'));
  $('#mcqReview').addEventListener('change', renderMCQ);
  $('#qRefresh').addEventListener('click', refreshQuestions);

  // Event delegation for dynamically rendered cards
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]'); if (!el) return;
    const a = el.dataset.action;
    if (a === 'expand') {
      const s = el.previousElementSibling; s.classList.toggle('expanded');
      el.textContent = s.classList.contains('expanded') ? 'Show less' : 'Read more';
    } else if (a === 'answer') {
      const box = $(`#ans-${el.dataset.i}`), show = !box.classList.contains('show');
      box.classList.toggle('show', show); el.textContent = show ? 'Hide answer' : 'Show answer';
      const q = visibleQuestions()[+el.dataset.i];
      if (show && q && !q._viewed) { q._viewed = true; recordProgress({ kind: 'interview_view', category: q.category, item: q.question }); }
    } else if (a === 'pick') answerMCQ(+el.dataset.i);
    else if (a === 'submit') submitMCQ();
    else if (a === 'next') { if (state.quiz.index >= state.quiz.items.length - 1) { state.quiz.index++; startQuiz('more'); } else { state.quiz.index++; renderMCQ(); } }
    else if (a === 'more-quiz') startQuiz('more');
    else if (a === 'prev') { state.quiz.index = Math.max(0, state.quiz.index - 1); state.quiz.selected = null; renderMCQ(); }
    else if (a === 'new-quiz') startQuiz();
  });

  // Chat
  const ta = $('#chatText');
  $('#chatForm').addEventListener('submit', (e) => { e.preventDefault(); const v = ta.value; ta.value = ''; ta.style.height = ''; sendChat(v); });
  ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#chatForm').requestSubmit(); } });
  ta.addEventListener('input', () => { ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 140) + 'px'; });
  $('#kbFile').addEventListener('change', (e) => { uploadKnowledge(e.target.files[0]); e.target.value = ''; });
  bindModelPicker();
  const fs = $('#focusSel');                                     // 6.1: the chosen mode stays chosen (this browser only)
  if (fs) {
    fs.value = store.get('abhify_chat_mode') || 'auto'; if (!fs.value) fs.value = 'auto';
    const sync = () => { const o = fs.selectedOptions[0]; fs.title = o?.title || 'Chat mode'; fs.dataset.mode = fs.value; };
    sync();
    fs.addEventListener('change', () => { store.set('abhify_chat_mode', fs.value); sync(); toast(`${fs.selectedOptions[0].textContent.trim()}: ${fs.title}`); });
  }

  // Voice
  $('#voiceFab').addEventListener('click', () => startListening('command'));
  $('#chatMic').addEventListener('click', () => startListening('dictate'));
  $('#voiceStop').addEventListener('click', stopListening);

  // Settings / install
  $('#settingsBtn').addEventListener('click', openSettings);
  $('#closeSettings').addEventListener('click', closeSettings);
  $('#saveSettings').addEventListener('click', saveSettings);
  bindLiveSettings();
  $('#testApi').addEventListener('click', testApi);
  $('#settingsSheet').addEventListener('click', (e) => { if (e.target.id === 'settingsSheet') closeSettings(); });
  $('#resetProgress').addEventListener('click', () => {
    if (!confirm('Reset your local score and saved quiz progress on this device?')) return;
    state.progress = { mcq_total: 0, mcq_correct: 0, interview_viewed: 0, streak_days: 0 };
    store.setJson(LS.progress, state.progress); updateProgressPill(); toast('Local progress reset');
  });
  $('#installBtn').addEventListener('click', installApp);
  $('#setEngine').addEventListener('change', renderDeviceSettings);
  $('#deviceRemove').addEventListener('click', removeDeviceModel);
  $('#deviceClear').addEventListener('click', clearDeviceChats);
  bindV2Events();

  // Connectivity changes
  window.addEventListener('hashchange', () => { if (readPairingLink()) checkConnection(); else readInviteLink(); });
  $('#offlineBanner').addEventListener('click', (e) => {
    if (e.target.closest('[data-join]')) joinAsGuest();
    if (e.target.closest('[data-device-setup]')) setupDevice();
  });
  window.addEventListener('storage', (e) => { if (e.key === LS.token || e.key === LS.api) checkConnection(); });   // paired in another window
  window.addEventListener('online', checkConnection);
  window.addEventListener('offline', () => setOnline(false));
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkConnection(); });
}

function tickClock() { $('#clock').textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }

async function boot() {
  setNameUI();
  updateProgressPill();
  bindEvents();
  tickClock(); setInterval(tickClock, 15000);

  // Service worker (needs http/https; skipped when opened as a local file)
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
    navigator.serviceWorker.register('sw.js').catch((e) => console.warn('SW registration failed', e));
  }

  readPairingLink();
  state.ready = checkConnection();
  readInviteLink();
  await state.ready;
  setInterval(checkConnection, 30000);
  newsOpen('foryou').then(() => current === 'home' && renderHome());   // News Center (falls back to the classic AI-summarized view)
  loadPreferences();
  // Mode A vs Mode B: is the AI running on THIS device or on a paired computer? (/health says so;
  // /companion is the fallback for an older backend)
  if (state.health && typeof state.health.remote !== 'boolean') {
    try { state.remote = !(await api('/companion')).this_device_is_host; } catch { /* keep the guess */ }
    setOnline(state.online, state.health);
  }
  if (!state.remote && state.health && state.health.setup_completed === false) openSetup();   // first run on this computer

  // Deep links from manifest shortcuts, e.g. ./?tab=mcq; 6.1: also /assistant, /study, #news … and a refresh keeps the page
  const tab = tabFromUrl();
  if (tab && tab !== current) switchTab(tab);
}

/* ---------- 11. ABHIFY.AI v2: CHAT HISTORY, SETUP, MEMORY, SYSTEM ---------- */
const TOOL_TAGS = {
  web_search: '<span class="tag">🌐 Web search</span>',
  knowledge_base_search: '<span class="tag kb">📚 Your documents</span>',
  search_memory: '<span class="tag kb">🧠 Your memory</span>',
  save_memory: '<span class="tag kb">🧠 Memory saved</span>',
};

/** Format an assistant answer with tool badges, sources and (6.1) the personal context it could use (used live and for history). */
function botHtml(answer, tools = [], sources = [], model = null, stats = null, focus = null, context = null, live = null, question = '') {
  const liveUrls = new Set(((live && live.sources) || []).map((e) => e.url).filter(Boolean));
  sources = (sources || []).filter((s) => !liveUrls.has(s));
  const speed = stats && stats.tokens_per_second ? ` · ${stats.tokens_per_second} tok/s` : '';
  const tags = (model ? `<span class="tag model" title="Model that wrote this answer${stats ? ` (${stats.output_tokens} tokens in ${stats.total_seconds} s, measured by Ollama)` : ''}">${esc(model)}${speed}</span>` : '')
    + tools.map((t) => TOOL_TAGS[t] || '').join('') + (focus ? `<span class="tag" title="How this answer was formatted">${esc(focus)}</span>` : '');
  const src = (sources || []).length ? `<div class="sources"><span class="src-label">Sources</span>${sources.map((s, i) => {
    if (!/^https?:/.test(s)) return `<span class="src-chip" title="From your documents">📄 ${esc(s)}</span>`;
    try { return `<a class="src-chip" href="${esc(s)}" target="_blank" rel="noopener" title="${esc(s)}">🌐 ${esc(new URL(s).hostname)}</a>`; } catch { return `<span class="src-chip">${esc(s)}</span>`; }
  }).join('')}</div>` : '';
  return md(answer) + liveBoxHtml(live, question) + (tags ? `<div class="meta-tags">${tags}</div>` : '') + src + contextHtml(context);
}

/* ---------- 7b2. LIVE INFORMATION (real-time engine) ----------
   The backend retrieves current information before the model answers (realtime.py) and reports it as `live`
   {needed, state, sources, notes, problems}. Here: the indicator while retrieving, then the source chips ([W#] retrieved now,
   [L#] your own records or this computer) with publisher, published age and retrieved time, an inline details panel and
   Refresh. Every value is escaped; only http(s) links are ever made clickable. */
const liveStore = [];
const LIVE_STATE_LABEL = { ready: 'Ready', off: 'Off', needs_key: 'Needs key', offline: 'Offline', error: 'Error' };
function liveSearchingText(needed = []) {
  const n = new Set(needed), only = (...c) => needed.length && needed.every((x) => c.includes(x));
  if (only('news')) return 'Retrieving news…';
  if (only('weather', 'fx')) return 'Checking a live source…';
  if (only('projects', 'tasks')) return 'Reading your records…';
  if (only('system')) return "Reading this computer's status…";
  return n.size ? 'Searching current information…' : '';
}
function liveOldest(live) {
  const t = (live.sources || []).filter((e) => e.freshness === 'cached').map((e) => e.retrieved_at).filter(Boolean);
  return t.length ? Math.min(...t) : null;
}
function liveStatusText(live) {
  if (!live || !live.state || live.state === 'none') return '';
  const why = (live.problems || [])[0] || 'no source answered';
  if (live.state === 'verified') return 'Sources verified';
  if (live.state === 'cached') return `Using cached information (retrieved ${timeAgo(liveOldest(live) || live.retrieved_at || Date.now())})`;
  if (live.state === 'partial') return `Some sources verified · ${why}`;
  return `Live information unavailable — ${why}`;
}
/** The line shown while the answer is still coming: "Searching…" first, then the outcome. */
function liveIndicatorHtml(ev) {
  if (ev.phase === 'searching') {
    const t = liveSearchingText(ev.needed || []);
    return t ? `<div class="live-ind" role="status" data-state="searching"><span class="live-dot" aria-hidden="true"></span>${esc(t)}</div>` : '';
  }
  const t = liveStatusText(ev);
  return t ? `<div class="live-ind" role="status" data-state="${esc(ev.state)}"><span class="live-dot" aria-hidden="true"></span>${esc(t)}</div>` : '';
}
function liveSafeUrl(u) { return typeof u === 'string' && /^https?:\/\/[^\s"'<>]+$/i.test(u) && !/^https?:\/\/[^/]*@/i.test(u) ? u : null; }
function liveWhen(ms) { return ms ? new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
/** "published 2 h ago" / "date not given" / "rate for 2026-11-17" / "reading 1 min ago" / "your records". */
function livePublished(e) {
  if (e.kind === 'fx') return e.published_date ? `rate for ${e.published_date}` : 'date not given';
  if (e.kind === 'weather' || e.kind === 'system') return e.published_at ? `reading ${timeAgo(e.published_at)}` : 'reading taken now';
  if (e.kind === 'project' || e.kind === 'task') return e.published_at ? `recorded ${timeAgo(e.published_at)}` : 'your records';
  return e.published_at ? `published ${timeAgo(e.published_at)}` : 'date not given';
}
function liveBoxHtml(live, question = '') {
  if (!live || !live.state || live.state === 'none') return '';
  const key = liveStore.push(live) - 1, srcs = live.sources || [];
  const chips = srcs.map((e, i) => {
    const url = liveSafeUrl(e.url);
    return `<span class="lchip" data-fresh="${esc(e.freshness)}"><button type="button" class="lchip-main" data-live="${key}:${i}" aria-expanded="false" title="${esc(e.title)}">`
      + `<b class="lid">${esc(e.id)}</b><span class="lpub">${esc(e.publisher)}</span><span class="lage">${esc(livePublished(e))}</span></button>`
      + (url ? `<a class="lchip-open" href="${esc(url)}" target="_blank" rel="noopener noreferrer" aria-label="Open ${esc(e.id)} (${esc(e.publisher)}) in a new tab" title="Open the source">↗</a>` : '')
      + '</span>';
  }).join('');
  const retrieved = live.retrieved_at && (live.state === 'verified' || live.state === 'partial') ? ` · retrieved ${liveWhen(live.retrieved_at)}` : '';
  const notes = (live.notes || []).filter((n) => !(live.problems || []).includes(n) || live.state === 'partial');
  return `<div class="live-box" data-state="${esc(live.state)}"><div class="live-head"><span class="live-ind" data-state="${esc(live.state)}"><span class="live-dot" aria-hidden="true"></span>${esc(liveStatusText(live))}${esc(retrieved)}</span>`
    + (question ? `<button type="button" class="tool-btn live-refresh" data-live-q="${esc(question)}" aria-label="Fetch the live information again and answer again">↻ Refresh</button>` : '') + '</div>'
    + (chips ? `<div class="live-chips" role="group" aria-label="Sources">${chips}</div><div class="live-detail" hidden></div>` : '')
    + (notes.length ? `<div class="live-notes">${notes.map(esc).join(' · ')}</div>` : '') + '</div>';
}
/** The inline details panel for one source: snippet, published and retrieved times, freshness; the link opens a new tab. */
function liveDetailHtml(e) {
  const url = liveSafeUrl(e.url);
  const fresh = { live: 'a reading taken now', recent: 'recent', historical: 'may be out of date', cached: 'from a cached copy', unavailable: 'unavailable' }[e.freshness] || e.freshness;
  return `<div class="ld-title"><b class="lid">${esc(e.id)}</b> ${url ? `<a href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(e.title)}</a>` : esc(e.title)}</div>`
    + `<div class="ld-meta">${esc(e.publisher)} · ${esc(e.published_at ? `published ${liveWhen(e.published_at)}` : e.published_date ? `reference rate for ${e.published_date}` : 'date not given')} · retrieved ${esc(liveWhen(e.retrieved_at))} · ${esc(fresh)}</div>`
    + (e.snippet ? `<p class="ld-snippet">${esc(e.snippet)}</p>` : '<p class="ld-snippet muted">Headline only: no description was given.</p>')
    + (url ? `<div class="ld-url">${esc(url)}</div>` : '');
}
function toggleLiveDetail(btn) {
  const [k, i] = String(btn.dataset.live).split(':').map(Number), e = (liveStore[k]?.sources || [])[i];
  const panel = btn.closest('.live-box')?.querySelector('.live-detail');
  if (!e || !panel) return;
  const open = btn.getAttribute('aria-expanded') === 'true';
  btn.closest('.live-box').querySelectorAll('.lchip-main').forEach((b) => b.setAttribute('aria-expanded', 'false'));
  if (open) { panel.hidden = true; panel.innerHTML = ''; return; }
  panel.innerHTML = liveDetailHtml(e); panel.hidden = false; btn.setAttribute('aria-expanded', 'true');
}
/** 6.1 context indicator: what this answer could use from this computer (counts from the backend, never content). */
function contextHtml(c) {
  if (!c) return '';
  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const parts = [c.project ? `🗂 project “${c.project}”` : '', c.memories ? `🧠 ${plural(c.memories, 'saved memory', 'saved memories')}` : '',
    c.documents ? `📚 ${plural(c.documents, 'document excerpt', 'document excerpts')}` : '', c.history ? `↺ ${plural(c.history, 'earlier message', 'earlier messages')}` : ''].filter(Boolean);
  return `<div class="ctx-line" title="Context given to the model for this answer (from this computer only)">${parts.length ? `Context: ${parts.map(esc).join(' · ')}` : 'Context: none of your data'}</div>`;
}
/** 6.1 message tools: Copy, and Regenerate on the newest answer. */
const ICON_COPY = '<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"/></svg>';
const ICON_REGEN = '<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>';
function msgTools(answer, { text = '', regen = false } = {}) {
  const i = answerStore.push(answer) - 1;
  return `<div class="msg-tools" data-ans="${i}"><button type="button" class="tool-btn" data-copy="msg" aria-label="Copy this answer">${ICON_COPY}<span>Copy</span></button>`
    + (regen && text && state.engine !== 'device' ? `<button type="button" class="tool-btn msg-regen" data-regen-q="${esc(text)}" aria-label="Regenerate this answer">${ICON_REGEN}<span>Regenerate</span></button>` : '')
    + '</div>';
}
/** 6.1 follow-up suggestions, built from the answer itself (its headings and bold terms), no extra model call. */
function followUps(answer, focus) {
  answer = String(answer || '');
  if (answer.length < 160) return '';
  const terms = [];
  const add = (t) => { t = t.replace(/[*`_#:]+/g, '').trim(); if (t.length >= 3 && t.length <= 48 && !terms.some((x) => x.toLowerCase() === t.toLowerCase())) terms.push(t); };
  for (const m of answer.matchAll(/^\s*#{1,4}\s+(.+)$/gm)) add(m[1]);
  for (const m of answer.matchAll(/\*\*([^*\n]{3,48})\*\*/g)) add(m[1]);
  const qs = terms.slice(0, 2).map((t) => `Tell me more about ${t}`);
  qs.push(/coding|technical/i.test(focus || '') || answer.includes('```') ? 'How can I test this?' : 'Can you give a concrete example?');
  return `<div class="followups" role="group" aria-label="Suggested follow-ups">${qs.map((q) => `<button type="button" class="fu-chip" data-fu="${esc(q)}">${esc(q)}</button>`).join('')}</div>`;
}

/* ---------- 7d. SHELL (dev.8): theme, sidebar ----------
   Theme: system (default), light or dark, saved in this browser; index.html applies it before the first paint.
   Sidebar buttons open the existing sheets (Knowledge folder, Memory & history, System & data, Settings). */
const THEMES = ['system', 'light', 'dark'];
function themeNow() { try { return localStorage.getItem('abhify_theme') || 'system'; } catch { return 'system'; } }
function applyTheme(t) {
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t; else delete document.documentElement.dataset.theme;
  try { if (t === 'system') localStorage.removeItem('abhify_theme'); else localStorage.setItem('abhify_theme', t); } catch { /* storage blocked */ }
  const label = `Theme: ${t}`;
  $('#themeBtn').setAttribute('aria-label', label); $('#themeBtn').title = label;
  const dark = t === 'dark' || (t === 'system' && window.matchMedia?.('(prefers-color-scheme: dark)').matches);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark === false ? '#F5F6FA' : '#07080D');
}
/* 6.1 dialogs: Esc closes the topmost open sheet or popover (Settings, Customize Home, News sources, history, …); when a
   sheet opens, focus moves into it, and when it closes focus returns to the control that opened it. */
function topSheet() {
  const open = [...$$('.sheet-backdrop')].filter((x) => !x.classList.contains('hidden') && x.id !== 'setupSheet');
  return open.sort((a, b) => (+getComputedStyle(b).zIndex || 0) - (+getComputedStyle(a).zIndex || 0))[0] || null;
}
function bindDialogs() {
  const openers = new Map();
  const mo = new MutationObserver((list) => {
    for (const m of list) {
      const el = m.target, open = !el.classList.contains('hidden');
      if (open && !openers.has(el)) {
        openers.set(el, document.activeElement);
        requestAnimationFrame(() => {
          const box = el.querySelector('.sheet, .palette') || el;
          if (box.contains(document.activeElement)) return;
          const f = box.querySelector('input:not([type=hidden]):not([disabled]), select, textarea, button:not([disabled]), [href], [tabindex]:not([tabindex="-1"])');
          (f && !f.closest('[aria-live]') ? f : box).focus({ preventScroll: true });   // live regions re-render: focus the dialog itself
        });
      } else if (!open && openers.has(el)) {
        const back = openers.get(el); openers.delete(el);
        if (el.contains(document.activeElement) || document.activeElement === document.body) back?.focus?.({ preventScroll: true });
      }
    }
  });
  $$('.sheet-backdrop').forEach((x) => { mo.observe(x, { attributes: true, attributeFilter: ['class'] }); const box = x.querySelector('.sheet, .palette'); if (box && !box.hasAttribute('tabindex')) box.setAttribute('tabindex', '-1'); });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape' || e.defaultPrevented) return;
    const hp = $('#historyPop');
    if (hp && !hp.classList.contains('hidden')) { hp.classList.add('hidden'); $('#chatHistoryBtn').setAttribute('aria-expanded', 'false'); $('#chatHistoryBtn').focus(); return; }
    const top = topSheet();
    if (!top || top.classList.contains('as-page') || top.id === 'palette') return;   // pages and the palette have their own Esc
    e.preventDefault();
    if (top.id === 'settingsSheet') closeSettings(); else top.classList.add('hidden');
  });
}

function bindShell() {
  bindDialogs();
  applyTheme(themeNow());
  $('#themeBtn').addEventListener('click', () => { const t = THEMES[(THEMES.indexOf(themeNow()) + 1) % THEMES.length]; applyTheme(t); toast(`Theme: ${t}`); });
  $$('.side-btn[data-open]').forEach((b) => b.addEventListener('click', () => { closePages(); $('#' + b.dataset.open)?.click(); }));   // one page at a time
  let collapsed = false; try { collapsed = localStorage.getItem('abhify_side') === 'collapsed'; } catch { /* storage blocked */ }
  const setCollapsed = (on) => {
    $('#app').classList.toggle('side-collapsed', on); $('#sideCollapse').setAttribute('aria-pressed', String(on));
    $('#sideCollapse').querySelector('span').textContent = on ? 'Expand' : 'Collapse';
    try { localStorage.setItem('abhify_side', on ? 'collapsed' : 'open'); } catch { /* storage blocked */ }
  };
  setCollapsed(collapsed);
  $('#sideCollapse').addEventListener('click', () => setCollapsed(!$('#app').classList.contains('side-collapsed')));
}

/* ---------- 7g. AI ACTION CENTER (dev.8) ----------
   Reusable actions on any answer. Each one is a follow-up question sent through the normal chat (same model, same
   checks, same history), so nothing here is a separate or simulated AI. Answers are clipped to fit the 4,000-character limit. */
const AI_ACTIONS = [
  ['summarize', 'Summarize', (t) => `Summarize this in 3 to 5 bullet points:\n\n${t}`],
  ['simplify', 'Explain simply', (t) => `Explain this more simply, for a beginner, with one everyday example:\n\n${t}`],
  ['extract', 'Key points', (t) => `Extract the key facts and terms from this as a short list:\n\n${t}`],
  ['translate', 'Translate…', (t, lang) => `Translate this into ${lang}. Keep code and names unchanged:\n\n${t}`],
  ['quiz', 'Quiz me', (t) => `Write 3 multiple-choice questions (A–D) that test understanding of this, then list the answers with one-line explanations:\n\n${t}`],
  ['cards', 'Flashcards', (t) => `Make 5 flashcards from this, one per line as "Q: … | A: …":\n\n${t}`],
  ['checklist', 'Checklist', (t) => `Turn this into a short, practical checklist of steps ("- [ ] …"):\n\n${t}`],
  ['rewrite', 'Rewrite clearly', (t) => `Rewrite this more clearly and concisely, keeping every fact:\n\n${t}`],
];
const answerStore = [];
function actionBar(answer) {
  const i = answerStore.push(answer) - 1;
  return `<div class="ai-actions" role="group" aria-label="Actions on this answer" data-ans="${i}">` +
    AI_ACTIONS.map(([id, label]) => `<button type="button" class="act" data-act="${id}">${label}</button>`).join('') + '</div>';
}
function runAction(id, answer) {
  const a = AI_ACTIONS.find((x) => x[0] === id); if (!a) return;
  let lang = '';
  if (id === 'translate') { lang = (prompt('Translate into which language?', 'Hindi') || '').trim().slice(0, 40); if (!lang) return; }
  sendChat(a[2](String(answer).slice(0, 3300), lang));
}

/* ---------- 7h. PERFORMANCE (dev.8) ---------- */
let perfTimer = null;
async function samplePerf() {
  try {
    const p = await api('/system/performance', { timeout: 8000 });
    const g = p.gpus[0];
    const tile = (acc, k, v, sub) => `<div class="perf-tile" data-acc="${acc}"><span class="k">${k}</span><b>${v}</b><small class="muted">${sub}</small>
      <span class="meter"><i style="width:${Math.min(100, Math.max(0, parseFloat(v) || 0))}%"></i></span></div>`;
    $('#perfStats').innerHTML = tile('chat', 'CPU', `${Math.round(p.cpu_percent)}%`, 'all cores') +
      tile('docs', 'RAM', `${Math.round(p.ram_percent)}%`, `${p.ram_used_gb} of ${p.ram_total_gb} GB`) +
      (g ? tile('study', 'GPU', `${g.util_percent}%`, esc(g.name)) + tile('memory', 'VRAM', `${Math.round(g.vram_used_gb / g.vram_total_gb * 100)}%`, `${g.vram_used_gb} of ${g.vram_total_gb} GB`)
        : '<div class="perf-tile"><span class="k">GPU</span><b>–</b><small class="muted">No NVIDIA GPU reported (nvidia-smi not found)</small></div>');
    $('#perfLoaded').innerHTML = !p.ollama ? '<div class="muted small">Ollama isn\'t reachable.</div>'
      : p.loaded.length ? p.loaded.map((m) => `<div class="list-item"><div class="text"><b>${esc(m.name)}</b>${m.chat ? '' : ' <span class="tag">embedding</span>'}
          <div class="meta">${m.vram_gb} GB in GPU memory of ${m.size_gb} GB · unloads ${m.unloads_at ? new Date(m.unloads_at).toLocaleTimeString() : 'when Ollama decides'}</div></div></div>`).join('')
      : '<div class="muted small">No model is loaded right now (Ollama loads one on the next question).</div>';
    const l = p.last_inference;
    $('#perfLast').innerHTML = l ? `<b>${esc(l.model)}</b> · ${l.tokens_per_second} tokens/s · ${l.output_tokens} tokens · ${l.total_seconds} s total` +
      (l.load_seconds > 0.5 ? ` (of which ${l.load_seconds} s loading the model)` : '') : 'No answer yet since the backend started.';
  } catch (e) { $('#perfStats').innerHTML = `<p class="small err">${esc(e.message)}</p>`; }
}
function openPerf() {
  closePages(); openSheet('perfSheet'); samplePerf();                // one page at a time, from any path (Settings, launcher, System)
  clearInterval(perfTimer); perfTimer = setInterval(() => { if ($('#perfSheet').classList.contains('hidden')) { clearInterval(perfTimer); perfTimer = null; } else samplePerf(); }, 3000);
}

/* ---------- 7f. THE AI ORB (dev.8) ----------
   The orb shows what the assistant is really doing: listening (voice), thinking (waiting for an answer), ready, or
   unavailable (the AI backend isn't reachable). Updated where those states change; CSS animates it (off with reduced motion). */
function orbState() {
  if (voice.listening) return ['listening', 'Listening'];
  if (state.busy.chat) return ['thinking', 'Thinking'];
  if (state.engine !== 'device' && !state.online) return ['error', 'AI unavailable'];
  return ['idle', 'Ready'];
}
function updateOrb() {
  const [s, label] = orbState();
  $$('.orb[id]').forEach((o) => { o.dataset.state = s; o.setAttribute('aria-label', `Abhify.AI: ${label.toLowerCase()}`); });
  const t = $('#homeOrbState'); if (t) t.textContent = label;
}

/* ---------- 7l. BRAND LAUNCHER (dev.8) ----------
   Clicking the Abhify brand (sidebar; header logo on phones) opens every destination, grouped Workspace / AI System /
   Application. Each entry opens the page that already exists; opening the launcher itself starts nothing (no model load,
   no request), it only reads what the app already knows for the status line. */
const LAUNCH = {
  home: () => switchTab('home'), chat: () => switchTab('chat'), interview: () => switchTab('interview'), mcq: () => switchTab('mcq'),
  news: () => switchTab('news'), tasks: () => openTasks(), projects: () => openProjects(), documents: () => { closePages(); $('#openKnowledge').click(); },
  memory: () => { closePages(); $('#openMemory').click(); }, models: () => { closePages(); $('#rerunSetup').click(); },
  performance: () => openPerf(), system: () => { closePages(); $('#openSystem').click(); }, settings: () => $('#settingsBtn').click(),
};
let launchOpener = null;
function launcherCurrent() {
  const pages = { tasksSheet: 'tasks', projectsSheet: 'projects', newsReaderSheet: 'news', knowledgeSheet: 'documents', memorySheet: 'memory', perfSheet: 'performance', systemSheet: 'system', setupSheet: 'models', settingsSheet: 'settings' };
  const open = Object.keys(pages).find((id) => $('#' + id) && !$('#' + id).classList.contains('hidden'));
  return open ? pages[open] : current;
}
function openLauncher(opener) {
  launchOpener = opener;
  const now = launcherCurrent();
  $$('.launch-item').forEach((b) => { const on = b.dataset.go === now; b.classList.toggle('active', on); b.setAttribute('aria-current', on ? 'page' : 'false'); });
  const v = modelChipView();
  $('#launchStatus').textContent = `${v.label} · ${$('#statusText').textContent}`;      // existing state only
  $('#launcher').classList.remove('hidden');
  $$('#brandLauncher, #brandLauncherMobile').forEach((b) => b.setAttribute('aria-expanded', 'true'));
  ($('.launch-item.active') || $('.launch-item')).focus();
}
function closeLauncher(focusBack = true) {
  if ($('#launcher').classList.contains('hidden')) return;
  $('#launcher').classList.add('hidden');
  $$('#brandLauncher, #brandLauncherMobile').forEach((b) => b.setAttribute('aria-expanded', 'false'));
  if (focusBack) launchOpener?.focus();
}
function bindLauncher() {
  const toggle = (e) => { e.stopPropagation(); $('#launcher').classList.contains('hidden') ? openLauncher(e.currentTarget) : closeLauncher(); };
  $('#brandLauncher').addEventListener('click', toggle);
  $('#brandLauncherMobile').addEventListener('click', toggle);
  $('#launcher').addEventListener('click', (e) => {
    const b = e.target.closest('[data-go]'); if (!b) return;
    closeLauncher(false); LAUNCH[b.dataset.go]?.();
  });
  document.addEventListener('click', (e) => { if (!e.target.closest('#launcher, #brandLauncher, #brandLauncherMobile')) closeLauncher(false); });
  $('#launcher').addEventListener('keydown', (e) => {
    const all = [...$$('.launch-item')], i = all.indexOf(document.activeElement);
    if (e.key === 'Escape') { e.preventDefault(); closeLauncher(); }
    else if (['ArrowDown', 'ArrowRight'].includes(e.key)) { e.preventDefault(); all[(i + 1) % all.length].focus(); }
    else if (['ArrowUp', 'ArrowLeft'].includes(e.key)) { e.preventDefault(); all[(i - 1 + all.length) % all.length].focus(); }
  });
  $$('.qa').forEach((b) => b.addEventListener('click', () => ({ chat: () => switchTab('chat'), task: () => openTasks(), study: () => switchTab('mcq'), news: () => switchTab('news') })[b.dataset.qa]()));
}

/* ---------- 7m. PROJECTS (stability milestone) ----------
   A project groups existing documents (by reference), its chats and its AI tasks, with its own instructions. Its chat
   and tasks use only its documents (backend: projects.py). Deleting a project never deletes documents or chats. */
const proj = { list: [], current: null };
async function openProjects() {
  closePages(); openSheet('projectsSheet');
  $('#projView').classList.add('hidden'); $('#projList').classList.remove('hidden');
  if (!proj.list.length) $('#projCards').innerHTML = skeleton(2);
  try { proj.list = (await api('/projects')).projects; renderProjects(); }
  catch (e) { $('#projCards').innerHTML = stateHtml('error', "Couldn't load your projects", e.message === 'offline' ? 'Projects need the Abhify.AI app on this computer.' : e.message); }
}
function renderProjects() {
  const all = $('#projShowArchived').checked, list = proj.list.filter((p) => all || !p.archived);
  $('#projCards').innerHTML = list.length ? list.map((p) => `<button class="proj-card" data-pid="${esc(p.id)}" data-acc="know">
      <b>${p.archived ? '🗄' : '🗂'} ${esc(p.name)}</b><small class="muted">${p.documents.length} document${p.documents.length === 1 ? '' : 's'} · ${p.conversations.length} chat${p.conversations.length === 1 ? '' : 's'}${p.archived ? ' · archived' : ''}</small></button>`).join('')
    : stateHtml('empty', 'No projects yet', 'Create one above: it keeps its own instructions, documents and chats.');
}
async function showProject(pid) {
  let p; try { p = await api(`/projects/${encodeURIComponent(pid)}`); } catch (e) { toast(e.message); return; }
  proj.current = p;
  $('#projList').classList.add('hidden'); $('#projView').classList.remove('hidden');
  const docs = p.documents_status.map((d) => `<li>${esc(d.name)} ${d.indexed ? '' : '<span class="small err">(not in the knowledge index any more)</span>'}
    <button class="act" data-pact="unlink" data-name="${esc(d.name)}" aria-label="Remove ${esc(d.name)} from the project">Remove from project</button></li>`).join('');
  $('#projView').innerHTML = `<div class="task-card" data-acc="know">
    <div class="task-head"><div><b>${esc(p.name)}</b><br><small class="muted">${p.archived ? 'Archived · ' : ''}${p.documents.length} documents · ${p.chats.length} chats · ${p.tasks.length} AI tasks</small></div>
      <button class="btn btn-ghost btn-sm" data-pact="back">All projects</button></div>
    ${p.archived ? '<p class="small muted">Archived: not offered in the Task Runner. Restore it to work on it again.</p>' : ''}
    <div class="proj-actions">
      <button class="btn btn-primary btn-sm" data-pact="chat">💬 Chat in this project</button>
      <button class="btn btn-ghost btn-sm" data-pact="task">✅ AI task</button>
      <button class="btn btn-ghost btn-sm" data-pact="study">🗓 Study plan</button>
      <button class="btn btn-ghost btn-sm" data-pact="quiz">❔ Quiz</button>
      <button class="btn btn-ghost btn-sm" data-pact="overview">📋 Status overview</button></div>
    <h3>Agent <span class="muted small">(plans from your records; changes only what you approve)</span></h3>
    <div class="proj-actions">
      <button class="btn btn-ghost btn-sm" data-pact="agent" data-wf="status">Status &amp; blockers</button>
      <button class="btn btn-ghost btn-sm" data-pact="agent" data-wf="priorities">Suggest priorities</button>
      <button class="btn btn-ghost btn-sm" data-pact="agent" data-wf="docs">Related documents</button>
      <button class="btn btn-ghost btn-sm" data-pact="agent" data-wf="unfinished">Unfinished work</button>
      <button class="btn btn-ghost btn-sm" data-pact="agent" data-wf="duplicates">Possible duplicates</button></div>
    <div id="projAgent" class="agent-out" aria-live="polite"></div>
    <h3>Documents</h3><ul class="list small">${docs || '<li class="muted">No documents yet.</li>'}</ul>
    <div class="row"><select id="projAddDoc" class="select" aria-label="Add a document from your knowledge folder"></select><button class="btn btn-ghost btn-sm" data-pact="link">Add</button></div>
    <p class="muted small">Adding links a document from your knowledge folder (nothing is copied); removing it never deletes the document.</p>
    <h3>Where you left off</h3>
    ${p.brief ? `<p class="small" style="white-space:pre-line">${esc(p.brief.split('\n').slice(1).join('\n').replace(/^- /gm, '• '))}</p>`
      : '<p class="muted small">Nothing recorded yet. Add the objective, status and next actions below; project chats then use them (from your records only).</p>'}
    <details class="proj-status"><summary>Project status (written by you)</summary>
      ${[['objective', 'Objective', 1], ['stack', 'Technology', 1], ['current', 'Current status', 2], ['milestones', 'Completed milestones (one per line)', 2],
         ['issues', 'Open issues / blockers (one per line)', 2], ['next', 'Next actions (one per line)', 2], ['decisions', 'Decisions (one per line)', 2]].map(([k, label, rows]) => {
        const v = (p.status || {})[k]; const text = Array.isArray(v) ? v.join('\n') : (v || '');
        return `<label class="field"><span class="small">${label}</span><textarea class="input" rows="${rows}" data-pstatus="${k}" maxlength="${rows > 1 ? 2000 : 400}">${esc(text)}</textarea></label>`; }).join('')}
    </details>
    <h3>Instructions</h3>
    <textarea id="projInstr" class="input" rows="4" maxlength="1500" placeholder="e.g. Answer at master's level and cite the documents.">${esc(p.instructions || '')}</textarea>
    <label class="field check"><input type="checkbox" id="projMem" ${p.use_memory ? 'checked' : ''}><span>Use my personal memory in this project's chats (only when memory is on)</span></label>
    <div class="row"><button class="btn btn-primary btn-sm" data-pact="save">Save</button></div>
    ${p.chats.length ? `<h3>Chats</h3><ul class="list small">${p.chats.map((c) => `<li>${esc(c.title)} <button class="act" data-pact="open-chat" data-cid="${esc(c.id)}">Continue</button></li>`).join('')}</ul>` : ''}
    ${p.tasks.length ? `<h3>AI tasks</h3><ul class="list small">${p.tasks.map((t) => `<li>${esc(t.title)} · ${esc(t.status)} <button class="act" data-pact="open-task" data-tid="${esc(t.id)}">Open</button></li>`).join('')}</ul>` : ''}
    <div class="proj-actions">
      <button class="btn btn-ghost btn-sm" data-pact="rename">Rename</button>
      <button class="btn btn-ghost btn-sm" data-pact="${p.archived ? 'restore' : 'archive'}">${p.archived ? 'Restore' : 'Archive'}</button>
      <button class="btn btn-ghost btn-sm" data-pact="export">Export</button>
      <button class="btn btn-ghost btn-sm danger" data-pact="delete">Delete…</button></div></div>`;
  try {
    const { documents } = await api('/tasks/sources');
    const have = new Set(p.documents);
    $('#projAddDoc').innerHTML = documents.filter((d) => !have.has(d)).map((d) => `<option>${esc(d)}</option>`).join('') || '<option value="">All your documents are in this project</option>';
  } catch { /* list stays empty */ }
}
async function projectAction(a, el) {
  const p = proj.current; if (!p && a !== 'back') return;
  const id = p && encodeURIComponent(p.id);
  try {
    if (a === 'back') return openProjects();
    if (a === 'overview-back') return showProject(p.id);
    if (a === 'chat') { closePages(); newChat(p); switchTab('chat'); toast(`Chatting in ${p.name}`); return; }
    if (a === 'open-chat') { closePages(); newChat(p); openConversation(el.dataset.cid); return; }   // 6.1: history loaded once (was twice)
    if (a === 'task' || a === 'study' || a === 'quiz') return openTasks({ project: p.id, template: a === 'task' ? 'summary' : a });
    if (a === 'open-task') { await openTasks(); return showTask(el.dataset.tid); }
    if (a === 'overview') return showProjectOverview(p.id);
    if (a === 'agent') return agentPlan(p.id, el.dataset.wf);
    if (a === 'agent-apply') return agentApply(p.id);
    if (a === 'agent-undo') return agentUndo(p.id);
    if (a === 'link') { const n = $('#projAddDoc').value; if (!n) return; await api(`/projects/${id}/documents`, { method: 'POST', body: { names: [n] } }); }
    if (a === 'unlink') await api(`/projects/${id}/documents?name=${encodeURIComponent(el.dataset.name)}`, { method: 'DELETE' });
    if (a === 'save') {
      const status = {}; let changed = false;
      $$('[data-pstatus]').forEach((t) => { const k = t.dataset.pstatus; const lists = ['milestones', 'issues', 'next', 'decisions'];
        const old = (p.status || {})[k]; const was = Array.isArray(old) ? old.join('\n') : (old || '');
        if (t.value.trim() !== was.trim()) changed = true;
        status[k] = lists.includes(k) ? t.value.split('\n').map((x) => x.trim()).filter(Boolean).slice(0, 12) : t.value.trim().slice(0, 400); });
      await api(`/projects/${id}`, { method: 'PATCH', body: { instructions: $('#projInstr').value, use_memory: $('#projMem').checked, ...(changed ? { status } : {}) } });
      toast('Project saved');
    }
    if (a === 'rename') { const n = prompt('New name for the project', p.name); if (!n?.trim()) return; await api(`/projects/${id}`, { method: 'PATCH', body: { name: n.trim() } }); if (state.project?.id === p.id) setChatProject({ id: p.id, name: n.trim() }); }
    if (a === 'archive' || a === 'restore') { await api(`/projects/${id}/archive`, { method: 'POST', body: { archived: a === 'archive' } }); if (a === 'archive' && state.project?.id === p.id) setChatProject(null); }
    if (a === 'export') {
      const res = await fetch(`${state.apiBase}/projects/${id}/export`, { headers: authHeaders() });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const url = URL.createObjectURL(await res.blob());
      Object.assign(document.createElement('a'), { href: url, download: `${p.name.replace(/[^\w -]+/g, '').trim().slice(0, 50) || 'project'}.md` }).click();
      setTimeout(() => URL.revokeObjectURL(url), 2000); return;
    }
    if (a === 'delete') {
      if (!confirm(`Delete the project "${p.name}"?\n\nOnly the project is removed. Its documents stay in your knowledge folder and its chats and AI tasks stay in your history.`)) return;
      await api(`/projects/${id}?confirm=true`, { method: 'DELETE' });
      if (state.project?.id === p.id) setChatProject(null);
      toast('Project deleted (documents and chats kept)'); return openProjects();
    }
    showProject(p.id);
  } catch (e) { toast(e.message); }
}
/* 5.6: read-only Status overview (GET /projects/{pid}/overview). No edit controls: everything shown is what was recorded. */
function overviewHtml(o) {
  const list = (items, empty) => items.length ? `<ul class="list small">${items.map((x) => `<li>${esc(x.text)} <span class="src">· ${esc(x.source)}</span></li>`).join('')}</ul>`
    : `<p class="muted small">${esc(empty)}</p>`;
  const st = o.status, act = o.activity;
  const box = (title, body) => `<div class="ov-box"><h4>${title}</h4>${body}</div>`;
  return `<div class="task-card" data-acc="know">
    <div class="task-head"><div><b>📋 ${esc(o.name)} · Status overview</b><br><small class="muted">${o.archived ? 'Archived · ' : ''}Read-only${st.age ? ` · status recorded ${esc(st.age)}` : ' · no status recorded yet'}</small></div>
      <button class="btn btn-ghost btn-sm" data-pact="overview-back">Back to project</button></div>
    ${o.instructions_excerpt ? `<p class="small"><b>Instructions:</b> ${esc(o.instructions_excerpt)}</p>` : ''}
    ${st.recorded ? `<p class="small">${[st.objective && `<b>Objective:</b> ${esc(st.objective)}`, st.stack && `<b>Technology:</b> ${esc(st.stack)}`, st.current && `<b>Current:</b> ${esc(st.current)}`].filter(Boolean).join('<br>')}</p>`
      : '<p class="muted small">Nothing recorded yet. Fill in "Project status (written by you)" on the project page.</p>'}
    <div class="ov-grid">
      ${box('✅ Milestones (recorded progress)', list(o.milestones, 'None recorded.'))}
      ${box('⛔ Issues / blockers', list(o.issues, 'None recorded.'))}
      ${box('🧭 Decisions', list(o.decisions, 'None recorded.'))}
      ${box('➡️ Next actions', list(o.next, 'None recorded.'))}
      ${box('📄 Documents', o.documents.length ? `<ul class="list small">${o.documents.map((d) => `<li>${esc(d.name)}${d.indexed ? '' : ' <span class="small err">(not in the knowledge index any more)</span>'}</li>`).join('')}</ul>` : '<p class="muted small">No linked documents.</p>')}
      ${box('🕘 Recent activity', `<p class="small">${act.conversations} chat${act.conversations === 1 ? '' : 's'}${act.conversations_age ? ` · last active ${esc(act.conversations_age)}` : ''}</p>`
        + (act.tasks.length ? `<ul class="list small">${act.tasks.map((t) => `<li>${esc(t.title)} <span class="src">· AI task · ${esc(t.status)}</span></li>`).join('')}</ul>` : '<p class="muted small">No AI tasks for this project.</p>'))}
    </div>
    <h3>Next possible actions</h3>${list(o.next_possible_actions, 'Nothing to suggest: no open issues or next actions are recorded.')}
    <p class="muted small">${esc(o.note)}</p></div>`;
}
async function showProjectOverview(pid) {
  let o; try { o = await api(`/projects/${encodeURIComponent(pid)}/overview`); } catch (e) { toast(e.message); return; }
  $('#projList').classList.add('hidden'); $('#projView').classList.remove('hidden');
  $('#projView').innerHTML = overviewHtml(o);
}
/* 5.6 Desktop Agent 2.0: plan (read-only) -> tick proposals -> "Approve N" -> verified result -> Undo. */
const agent = { run: null, applied: null };
function agentPlanHtml(r) {
  const secs = r.sections.map((s) => `<b>${esc(s.title)}</b><ul class="list small">${s.lines.map((l) => `<li>${esc(l.text)} <span class="src">· ${esc(l.source)}</span></li>`).join('')}</ul>`).join('')
    || '<p class="muted small">Nothing in this project\'s records for this check.</p>';
  const props = r.proposals.length ? `<h4>Proposed changes <span class="muted small">(nothing changes until you approve)</span></h4>`
    + r.proposals.map((x) => `<label class="agent-prop small"><input type="checkbox" data-agent-item="${esc(x.id)}" checked><span>${esc(x.label)} <span class="src">· ${esc(x.source)} · ${esc(x.tool)} (${x.kind === 'REVERSIBLE' ? 'can be undone' : esc(x.kind)})</span></span></label>`).join('')
    + `<div class="row"><button class="btn btn-primary btn-sm" data-pact="agent-apply" id="agentApprove">Approve ${r.proposals.length}</button></div>` : '';
  return `<div class="task-card"><div><b>${esc(r.title)}</b><span class="muted small"> · read-only plan</span></div>${secs}${props}</div>`;
}
async function agentPlan(pid, wf) {
  const out = $('#projAgent'); out.innerHTML = skeleton(2);
  try { agent.run = await api('/agent/plan', { method: 'POST', body: { project_id: pid, workflow: wf } }); agent.applied = null; }
  catch (e) { out.innerHTML = `<p class="small err">${esc(e.message)}</p>`; return; }
  out.innerHTML = agentPlanHtml(agent.run);
  out.onchange = () => { const n = $$('[data-agent-item]:checked').length; const b = $('#agentApprove'); if (b) { b.textContent = `Approve ${n}`; b.disabled = !n; } };
}
async function agentApply(pid) {
  const ids = [...$$('[data-agent-item]:checked')].map((x) => x.dataset.agentItem); if (!ids.length || !agent.run) return;
  let r; try { r = await api('/agent/apply', { method: 'POST', body: { project_id: pid, run_id: agent.run.run_id, item_ids: ids, approved: true } }); }
  catch (e) { toast(e.message); return; }
  agent.applied = r;
  const label = (id) => (agent.run.proposals.find((x) => x.id === id) || {}).label || id;
  $('#projAgent').innerHTML = `<div class="task-card"><b>Result</b><ul class="list small">${r.results.map((x) => `<li>${x.applied ? (x.verified ? '✅' : '⚠️') : '•'} ${esc(label(x.id))} <span class="src">· ${esc(x.note || '')}</span></li>`).join('')}</ul>
    ${r.can_undo ? '<div class="row"><button class="btn btn-ghost btn-sm" data-pact="agent-undo">Undo</button></div>' : ''}</div>`;
  toast(`${r.applied} change${r.applied === 1 ? '' : 's'} applied and checked`);
}
async function agentUndo(pid) {
  if (!agent.run) return;
  let r; try { r = await api('/agent/undo', { method: 'POST', body: { run_id: agent.run.run_id } }); } catch (e) { toast(e.message); return; }
  await showProject(pid);
  $('#projAgent').innerHTML = `<p class="small">↩ Undone: ${r.reverted.length} change${r.reverted.length === 1 ? '' : 's'}${r.reverted.every((x) => x.verified) ? ' (checked in the saved project)' : ''}.</p>`;
}
function bindProjects() {
  $('#openProjects').addEventListener('click', openProjects);
  $('#projectsClose').addEventListener('click', () => closeSheet('projectsSheet'));
  $('#projShowArchived').addEventListener('change', renderProjects);
  $('#projNew').addEventListener('submit', async (e) => {
    e.preventDefault(); const n = $('#projName').value.trim(); if (!n) return;
    try { const p = await api('/projects', { method: 'POST', body: { name: n } }); $('#projName').value = ''; showProject(p.id); } catch (err) { toast(err.message); }
  });
  $('#projCards').addEventListener('click', (e) => { const b = e.target.closest('[data-pid]'); if (b) showProject(b.dataset.pid); });
  $('#projView').addEventListener('click', (e) => { const b = e.target.closest('[data-pact]'); if (b) projectAction(b.dataset.pact, b); });
  $('#projectChip').addEventListener('click', () => { setChatProject(null); newChat(); });
  $$('[data-settings-go]').forEach((b) => b.addEventListener('click', () => { closeSheet('settingsSheet'); LAUNCH[b.dataset.settingsGo]?.(); }));
}

/* ---------- 7k. AI TASK RUNNER (dev.8) ----------
   Templates and execution live in the backend (task_runner.py: /tasks…). The page picks a template, a source and a
   model, starts the task and follows its steps until it finishes. Saving to Knowledge always asks first. */
const tasks = { templates: [], sources: null, tpl: null, srcKind: null, current: null, timer: null, preset: null };
/** [preset] {template, project}: opened from a project page, with that project's documents as the source. */
async function openTasks(preset = null) {
  tasks.preset = preset?.project ? preset : null;
  closePages(); openSheet('tasksSheet');
  try {
    if (!tasks.templates.length) tasks.templates = (await api('/tasks/templates')).templates;
    tasks.sources = await api('/tasks/sources');
    if (!state.models.loaded) await loadModels();
  } catch (e) {                                                   // 6.1: the shared error state, with a way to try again
    $('#taskTemplates').innerHTML = stateHtml('error', "Couldn't load the Task Runner", e.message === 'offline' ? 'The Task Runner needs the Abhify.AI app on this computer.' : e.message)
      + '<div class="row state-retry"><button type="button" class="btn btn-ghost btn-sm" data-retry-tasks>↻ Try again</button></div>';
    $('#taskTemplates').querySelector('[data-retry-tasks]').onclick = async () => { await checkConnection(); openTasks(preset); };
    $('#taskHistory').innerHTML = '';
    return;
  }
  const acc = { summary: 'docs', study: 'study', quiz: 'career', news: 'know', custom: 'chat' };
  $('#taskTemplates').innerHTML = tasks.templates.map((t) => `<button class="task-tpl" role="radio" aria-checked="${tasks.tpl === t.id}" data-tpl="${t.id}" data-acc="${acc[t.id]}">
      <b>${esc(t.title)}</b><small>${t.steps.map(esc).join(' → ')}</small></button>`).join('');
  $('#taskModel').innerHTML = `<option value="">Default (${esc(state.models.def || 'configured model')})</option>` +
    state.models.list.map((m) => `<option value="${esc(m.name)}" ${state.chatModel === m.name ? 'selected' : ''}>${esc(m.name)}${m.parameter_size ? ` · ${esc(m.parameter_size)}` : ''}</option>`).join('');
  renderTaskHistory();
  if (tasks.preset) { $('#taskView').classList.add('hidden'); pickTemplate(tasks.preset.template || 'summary'); }
}
function pickTemplate(id) {
  tasks.tpl = id; const t = tasks.templates.find((x) => x.id === id);
  $$('.task-tpl').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.tpl === id)));
  $('#taskForm').classList.remove('hidden'); $('#taskReqField').classList.toggle('hidden', id !== 'custom');
  $('#taskQuizOpts').classList.toggle('hidden', id !== 'quiz');
  const label = { document: 'Document', project: 'Project', news: 'News', text: 'Text' };
  const kinds = t.sources.includes('document') && (tasks.sources?.projects || []).length ? ['document', 'project', ...t.sources.slice(1)] : t.sources;
  $('#taskSrcKinds').innerHTML = kinds.map((k) => `<button class="chip" role="radio" data-kind="${k}">${label[k]}</button>`).join('');
  pickSourceKind(tasks.preset && kinds.includes('project') ? 'project' : kinds[0]);
}
function pickSourceKind(k) {
  tasks.srcKind = k;
  $$('#taskSrcKinds .chip').forEach((b) => { b.classList.toggle('active', b.dataset.kind === k); b.setAttribute('aria-checked', String(b.dataset.kind === k)); });
  const docs = tasks.sources?.documents || [];
  $('#taskDoc').classList.toggle('hidden', k !== 'document');
  $('#taskDoc').innerHTML = docs.length ? docs.map((d) => `<option>${esc(d)}</option>`).join('') : '<option value="">No documents in your knowledge folder yet</option>';
  const prj = tasks.sources?.projects || [];
  $('#taskProject').classList.toggle('hidden', k !== 'project');
  $('#taskProject').innerHTML = prj.map((p) => `<option value="${esc(p.id)}" ${tasks.preset?.project === p.id ? 'selected' : ''}>${esc(p.name)} · ${p.documents} document${p.documents === 1 ? '' : 's'}</option>`).join('');
  $('#taskNews').classList.toggle('hidden', k !== 'news');
  $('#taskNews').innerHTML = (tasks.sources?.news_categories || []).filter((c) => c.id !== 'local').map((c) =>
    `<button class="chip${c.id === 'foryou' ? ' active' : ''}" data-ncat="${esc(c.id)}">${esc(c.label)}</button>`).join('');
  $('#taskText').classList.toggle('hidden', k !== 'text');
}
async function startTask() {
  $('#taskFormErr').textContent = '';
  const source = { type: tasks.srcKind };
  if (tasks.srcKind === 'document') { source.name = $('#taskDoc').value; if (!source.name) { $('#taskFormErr').textContent = 'Add a document to your knowledge folder first.'; return; } }
  if (tasks.srcKind === 'project') { source.project_id = $('#taskProject').value; if (!source.project_id) { $('#taskFormErr').textContent = 'Create a project first.'; return; } }
  if (tasks.srcKind === 'news') source.categories = [...$$('#taskNews .chip.active')].map((b) => b.dataset.ncat);
  if (tasks.srcKind === 'text') { source.text = $('#taskText').value; source.label = 'Pasted text'; }
  if ($('#taskStart').disabled) return;                                // one submission per click, however fast
  $('#taskStart').disabled = true;
  try {
    const body = { template: tasks.tpl, request: $('#taskReq').value, source, model: $('#taskModel').value || null };
    if (tasks.tpl === 'quiz') body.options = { count: +$('#taskQCount').value, difficulty: $('#taskQLevel').value };
    if (source.project_id) body.project_id = source.project_id;                 // listed in the project's AI tasks
    const t = await api('/tasks', { method: 'POST', body });
    await api(`/tasks/${t.id}/run`, { method: 'POST' });
    showTask(t.id);
  } catch (e) { $('#taskFormErr').textContent = e.message; }
  finally { $('#taskStart').disabled = false; }
}
/** The whole task (every finished result and its sources) as Markdown, for Export. */
function taskMarkdown(t) {
  const refs = (t.source_refs || []).map((r) => `- ${r.label}${r.url ? ` (${r.publisher || ''}) ${r.url}` : ''}`).join('\n');
  const steps = t.steps.filter((s) => s.ai && s.result).map((s) => `## ${s.title}\n\n${s.quiz
    ? s.quiz.map((q, i) => `${i + 1}. ${q.q}\n${q.options.map((o, j) => `   ${'ABCDEF'[j]}) ${o}`).join('\n')}\n   Answer: ${'ABCDEF'[q.answer]}. ${q.explanation}`).join('\n\n')
    : s.result}`).join('\n\n');
  return `# ${t.title}\n\n_${new Date(t.created_at).toLocaleString()} · model ${t.model || 'default'}_\n\n${steps}\n\n## Sources\n${refs || '- (none)'}\n\n_Generated by Abhify.AI Task Runner._\n`;
}
const STEP_ICON = { done: '✓', running: '…', failed: '!', cancelled: '–', pending: '·' };
async function showTask(id) {
  clearTimeout(tasks.timer);
  let t; try { t = await api(`/tasks/${id}`); } catch (e) { toast(e.message); return; }
  tasks.current = t;
  const running = t.status === 'running' || t.status === 'ready';
  const statusText = { ready: 'Starting…', running: 'Running', completed: 'Completed', failed: 'Stopped: a step failed', cancelled: 'Cancelled', draft: 'Draft' }[t.status];
  const refs = (t.source_refs || []).map((r) => r.url ? `<li><a href="${esc(r.url)}" target="_blank" rel="noopener noreferrer">${esc(r.label)}</a> <span class="muted small">${esc(r.publisher || '')}</span></li>` : `<li>${esc(r.label)}</li>`).join('');
  $('#taskView').classList.remove('hidden');
  $('#taskView').innerHTML = `<div class="task-card" data-acc="chat">
    <div class="task-head"><div><b>${esc(t.title)}</b><br><small class="muted">${esc(statusText)} · model ${esc(t.model || 'default')}</small></div>
      <div class="row-tight">${running ? '<button class="btn btn-ghost btn-sm" data-tact="cancel">Cancel</button>' : ''}
      ${t.status === 'failed' || t.status === 'cancelled' ? '<button class="btn btn-primary btn-sm" data-tact="resume">Retry</button>' : ''}
      ${!running && t.steps.some((s) => s.ai && s.result) ? '<button class="btn btn-ghost btn-sm" data-tact="export" title="Download the results as Markdown">Export</button>' : ''}</div></div>
    <div class="meter" role="progressbar" aria-valuenow="${t.progress}" aria-valuemin="0" aria-valuemax="100" aria-label="Task progress"><i style="width:${t.progress}%"></i></div>
    ${t.error ? `<p class="small err">${esc(t.error)}</p>` : ''}
    <ol class="task-steps">${t.steps.map((s) => `<li class="tstep" data-status="${s.status}" data-step="${s.id}">
      <div class="tstep-head"><span class="tdot" aria-hidden="true">${STEP_ICON[s.status] || '·'}</span><b>${esc(s.title)}</b>
        <span class="muted small">${esc(s.status)}</span>
        ${s.status === 'failed' ? '<button class="act" data-tact="retry">Retry this step</button>' : ''}
        ${s.status === 'done' && s.ai ? '<button class="act" data-tact="copy">Copy</button><button class="act" data-tact="save">Save to Knowledge</button>' : ''}</div>
      ${s.error ? `<p class="small err">${esc(s.error)}</p>` : ''}
      ${s.quiz ? renderTaskQuiz(s.quiz) : s.result ? `<div class="tresult">${s.ai ? md(s.result) : esc(s.result)}</div>` : ''}
    </li>`).join('')}</ol>
    ${refs ? `<details class="task-refs" open><summary>Sources</summary><ul>${refs}</ul></details>` : ''}
    <p class="muted small">AI results can be wrong; they are based only on the source shown. Nothing here is added to your memory.</p>
  </div>`;
  if (running) tasks.timer = setTimeout(() => { if (!$('#tasksSheet').classList.contains('hidden')) showTask(id); }, 1000);
  else renderTaskHistory();
}
function renderTaskQuiz(quiz) {
  return `<div class="tquiz"><p class="small muted tq-score" aria-live="polite">Answer each question: ${quiz.length} question${quiz.length === 1 ? '' : 's'}, only from the document.</p>
    ${quiz.map((q, i) => `<div class="tq" data-q="${i}"><p><b>${i + 1}. ${esc(q.q)}</b></p>
    ${q.options.map((o, j) => `<button class="option" data-opt="${j}"><span class="letter">${'ABCDEF'[j]}</span>${esc(o)}</button>`).join('')}
    <div class="tq-fb hidden" data-answer="${q.answer}"><p>${esc(q.explanation)}</p>
      ${q.quote ? `<blockquote>“${esc(q.quote)}”</blockquote><small class="${q.quote_found ? 'muted' : 'err'}">${q.quote_found ? 'Quote found in the source.' : 'This quote was not found in the source: check the answer yourself.'}</small>` : ''}</div></div>`).join('')}</div>`;
}
async function renderTaskHistory() {
  try {
    const { tasks: list } = await api('/tasks');
    $('#taskHistory').innerHTML = list.length ? list.slice(0, 12).map((t) => `<div class="list-item" data-tid="${t.id}"><div class="text"><b>${esc(t.title)}</b>
      <div class="meta">${esc(t.status)} · ${new Date(t.created_at).toLocaleString()}</div></div>
      <span class="actions"><button class="mini-btn" data-hact="open" aria-label="Open">↗</button><button class="mini-btn" data-hact="del" aria-label="Delete">✕</button></span></div>`).join('')
      : '<p class="muted small">No tasks yet.</p>';
  } catch { /* shown elsewhere */ }
}
function bindTasks() {
  $('#openTasks').addEventListener('click', openTasks);
  $('#tasksClose').addEventListener('click', () => closeSheet('tasksSheet'));
  $('#taskTemplates').addEventListener('click', (e) => { const b = e.target.closest('[data-tpl]'); if (b) pickTemplate(b.dataset.tpl); });
  $('#taskSrcKinds').addEventListener('click', (e) => { const b = e.target.closest('[data-kind]'); if (b) pickSourceKind(b.dataset.kind); });
  $('#taskNews').addEventListener('click', (e) => e.target.closest('.chip')?.classList.toggle('active'));
  $('#taskStart').addEventListener('click', startTask);
  $('#taskHistory').addEventListener('click', async (e) => {
    const row = e.target.closest('[data-tid]'); if (!row) return;
    if (e.target.closest('[data-hact=del]')) { if (confirm('Delete this task from the history? (Anything you saved to Knowledge stays.)')) { await api(`/tasks/${row.dataset.tid}`, { method: 'DELETE' }); renderTaskHistory(); } }
    else showTask(row.dataset.tid);
  });
  $('#taskView').addEventListener('click', async (e) => {
    const t = tasks.current; if (!t) return;
    const opt = e.target.closest('[data-opt]');
    if (opt) {                                                          // quiz: answer once, then show explanation and quote
      const q = opt.closest('.tq'), fb = q.querySelector('.tq-fb'), right = +fb.dataset.answer;
      if (!fb.classList.contains('hidden')) return;
      q.querySelectorAll('[data-opt]').forEach((b) => { b.disabled = true; if (+b.dataset.opt === right) b.classList.add('correct'); });
      if (+opt.dataset.opt !== right) opt.classList.add('wrong');
      fb.classList.remove('hidden');
      const box = q.closest('.tquiz'), all = box.querySelectorAll('.tq').length, done = box.querySelectorAll('.tq-fb:not(.hidden)').length;
      const ok = box.querySelectorAll('.option.correct').length - box.querySelectorAll('.tq:has(.option.wrong)').length;
      box.querySelector('.tq-score').textContent = done === all ? `Result: ${ok} of ${all} correct (${Math.round(100 * ok / all)}%).` : `${ok} of ${done} correct so far · ${all - done} to go`;
      recordProgress({ kind: 'mcq', category: 'Document quiz', correct: +opt.dataset.opt === right, item: q.querySelector('b').textContent.slice(0, 200) });
      return;
    }
    const a = e.target.closest('[data-tact]')?.dataset.tact; if (!a) return;
    const step = e.target.closest('[data-step]')?.dataset.step;
    try {
      if (a === 'cancel') await api(`/tasks/${t.id}/cancel`, { method: 'POST' });
      if (a === 'resume') await api(`/tasks/${t.id}/run`, { method: 'POST' });
      if (a === 'retry') await api(`/tasks/${t.id}/retry/${step}`, { method: 'POST' });
      if (a === 'copy') { await navigator.clipboard?.writeText(t.steps.find((s) => s.id === step).result); toast('Copied'); return; }
      if (a === 'export') {
        const url = URL.createObjectURL(new Blob([taskMarkdown(t)], { type: 'text/markdown' }));
        Object.assign(document.createElement('a'), { href: url, download: `${t.title.replace(/[^\w -]+/g, '').trim().slice(0, 50) || 'task'}.md` }).click();
        setTimeout(() => URL.revokeObjectURL(url), 2000); return;
      }
      if (a === 'save') {
        if (!confirm('Save this result to Knowledge? It becomes a note in your knowledge folder index (not your personal memory).')) return;
        const r = await api(`/tasks/${t.id}/save`, { method: 'POST', body: { step, confirm: true } });
        toast(r.duplicate ? 'Already in Knowledge' : `Saved to Knowledge as ${r.filename}`); return;
      }
      showTask(t.id);
    } catch (err) { toast(err.message); }
  });
}

/* ---------- 7i. COMMAND PALETTE (dev.8) ----------
   Ctrl+K / Cmd+K anywhere: pages, commands, models (the real /models list), news categories, news search, a question
   for the AI, and past conversations whose title matches (from /conversations). Nothing runs until you choose it. */
let palItems = [], palSel = 0, palConvs = null, palProjects = [], palTasks = [];
function palCommands(q) {
  const go = (t) => () => switchTab(t), click = (id) => () => $('#' + id)?.click();
  const base = [
    ['Home', 'Go to', go('home')], ['Assistant (chat)', 'Go to', go('chat')], ['Study · Discover', 'Go to', go('study')], ['News', 'Go to', go('news')], ['More', 'Go to', go('more')],
    ['Interview practice (Question Bank)', 'Go to', go('interview')], ['Quiz (MCQ)', 'Go to', go('mcq')], ['New chat', 'Command', () => { newChat(); switchTab('chat'); }],
    ['Knowledge folder', 'Open', click('openKnowledge')], ['Memory & history', 'Open', click('openMemory')], ['System & data', 'Open', click('openSystem')],
    ['Performance', 'Open', () => openPerf()], ['AI Task Runner', 'Open', () => openTasks()], ['Projects (workspaces)', 'Open', () => openProjects()], ['AI Models (models & setup)', 'Open', () => LAUNCH.models()],
    ['Documents & Knowledge folder', 'Open', () => LAUNCH.documents()], ['Open the Abhify navigation', 'Command', () => openLauncher($('#brandLauncher').offsetParent ? $('#brandLauncher') : $('#brandLauncherMobile'))],
    ...tasks.templates.map((t) => [`New task: ${t.title}`, 'Task', () => openTasks().then(() => pickTemplate(t.id))]), ['Settings', 'Open', click('settingsBtn')], ['News sources', 'Open', () => { switchTab('news'); newsSources(); }],
    ['Customize Home', 'Command', () => openHomeLayout()], ['Switch theme (system → light → dark)', 'Command', click('themeBtn')],
    ['Collapse or expand the sidebar', 'Command', click('sideCollapse')],
    ...state.models.list.map((m) => [`Use ${m.name} in this chat`, 'Model', () => { switchTab('chat'); pickChatModel(m.name); }]),
    ...news.cats.map((c) => [`News: ${c.label}`, 'News', () => { switchTab('news'); newsOpen(c.id); }]),
  ];
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  const hit = (t) => words.every((w) => t.toLowerCase().includes(w));
  const out = base.filter(([t, k]) => !words.length || hit(`${t} ${k}`));
  // local matches: skipped when the grouped search already lists them, or when a type filter excludes their type
  const found = new Set(palSearch && palSearch.q === q.trim() ? palSearch.groups.flatMap((g) => g.results.map((r) => `${r.type_key}:${r.ref}`)) : []);
  const keep = (key, id) => !found.has(`${key}:${id}`) && (!palFilter.type || palFilter.type === key);
  if (palConvs && words.length) out.push(...palConvs.filter((c) => keep('conversation', c.id) && hit(c.title)).slice(0, 5).map((c) => [c.title, 'Conversation', () => openConversation(c.id)]));
  if (words.length) {
    out.push(...palProjects.filter((p) => keep('project', p.id) && hit(`${p.name} ${p.documents.join(' ')}`)).slice(0, 5).map((p) => [p.name, p.archived ? 'Project (archived)' : 'Project', async () => { await openProjects(); showProject(p.id); }]));
    out.push(...palTasks.filter((t) => keep('task', t.id) && hit(`${t.title} ${t.source?.name || ''}`)).slice(0, 5).map((t) => [`${t.title}${t.source?.name ? ` · ${t.source.name}` : ''}`, `AI task (${t.status})`, async () => { await openTasks(); showTask(t.id); }]));
  }
  // next build / 5.6: universal search results, grouped by type with counts (memories only when memory is on)
  if (words.length) out.push(...palSearchItems(q));
  else out.push(...palRecentItems());
  if (q.trim()) {
    out.push([`Search news for "${q.trim()}"`, 'News search', () => { switchTab('news'); $('#newsQ').value = q.trim(); newsSearch(q.trim()); }]);
    out.push([`Ask the AI: "${q.trim()}"`, 'Chat', () => sendChat(q.trim())]);
  }
  return out.slice(0, 40);
}
function renderPalette() {
  palItems = palCommands($('#palQ').value); palSel = Math.min(palSel, Math.max(0, palItems.length - 1));
  $('#palList').innerHTML = (palListHtml(palItems, palSel) + palSearchEmptyHtml($('#palQ').value)) || '<li class="muted small">Nothing matches.</li>';
  $('#palQ').setAttribute('aria-activedescendant', palItems.length ? `pal${palSel}` : '');
  $(`#pal${palSel}`)?.scrollIntoView({ block: 'nearest' });
}
async function openPalette(q = '') {
  palSel = 0; $('#palQ').value = typeof q === 'string' ? q : ''; openSheet('palette'); renderPalette(); $('#palQ').focus();
  if ($('#palQ').value) schedulePalSearch();
  if (palConvs === null && state.apiBase !== null && !state.remote && state.engine !== 'device') {
    try { palConvs = (await api('/conversations', { timeout: 6000 })).conversations || []; } catch { palConvs = []; }
  }
  // projects and AI tasks are searchable too (read each time the palette opens; local data only)
  if (state.apiBase !== null && !state.remote && state.engine !== 'device') {
    try { palProjects = (await api('/projects', { timeout: 6000 })).projects || []; } catch { palProjects = []; }
    try { palTasks = (await api('/tasks', { timeout: 6000 })).tasks || []; } catch { palTasks = []; }
    renderPalette();
  }
}
function runPalette(i) { const it = palItems[i]; if (!it) return; closeSheet('palette'); it[2](); }
function bindPalette() {
  document.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); $('#palette').classList.contains('hidden') ? openPalette() : closeSheet('palette'); }
  });
  $('#paletteBtn').addEventListener('click', () => openPalette());
  $('#palQ').addEventListener('input', () => { palSel = 0; renderPalette(); schedulePalSearch(); });
  $('#palQ').addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); palSel = Math.min(palSel + 1, palItems.length - 1); renderPalette(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); palSel = Math.max(palSel - 1, 0); renderPalette(); }
    else if (e.key === 'Enter') { e.preventDefault(); runPalette(palSel); }
    else if (e.key === 'Escape') { e.preventDefault(); closeSheet('palette'); }
  });
  $('#palList').addEventListener('click', (e) => { const li = e.target.closest('[data-i]'); if (li) runPalette(+li.dataset.i); });
  $('#palette').addEventListener('click', (e) => { if (e.target.id === 'palette') closeSheet('palette'); });
  bindPalFilters();
}

/* ---------- 7j. HOME LAYOUT (dev.8): show/hide/reorder cards, kept in this browser ---------- */
const HOME_CARDS = [['homeContinue', 'Continue working'], ['homeToday', 'Today'], ['homeProjects', 'Active projects'], ['homeStatus', 'AI on this computer'],
  ['homeNewsCard', 'AI news'], ['homeQuiz', 'Quiz'], ['homeKnowledge', 'Your documents'], ['homeInterview', 'Interview practice']];
function homeLayout() {
  const saved = store.json('abhify_home_layout', null);
  const order = saved?.order?.filter((id) => HOME_CARDS.some(([c]) => c === id)) || [];
  HOME_CARDS.forEach(([id]) => { if (!order.includes(id)) order.push(id); });
  return { order, hidden: saved?.hidden || [] };
}
function applyHomeLayout() {
  const l = homeLayout();
  l.order.forEach((id, i) => { const el = $('#' + id); if (el) { el.style.order = String(i + 1); el.classList.toggle('hidden', l.hidden.includes(id)); } });
}
function openHomeLayout() {
  const l = homeLayout();
  $('#homeLayoutList').innerHTML = l.order.map((id, i) => `<div class="list-item" data-card="${id}">
      <label class="text"><input type="checkbox" ${l.hidden.includes(id) ? '' : 'checked'} data-show> ${esc(HOME_CARDS.find(([c]) => c === id)[1])}</label>
      <span class="actions"><button class="mini-btn" data-move="-1" aria-label="Move up" ${i === 0 ? 'disabled' : ''}>↑</button>
      <button class="mini-btn" data-move="1" aria-label="Move down" ${i === l.order.length - 1 ? 'disabled' : ''}>↓</button></span></div>`).join('');
  openSheet('homeLayoutSheet');
}
function bindHomeLayout() {
  $('#homeCustomize').addEventListener('click', openHomeLayout);
  $('#homeLayoutDone').addEventListener('click', () => closeSheet('homeLayoutSheet'));
  $('#homeLayoutReset').addEventListener('click', () => { store.set('abhify_home_layout', ''); applyHomeLayout(); openHomeLayout(); });
  $('#homeLayoutList').addEventListener('click', (e) => {
    const row = e.target.closest('[data-card]'); if (!row) return; const l = homeLayout(), id = row.dataset.card;
    if (e.target.matches('[data-show]')) l.hidden = e.target.checked ? l.hidden.filter((x) => x !== id) : [...l.hidden, id];
    const mv = e.target.closest('[data-move]');
    if (mv) { const i = l.order.indexOf(id), j = i + +mv.dataset.move; [l.order[i], l.order[j]] = [l.order[j], l.order[i]]; }
    store.setJson('abhify_home_layout', l); applyHomeLayout(); if (mv) openHomeLayout();
  });
  applyHomeLayout();
}

/* ---------- 7e. HOME (dev.8) ----------
   A bento of real things to continue. Every card reads data the app already has: the last conversation (/conversations),
   the local quiz score, the cached news, the knowledge-folder count (/health), the AI status and default model.
   Nothing is a sample; empty cards say what to do next. */
function greeting(d = new Date()) { const h = d.getHours(); return h < 5 ? 'Good evening' : h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening'; }
function agoText(ts) {
  const t = Date.parse(String(ts).replace(' ', 'T') + (/[zZ+]/.test(ts) ? '' : 'Z'));
  if (!t) return '';
  const m = Math.round((Date.now() - t) / 60000);
  return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} days ago`;
}
/** 5.6 Home: an AI dashboard of real records only (conversations, plan, projects, health, news cache, quiz score). */
function homeRecentHtml(conversations) {
  if (!conversations || !conversations.length) return stateHtml('empty', 'No chats yet', 'Ask something above to start.', true);
  return conversations.slice(0, 3).map((c) => `<button type="button" class="row-item" data-thread="${esc(c.id)}"><span class="ri-dot" aria-hidden="true"></span>
    <span class="ri-main"><b>${esc(c.title)}</b><small>${esc(`${c.messages} messages · ${agoText(c.updated_at)}`)}</small></span></button>`).join('');
}
function homeProjectsHtml(list) {
  const active = (list || []).filter((p) => !p.archived).slice(0, 3);
  if (!active.length) return stateHtml('empty', 'No projects yet', 'A project keeps its own instructions, documents and chats.', true);
  return active.map((p) => {
    const st = p.status || {}, next = (st.next || [])[0];
    const line = st.current || (next ? `Next: ${next}` : `${(p.documents || []).length} documents`);
    return `<button type="button" class="row-item" data-project="${esc(p.id)}"><span class="ri-dot" aria-hidden="true"></span>
      <span class="ri-main"><b>${esc(p.name)}</b><small>${esc(line)}</small></span></button>`;
  }).join('');
}
function homeStatusHtml(h, models) {
  if (!h) return '';
  const ok = (b) => `<span class="dot ${b ? 'on' : 'off'}" aria-hidden="true"></span>`;
  const cell = (k, v, good) => `<div><span class="k">${esc(k)}</span><span class="v">${good == null ? '' : ok(good)}${esc(v)}</span></div>`;
  return cell('Chat model', models.def || h.llm_model || '—', h.llm_ready) + cell('Embeddings', h.embed_model || '—', h.embed_ready)
    + cell('Knowledge', h.knowledge_chunks != null ? `${h.knowledge_chunks} sections` : 'not connected', h.knowledge_chunks > 0 ? true : null)
    + cell('Internet', h.internet ? 'available' : 'offline', !!h.internet);
}
async function renderHome() {
  if (!$('#tab-home')) return;
  const n = displayName();
  $('#homeGreet').textContent = n ? `${greeting()}, ${n}` : greeting();
  $('#homeDate').textContent = new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });
  const p = state.progress;
  $('#homeQuizScore').textContent = `${p.mcq_correct || 0} / ${p.mcq_total || 0} correct`;
  $('#homeQuizMeta').textContent = p.streak_days > 1 ? `${p.streak_days}-day streak · start a new round` : 'Start a round of multiple-choice questions';
  $('#homeInterviewCount').textContent = state.questions.length ? `${state.questions.length} saved question${state.questions.length === 1 ? '' : 's'}` : 'Questions with model answers';
  const cached = store.json(LS.news, null);
  const items = (news.result && news.result.articles && news.result.articles.length) ? news.result.articles : (cached && cached.items) || [];
  $('#homeNews').innerHTML = items.length ? items.slice(0, 3).map((x) => `<li>${esc(x.title)}</li>`).join('')
    : '<li class="muted">No saved headlines yet. Open News to load them.</li>';
  const h = state.health;
  $('#homeKnowledgeCount').textContent = h && h.knowledge_chunks != null ? `${h.knowledge_chunks} indexed sections` : 'Not connected';
  $('#homeStatusText').textContent = state.engine === 'device' ? 'Runs in this browser' : $('#statusText').textContent;
  $('#homeStatusGrid').innerHTML = state.engine === 'device' ? '' : homeStatusHtml(h, state.models);
  $('#homeStatusModel').textContent = state.engine === 'device' ? 'Abhify Lite' : (h ? '' : 'Start the Abhify.AI app on your computer to see its models.');
  $('#homeModel').textContent = state.engine === 'device' ? 'Starts a new chat in this browser.' : `Starts a new chat${state.models.def ? ` with ${state.models.def}` : ''} · runs on this computer.`;
  if (state.engine !== 'device' && state.apiBase !== null && !state.remote) {
    try {
      const conversations = ((await api('/conversations')).conversations || []).filter((c) => c.messages !== 0);   // 6.1: a chat stopped before its first answer is empty
      $('#homeRecent').innerHTML = homeRecentHtml(conversations);
      $('#homeContinue').dataset.thread = conversations[0] ? conversations[0].id : '';
    } catch { $('#homeRecent').innerHTML = stateHtml('error', "Couldn't read your chats", 'The app on your computer is not reachable.', true); }
    try { $('#homeProjectsList').innerHTML = homeProjectsHtml((await api('/projects?archived=false', { timeout: 8000 })).projects); }
    catch { $('#homeProjectsList').innerHTML = stateHtml('empty', 'Projects are not available here', '', true); }
    renderPlan();
    renderHomeSuggestions();
    renderBriefing();
  } else {
    const note = state.engine === 'device' ? 'Available with the Abhify.AI app on your computer.' : 'Connect to the Abhify.AI app on your computer.';
    ['#homeRecent', '#homeProjectsList', '#planOut'].forEach((id) => { $(id).innerHTML = stateHtml('empty', 'Not available offline', note, true); });
    $('#briefingOut').innerHTML = stateHtml('empty', 'Not available offline', note, true);
  }
}
/* ---------- next build: Study → Discover, More, News language, briefing, universal search ---------- */
async function discoverSearch(topic) {
  const out = $('#discoverOut'); if (!topic || topic.length < 2) return;
  out.innerHTML = skeleton(2);
  let d; try { d = await api(`/discover?q=${encodeURIComponent(topic)}&free_only=${$('#discoverFree').checked}`, { timeout: 30000 }); }
  catch (e) { out.innerHTML = stateHtml('error', "Couldn't search the course sources", e.message === 'offline' ? 'Discover needs the Abhify.AI app on your computer and internet.' : e.message); return; }
  const card = (r) => `<div class="task-card" data-acc="study"><b>${esc(r.title)}</b><br><small class="muted">${esc([r.provider, r.kind, r.level].filter(Boolean).join(' · '))}</small>
    <div class="meta-tags"><span class="tag ${r.cost === 'Free' ? 'kb' : ''}">${esc(r.cost)}</span>${r.license ? `<span class="tag">${esc(r.license)}</span>` : ''}${(r.topics || []).slice(0, 2).map((t) => `<span class="tag">${esc(t)}</span>`).join('')}</div>
    <small class="muted">Source: ${esc(r.source)} (live)</small> <a class="btn btn-ghost btn-sm" href="${esc(r.url)}" target="_blank" rel="noopener">Open ↗</a></div>`;
  out.innerHTML = (d.notes || []).map((n) => `<p class="small err">⚠ ${esc(n)}</p>`).join('')
    + `<h3 class="set-h">${d.results.length ? `${d.results.length} from live sources · checked just now` : 'No matching courses or books from the live sources'}</h3>` + d.results.map(card).join('')
    + (d.providers.length ? '<h3 class="set-h">Search on the providers\' own sites</h3><ul class="list small">' + d.providers.map((p) =>
      `<li><a href="${esc(p.url)}" target="_blank" rel="noopener"><b>${esc(p.provider)}</b></a> <span class="muted">${esc(p.note)}</span></li>`).join('') + '</ul>' : '')
    + '<p class="muted small">Prices and availability can change: the provider\'s page is the final word.</p>';
}
function renderMore() {
  const groups = [
    ['Knowledge', [['Knowledge folder', 'Documents this computer indexes for answers', () => $('#openKnowledge').click(), 'docs'],
      ['Memory & history', 'What Abhify remembers and your chat history', () => $('#openMemory').click(), 'chat']]],
    ['Workspace', [['Projects', 'Instructions, documents, chats and status per project', () => openProjects(), 'chat'],
      ['AI Task Runner', 'Multi-step AI tasks on documents, news or text', () => openTasks(), 'chat']]],
    ['Career', [['Question Bank', 'Interview questions with model answers', () => switchTab('interview'), 'study'],
      ['MCQs', 'Practise from topics, your resume or any subject', () => switchTab('mcq'), 'study']]],
    ['AI & System', [['AI models & setup', 'Installed models and what fits this computer', () => LAUNCH.models(), 'docs'],
      ['Performance', 'CPU, memory, GPU and answer speed, measured live', () => openPerf(), 'docs'],
      ['System & data', 'Backups, updates, companion access and data folder', () => $('#openSystem').click(), 'docs']]],
    ['Settings & privacy', [['Settings', 'Your name, answer style, theme and connection', () => $('#settingsBtn').click(), 'chat']]],
  ];
  $('#moreGroups').innerHTML = groups.map(([g, items], gi) => `<section class="more-group" aria-label="${esc(g)}"><h3 class="set-h">${esc(g)}</h3><div class="more-grid">${items.map(([t, d, , acc], i) =>
    `<button type="button" class="card card--interactive more-tile${t.startsWith('Memory') || t.startsWith('Knowledge') ? ' personal' : ''}" data-acc="${acc}" data-more="${gi}:${i}"><b>${esc(t)}</b><small>${esc(d)}</small></button>`).join('')}</div></section>`).join('');
  $('#moreGroups').onclick = (e) => { const b = e.target.closest('[data-more]'); if (!b) return; const [gi, i] = b.dataset.more.split(':').map(Number); groups[gi][1][i][2](); };
}
async function renderPlan() {
  const out = $('#planOut'); if (!out) return;
  let p; try { p = await api('/plan', { timeout: 8000 }); } catch { out.innerHTML = stateHtml('error', "Couldn't read today's plan", '', true); return; }
  out.innerHTML = p.entries.length ? `<ul class="b-list">${p.entries.slice(0, 5).map((e) => `<li>${esc(e.title)} <span class="muted">· ${esc(e.project)}</span></li>`).join('')}</ul><p class="muted small">${esc(p.note)}</p>`
    : stateHtml('empty', 'Nothing planned', 'Next actions and open issues from your projects appear here.', true);
}
async function renderSkills() {
  const out = $('#skillsOut'); if (!out) return;
  let s; try { s = await api('/skills', { timeout: 8000 }); } catch { out.classList.add('hidden'); return; }
  if (!s.skills.length) { out.classList.add('hidden'); return; }
  out.classList.remove('hidden');
  out.innerHTML = skillsHtml(s);
}
/* 5.6: skill levels with their answer counts, a general learning order (prerequisites first) and a revision plan. */
function skillsHtml(s) {
  const n = (k) => k.answers_label || `${k.answered} answers`;
  return '<h3 class="set-h">Skills (from your MCQ answers)</h3>' + (s.next ? `<p><b>Next:</b> practise ${esc(s.next.skill)} <span class="muted small">— ${esc(s.next.reason)}</span></p>` : '')
    + `<ul class="list small">${s.skills.slice(0, 8).map((k) => `<li>${esc(k.skill)} <span class="muted">· ${esc(k.level)} · ${esc(n(k))}${k.accuracy == null ? '' : ` · ${k.accuracy}% correct`}${k.trend ? ` · ${esc(k.trend)}` : ''}</span></li>`).join('')}</ul>`
    + ((s.revision || []).length ? `<b>Revision plan</b><ol class="list small">${s.revision.slice(0, 5).map((r) => `<li>${esc(r.skill)} <span class="muted">· ${esc(r.reason)} (${esc(r.answers_label)})</span></li>`).join('')}</ol>` : '')
    + ((s.learning_path || []).length ? `<b>Learning path</b><ol class="list small">${s.learning_path.map((x) => `<li>${esc(x.skill)} <span class="muted">· ${esc(x.level)}${x.yours ? ` · ${esc(x.answers_label)}` : ''}${x.comes_before.length ? ` · before ${esc(x.comes_before.join(', '))}` : ''}</span></li>`).join('')}</ol>
      <p class="muted small">${esc(s.path_note || '')}</p>` : '')
    + `<p class="muted small">Levels need at least ${s.min_answers} answers; trends need 10.</p>`;
}
async function renderBriefing() {
  const kind = $('#briefingKind')?.value || 'now';                // Today's plan has its own card (renderPlan)
  const out = $('#briefingOut'); if (!out) return;
  let b; try { b = await api(kind === 'now' ? '/briefing' : `/briefing?kind=${encodeURIComponent(kind)}`, { timeout: 8000 }); } catch { out.innerHTML = stateHtml('error', "Couldn't make the briefing", 'Your records on this computer could not be read.', true); return; }
  $('#homeBriefing').classList.remove('hidden');
  const emptyText = { evening: 'Nothing changed in your records today.', weekly: 'Nothing in your records for the last 7 days.' }[kind] || 'Nothing in your records to brief you on yet.';
  out.innerHTML = b.empty ? stateHtml('empty', emptyText, '', true) : b.sections.map((s) => `<b>${esc(s.title)}</b><ul class="list small">${s.items.map((i) =>
    `<li>${esc(i.text)} <span class="muted">· ${esc(i.source)}</span></li>`).join('')}</ul>`).join('') + '<p class="muted small">From your records on this computer just now.</p>';
}
function bindStudyMore() {
  $('#discoverForm')?.addEventListener('submit', (e) => { e.preventDefault(); discoverSearch($('#discoverQ').value.trim()); });
  $$('[data-study-go]').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.studyGo)));
  $('#briefingRefresh')?.addEventListener('click', renderBriefing);
  const bk = $('#briefingKind');
  if (bk) {
    bk.value = store.get('abhify_briefing_kind') || 'now';
    bk.addEventListener('change', () => { store.set('abhify_briefing_kind', bk.value); renderBriefing(); });
  }
  $$('[data-news-lang]').forEach((b) => b.addEventListener('click', async () => {
    try { news.prefs = await api('/news/prefs', { method: 'PUT', body: { ...(news.prefs || {}), language: b.dataset.newsLang } }); markNewsLang(); newsOpen(news.cat, true); }
    catch (e) { toast(e.message === 'offline' ? 'News language needs the Abhify.AI app on your computer' : e.message); }
  }));
}
function markNewsLang() { const l = (news.prefs || {}).language || 'en'; $$('[data-news-lang]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.newsLang === l))); }
/* 5.6 Universal Search 2.0: filters (type, date, sort) as chips, results grouped with counts, a useful empty state and
   recent searches (kept in this browser only). GET /search does the work locally; no embeddings, no network. */
let palSearch = null, palTimer = 0;
const palFilter = { type: '', days: '', sort: 'relevance' };
const PAL_OPEN = {
  conversation: (r) => openConversation(r.ref), project: async (r) => { await openProjects(); showProject(r.ref); },
  task: async (r) => { await openTasks(); showTask(r.ref); }, memory: () => $('#openMemory').click(), document: () => $('#openKnowledge').click(),
  news: (r) => window.open(r.ref, '_blank', 'noopener'), study: () => switchTab('mcq'),
};
/** Palette rows; an item's 4th field is its group header ("Project · 3"), shown once before the group. */
function palListHtml(items, sel) {
  let last = null;
  return items.map(([t, k, , group], i) => {
    const head = group && group !== last ? `<li class="pal-group" role="presentation">${esc(group)}</li>` : '';
    last = group || null;
    return `${head}<li role="option" id="pal${i}" aria-selected="${i === sel}" data-i="${i}"><span>${esc(t)}</span><small>${esc(k)}</small></li>`;
  }).join('');
}
function palSearchQuery(q, f) {
  const p = new URLSearchParams({ q });
  if (f.type) p.set('types', f.type);
  if (f.days) p.set('days', f.days);
  if (f.sort && f.sort !== 'relevance') p.set('sort', f.sort);
  return `/search?${p}`;
}
function palRemember(q) {
  const list = [q, ...store.json('abhify_recent_searches', []).filter((x) => x !== q)].slice(0, 6);
  store.setJson('abhify_recent_searches', list);
}
function palSearchItems(q) {
  if (!palSearch || palSearch.q !== q.trim()) return [];
  const out = [];
  for (const g of palSearch.groups || []) {
    for (const r of g.results.slice(0, 6)) {
      out.push([r.snippet ? `${r.title} — ${r.snippet.slice(0, 60)}` : r.title, r.source ? `${r.type} · ${r.source}` : r.type,
        () => { palRemember(q.trim()); (PAL_OPEN[r.type_key] || (() => {}))(r); }, `${g.type} · ${g.count}`]);
    }
  }
  return out;
}
function palRecentItems() {
  return store.json('abhify_recent_searches', []).map((q) => [q, 'Recent search', () => setTimeout(() => openPalette(q), 0), 'Recent searches']);
}
function palSearchEmptyHtml(q) {
  q = (q || '').trim();
  if (!palSearch || palSearch.q !== q || q.length < 2 || (palSearch.total || 0) > 0) return '';
  const f = [palFilter.type && 'type', palFilter.days && 'date'].filter(Boolean);
  return `<li class="pal-empty small" role="presentation">No matches for “${esc(q)}” in your chats, projects, AI tasks, document names, saved news or study.
    ${f.length ? `Try clearing the ${f.join(' and ')} filter.` : 'Try fewer or shorter words.'}${palFilter.days ? ' Items without a date (document names) are left out when a date filter is on.' : ''}</li>`;
}
function schedulePalSearch() {
  clearTimeout(palTimer); const q = $('#palQ').value.trim();
  if (q.length < 2 || state.apiBase === null || state.remote || state.engine === 'device') { palSearch = null; return; }
  palTimer = setTimeout(async () => {
    let d; try { d = await api(palSearchQuery(q, palFilter), { timeout: 6000 }); } catch { d = { groups: [], total: 0 }; }
    palSearch = { q, groups: d.groups || [], total: d.total || 0 };
    if ($('#palQ').value.trim() === q) renderPalette();
  }, 250);
}
function bindPalFilters() {
  $('#palFilters')?.addEventListener('click', (e) => {
    const b = e.target.closest('[data-pf]'); if (!b) return;
    palFilter[b.dataset.pf] = b.dataset.v;
    $$(`[data-pf="${b.dataset.pf}"]`).forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
    palSel = 0; schedulePalSearch(); $('#palQ').focus();
  });
}
/* 5.2: "Continue working" + suggestions (GET /suggestions: records only; dismissed keys kept in this browser). */
async function renderHomeSuggestions() {
  const box = $('#homeCompanion'); if (!box) return;
  let d; try { d = await api('/suggestions'); } catch { box.classList.add('hidden'); return; }
  const gone = new Set(store.json('abhify_dismissed_suggestions', []));
  const sugg = (d.suggestions || []).filter((s) => !gone.has(s.key));
  const row = (glyph, title, detail, attrs, extra = '') => `<div class="row-tight comp-row"><button type="button" class="act comp-open" ${attrs}>${glyph} <b>${esc(title)}</b><br><small class="muted">${esc(detail)}</small></button>${extra}</div>`;
  $('#homeCompanionCont').innerHTML = (d.continue || []).length ? '<h3 class="set-h">Recent activity</h3>' + d.continue.map((c) =>
    row(c.kind === 'project' ? '🗂' : '✅', c.title, c.detail, `data-comp="${c.kind}" data-ref="${esc(c.ref)}"`)).join('') : '';
  $('#homeCompanionSugg').innerHTML = sugg.length ? '<h3 class="set-h">Suggestions</h3>' + sugg.map((s) =>
    row('💡', s.title, s.reason, `data-comp="${s.kind || 'project'}" data-ref="${esc(s.ref)}"`,
      `<button type="button" class="mini-btn" data-dismiss="${esc(s.key)}" aria-label="Dismiss this suggestion">✕</button>`)).join('') : '';
  box.classList.toggle('hidden', !(d.continue || []).length && !sugg.length);
}
function bindHomeSuggestions() {
  $('#homeCompanion')?.addEventListener('click', (e) => {
    const x = e.target.closest('[data-dismiss]');
    if (x) { const l = store.json('abhify_dismissed_suggestions', []); l.push(x.dataset.dismiss); store.setJson('abhify_dismissed_suggestions', l.slice(-200)); renderHomeSuggestions(); return; }
    const b = e.target.closest('[data-comp]'); if (!b) return;
    if (b.dataset.comp === 'task') openTasks(); else { openProjects(); showProject(b.dataset.ref); }
  });
}
function openConversation(id) {
  if (!id) { switchTab('chat'); return; }
  state.threadId = id; store.set(LS.thread, id);
  $('#chatLog').querySelectorAll('.msg').forEach((m, i) => { if (i > 0) m.remove(); });
  loaded.chat = false; switchTab('chat');
}
/** 5.6 Assistant history: the recent conversations (GET /conversations, local) in a popover next to New. */
async function openChatHistory() {
  const pop = $('#historyPop'), btn = $('#chatHistoryBtn');
  if (!pop.classList.contains('hidden')) { pop.classList.add('hidden'); btn.setAttribute('aria-expanded', 'false'); return; }
  pop.innerHTML = '<div class="pop-head">Recent conversations</div><div class="pad"><span class="skel-line"></span><span class="skel-line short"></span></div>';
  pop.classList.remove('hidden'); btn.setAttribute('aria-expanded', 'true');
  if (state.engine === 'device' || state.apiBase === null || state.remote) { pop.innerHTML = '<div class="pop-head">Recent conversations</div>' + stateHtml('empty', 'History lives on your computer', 'Open Abhify.AI on the computer to see past chats.', true); return; }
  let list = []; try { list = ((await api('/conversations', { timeout: 8000 })).conversations || []).filter((c) => c.messages !== 0); }
  catch (e) { pop.innerHTML = '<div class="pop-head">Recent conversations</div>' + stateHtml('error', "Couldn't read your chats", e.message, true); return; }
  pop.innerHTML = '<div class="pop-head">Recent conversations</div>' + (list.length ? list.slice(0, 15).map((c) => `<button type="button" class="model-opt hist-item" role="option" aria-selected="${c.id === state.threadId}" data-thread="${esc(c.id)}">
      <span class="opt-main"><b>${esc(c.title)}</b><small>${esc(`${c.messages} messages · ${agoText(c.updated_at)}`)}</small></span></button>`).join('') : stateHtml('empty', 'No chats yet', '', true));
}
function bindActions() {
  $('#chatLog').addEventListener('click', async (e) => {
    const copy = e.target.closest('[data-copy]');
    if (copy) {
      const t = copy.dataset.copy === 'code' ? copy.closest('.code-block').querySelector('code').textContent : answerStore[+copy.closest('.msg-tools').dataset.ans];
      const ok = await copyText(String(t ?? ''));
      const label = copy.querySelector('span') || copy, was = label.textContent;
      label.textContent = ok ? 'Copied' : 'Copy failed'; copy.classList.toggle('done', ok);
      setTimeout(() => { label.textContent = was; copy.classList.remove('done'); }, 1600);
      return;
    }
    const lchip = e.target.closest('.lchip-main');
    if (lchip) { toggleLiveDetail(lchip); return; }
    if (state.busy.chat) return;
    const lr = e.target.closest('[data-live-q]');
    if (lr) {                                           // the newest answer is answered again in place; an older one as a new question
      const msg = lr.closest('.msg'), newest = msg && msg === [...$$('#chatLog .msg.bot')].pop();
      sendChat(lr.dataset.liveQ, newest && state.threadId ? { regenerate: true, reuse: msg, refreshLive: true } : { refreshLive: true });
      return;
    }
    const regen = e.target.closest('[data-regen-q]');
    if (regen) { sendChat(regen.dataset.regenQ, { regenerate: true, reuse: regen.closest('.msg') }); return; }
    const retry = e.target.closest('[data-retry]');
    if (retry) { if (!state.online) await checkConnection(); sendChat(retry.dataset.retry, { regenerate: Boolean(retry.dataset.regen), reuse: retry.closest('.msg') }); return; }
    const fu = e.target.closest('[data-fu]');
    if (fu) { sendChat(fu.dataset.fu); return; }
    const b = e.target.closest('.act'); if (!b || !b.closest('.ai-actions')) return;
    runAction(b.dataset.act, answerStore[+b.closest('.ai-actions').dataset.ans]);
  });
  $('#chatHistoryBtn').addEventListener('click', (e) => { e.stopPropagation(); openChatHistory(); });
  $('#historyPop').addEventListener('click', (e) => { const b = e.target.closest('[data-thread]'); if (!b) return; $('#historyPop').classList.add('hidden'); $('#chatHistoryBtn').setAttribute('aria-expanded', 'false'); openConversation(b.dataset.thread); });
  document.addEventListener('click', (e) => { if (!e.target.closest('#historyPop, #chatHistoryBtn')) { $('#historyPop').classList.add('hidden'); $('#chatHistoryBtn').setAttribute('aria-expanded', 'false'); } });
  $('#chatAgentBtn').addEventListener('click', () => openTasks());
  $('#openPerf').addEventListener('click', openPerf);
  $('#perfClose').addEventListener('click', () => closeSheet('perfSheet'));
}
function bindHome() {
  bindActions(); bindPalette(); bindHomeLayout(); bindTasks(); bindLauncher(); bindProjects(); bindHomeSuggestions();
  $$('.starter').forEach((b) => b.addEventListener('click', () => sendChat(b.dataset.prompt)));
  $('#homeAsk').addEventListener('submit', (e) => {
    e.preventDefault(); const t = $('#homeAskText').value.trim(); if (!t) return;
    $('#homeAskText').value = ''; newChat(); sendChat(t);
  });
  $('#homeRecent').addEventListener('click', (e) => { const b = e.target.closest('[data-thread]'); if (b) openConversation(b.dataset.thread); });
  $('#homeContinueAll').addEventListener('click', () => { switchTab('chat'); openChatHistory(); });
  $('#homeProjectsList').addEventListener('click', async (e) => { const b = e.target.closest('[data-project]'); if (b) { await openProjects(); showProject(b.dataset.project); } });
  $('#homeProjectsAll').addEventListener('click', () => openProjects());
  $('#searchTrigger')?.addEventListener('click', () => openPalette());
  $('#homeQuiz').addEventListener('click', () => switchTab('mcq'));
  $('#homeInterview').addEventListener('click', () => switchTab('interview'));
  $('#homeNewsMore').addEventListener('click', () => switchTab('news'));
  $('#homeKnowledge').addEventListener('click', () => $('#openKnowledge').click());
}

/* Full pages (dev.8): on wide screens Memory, System and Knowledge open in the main area next to the sidebar instead of
   as a bottom sheet (CSS .as-page). The same elements and IDs; phones keep the sheet. The sidebar shows which is open. */
function closePages() { $$('.sheet-backdrop.as-page').forEach((p) => p.classList.add('hidden')); }
function watchPages() {
  const map = { memorySheet: 'openMemory', systemSheet: 'openSystem', knowledgeSheet: 'openKnowledge', perfSheet: 'openPerf', tasksSheet: 'openTasks', projectsSheet: 'openProjects' };
  const sync = () => {
    let any = false;
    for (const [sheet, btn] of Object.entries(map)) {
      const open = !$('#' + sheet).classList.contains('hidden'); any ||= open;
      $(`.side-btn[data-open="${btn}"]`)?.classList.toggle('active', open);
    }
    document.body.classList.toggle('page-open', any);
    $$('.tab').forEach((t) => t.classList.toggle('parent', any && t.dataset.tab === 'more'));   // 5.6: these pages live under More
  };
  const mo = new MutationObserver(sync);
  Object.keys(map).forEach((id) => mo.observe($('#' + id), { attributes: true, attributeFilter: ['class'] }));
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && document.body.classList.contains('page-open')) closePages(); });
}

/* ---------- 7c. CHAT MODEL PICKER (dev.8) ----------
   The list is /models: the chat models really installed in Ollama on the computer (embedding models left out).
   The choice belongs to the conversation (kept in this browser) and is sent with each message; the backend checks it
   and answers 400 instead of quietly using another model. "Default chat model" in Settings changes the computer's
   default for new chats. Phones paired to the computer use its default (model choice is PC only). */
function rememberThreadModel() {
  if (!state.threadId) return;
  const m = store.json(LS.threadModels, {});
  if (state.chatModel) m[state.threadId] = state.chatModel; else delete m[state.threadId];
  store.setJson(LS.threadModels, m);
}
function restoreThreadModel() {
  state.chatModel = (state.threadId && store.json(LS.threadModels, {})[state.threadId]) || null;
  renderModelChip();
}

async function loadModels() {
  if (state.engine === 'device' || state.apiBase === null) { renderModelChip(); return; }
  state.models.loading = true; renderModelChip();
  try {
    const r = await api('/models', { timeout: 8000 });
    Object.assign(state.models, { list: r.models || [], def: r.default || null, ollama: r.ollama, error: r.error || null,
      locked: !!r.locked, loaded: true, forbidden: false });
  } catch (e) {
    const forbidden = /computer|local|403|PC/i.test(e.message);
    Object.assign(state.models, { list: [], loaded: true, forbidden, error: forbidden ? null : e.message });
  } finally { state.models.loading = false; renderModelChip(); }
}

/** Pure: the chip text and state for the current engine, models and choice. */
function modelChipView(st = state) {
  if (st.engine === 'device') {
    const d = st.device;
    return { label: `On this device · ${d.modelLabel || 'Abhify Lite'}`, kind: 'device', problem: null };
  }
  const m = st.models;
  if (m.loading && !m.loaded) return { label: 'Loading models…', kind: 'loading', problem: null };
  if (m.forbidden) return { label: 'Computer\'s default model', kind: 'remote', problem: null };
  if (m.loaded && m.ollama === false) return { label: 'Ollama not reachable', kind: 'error', problem: m.error };
  const pick = st.chatModel || m.def;
  const installed = (n) => m.list.some((x) => x.name === n || x.name === `${n}:latest`);
  if (m.loaded && !m.list.length) return { label: 'No chat model installed', kind: 'error', problem: 'Install one in Models & setup.' };
  if (m.loaded && pick && !installed(pick)) return { label: `${pick} · not installed`, kind: 'error', problem: `${pick} is not installed in Ollama.` };
  return { label: pick ? (st.chatModel ? pick : `${pick} · default`) : 'Default model', kind: 'ok', problem: null };
}

function renderModelChip() {
  const chip = $('#modelChip'); if (!chip) return;
  const v = modelChipView();
  $('#modelLabel').textContent = v.label;
  chip.dataset.state = v.kind;
  chip.title = v.problem || 'Choose the AI model for this chat';
  chip.setAttribute('aria-label', `AI model: ${v.label}${v.problem ? '. ' + v.problem : ''}`);
}

function renderModelPop() {
  const pop = $('#modelPop'), m = state.models;
  const cur = state.chatModel || m.def;
  let html = '<div class="pop-head">AI model for this chat</div>';
  if (state.engine === 'device') {
    html += '<p class="muted small pad">The model on this device is chosen from its memory. Change where the AI runs in Settings.</p>';
  } else if (m.forbidden) {
    html += '<p class="muted small pad">Model choice is available on the computer running Abhify.AI. This device uses the computer\'s default model.</p>';
  } else if (m.loading && !m.loaded) {
    html += '<p class="muted small pad"><span class="typing"><span></span><span></span><span></span></span> Loading models…</p>';
  } else if (m.ollama === false) {
    html += `<p class="small pad err">${esc(m.error || 'Ollama isn\'t reachable.')}</p><button type="button" class="btn btn-ghost pop-act" data-model-act="retry">Try again</button>`;
  } else if (!m.list.length) {
    html += '<p class="muted small pad">No chat model is installed in Ollama yet. Install one in Models &amp; setup (nothing is downloaded from here).</p>';
  } else {
    html += m.list.map((x) => {
      const sel = cur && (x.name === cur || x.name === `${cur}:latest`);
      const meta = [x.parameter_size, x.quantization, x.size_gb ? `${x.size_gb} GB` : null].filter(Boolean).join(' · ');
      return `<button type="button" class="model-opt" role="option" aria-selected="${sel ? 'true' : 'false'}" data-model="${esc(x.name)}">
        <span class="radio" aria-hidden="true"></span><span class="opt-main"><b>${esc(x.name)}</b><small>${esc(meta || 'Local · Ollama')} · local</small></span>
        ${x.default ? '<span class="badge">Default</span>' : ''}</button>`;
    }).join('');
    html += '<p class="muted small pad">Used for your next messages in this chat. Earlier answers keep the model they were written with.</p>';
  }
  if (!state.remote && state.engine !== 'device') html += '<button type="button" class="btn btn-ghost pop-act" data-model-act="manage">Manage models</button>';
  pop.innerHTML = html;
}

function openModelPop() {
  if (state.engine !== 'device' && !state.models.loaded && !state.models.loading) loadModels().then(renderModelPop);
  renderModelPop();
  $('#modelPop').classList.remove('hidden'); $('#modelChip').setAttribute('aria-expanded', 'true');
  ($('#modelPop').querySelector('[aria-selected="true"]') || $('#modelPop').querySelector('button') || $('#modelPop')).focus();
}
function closeModelPop(focusChip = false) {
  $('#modelPop').classList.add('hidden'); $('#modelChip').setAttribute('aria-expanded', 'false');
  if (focusChip) $('#modelChip').focus();
}
function pickChatModel(name) {
  if (!state.models.list.some((x) => x.name === name)) { toast('That model is no longer installed'); loadModels(); return; }
  const def = state.models.def;
  state.chatModel = def && (name === def || name === `${def}:latest`) ? null : name;
  rememberThreadModel(); renderModelChip(); closeModelPop(true);
  toast(`Next answers in this chat: ${name}`);
}
function bindModelPicker() {
  $('#modelChip').addEventListener('click', () => ($('#modelPop').classList.contains('hidden') ? openModelPop() : closeModelPop()));
  $('#modelPop').addEventListener('click', (e) => {
    const opt = e.target.closest('[data-model]'); if (opt) return pickChatModel(opt.dataset.model);
    const act = e.target.closest('[data-model-act]')?.dataset.modelAct;
    if (act === 'retry') loadModels().then(renderModelPop);
    if (act === 'manage') { closeModelPop(); openSystem(); }
  });
  $('#modelPop').addEventListener('keydown', (e) => {
    const opts = [...$('#modelPop').querySelectorAll('button')];
    const i = opts.indexOf(document.activeElement);
    if (e.key === 'Escape') { e.preventDefault(); closeModelPop(true); }
    else if (e.key === 'ArrowDown' && opts.length) { e.preventDefault(); opts[(i + 1) % opts.length].focus(); }
    else if (e.key === 'ArrowUp' && opts.length) { e.preventDefault(); opts[(i - 1 + opts.length) % opts.length].focus(); }
  });
  document.addEventListener('click', (e) => { if (!e.target.closest('#modelBar')) closeModelPop(); });
}

/** Settings → Default chat model (PC only): the installed chat models, saved with PUT /models/default. */
function renderDefaultModelSetting() {
  const f = $('#defaultModelField'), m = state.models;
  const show = state.engine !== 'device' && !state.remote && m.loaded && !m.forbidden;
  f.classList.toggle('hidden', !show);
  if (!show) return;
  const sel = $('#setModel');
  sel.innerHTML = m.list.map((x) => `<option value="${esc(x.name)}" ${x.default ? 'selected' : ''}>${esc(x.name)}${x.parameter_size ? ` · ${esc(x.parameter_size)}` : ''}</option>`).join('');
  sel.disabled = !m.list.length || m.locked;
  $('#setModelInfo').textContent = m.ollama === false ? (m.error || 'Ollama isn\'t reachable.')
    : !m.list.length ? 'No chat model installed. Install one in Models & setup.'
    : m.locked ? 'Set by the LLM_MODEL environment variable on this computer.' : 'Used when you start a new chat. Each chat can pick another model from the chat.';
}
async function saveDefaultModel() {
  const sel = $('#setModel'), m = state.models;
  if ($('#defaultModelField').classList.contains('hidden') || sel.disabled || !sel.value) return;
  if (m.list.find((x) => x.default)?.name === sel.value) return;
  try {
    const r = await api('/models/default', { method: 'PUT', body: { model: sel.value } });
    Object.assign(state.models, { list: r.models || [], def: r.default });
    renderModelChip(); toast(`Default chat model: ${r.default}`);
  } catch (e) { toast(`Default model not changed: ${e.message}`); }
}

async function loadChatHistory() {
  await state.ready;                       // the tab can open before the first connection check finishes
  restoreThreadModel();                    // dev.8: this chat's model choice (kept in this browser)
  try { const p = JSON.parse(store.get('abhify_project') || 'null'); if (p?.id && state.threadId) setChatProject(p); } catch { /* none */ }
  if (state.engine === 'device') return loadDeviceHistory();
  if (!state.threadId || state.apiBase === null) return;
  try {
    const { messages } = await api(`/conversations/${encodeURIComponent(state.threadId)}?limit=50`);
    messages.forEach((m, i) => {
      const last = i === messages.length - 1, q = i > 0 && messages[i - 1].role === 'user' ? messages[i - 1].content : '';
      addMsg(m.role === 'user' ? 'user' : 'bot', m.role === 'user' ? esc(m.content) : botHtml(m.content, m.tools, m.sources) + msgTools(m.content, { text: q, regen: last }));
    });
  } catch { /* offline: keep the welcome message only */ }
}

function newChat(project = null) {
  state.threadId = null; store.set(LS.thread, ''); store.set(LS.deviceThread, '');
  state.chatModel = null; renderModelChip();                                   // new chats start on the default model
  setChatProject(project?.id ? project : null);                               // a new chat is outside any project unless started from one
  $('#chatLog').querySelectorAll('.msg').forEach((m, i) => { if (i > 0) m.remove(); });   // keep the welcome bubble
  toast('New conversation started');
}

async function loadPreferences() {
  if (state.apiBase === null) return;
  try {
    const p = await api('/preferences');
    if (p.user_name && !displayName() && !['there', 'Abhi'].includes(p.user_name)) { state.userName = p.user_name; setNameUI(); if (current === 'home') renderHome(); }
  } catch { /* keep local name */ }
}

/* -- sheet helpers -- */
const openSheet = (id) => $(`#${id}`).classList.remove('hidden');
const closeSheet = (id) => $(`#${id}`).classList.add('hidden');
function adminError(e) {
  return /only allowed from the computer/i.test(e.message)
    ? 'This action only works on the computer running Abhify.AI.' : e.message;
}
const gb = (n) => (n == null ? '?' : `${n} GB`);
const stat = (k, v, cls = '') => `<div class="stat ${cls}"><div class="k">${esc(k)}</div><div class="v">${esc(v)}</div></div>`;

/* -- first-run setup wizard -- */
const setup = { diag: null, profiles: null, polling: null, pullStatus: {}, testMsg: '', autoPlan: null, autoTimer: null };

async function openSetup() {
  if (sessionStorage.getItem('abhify_setup_skipped')) return;
  openSheet('setupSheet');
  $('#setupBody').innerHTML = skeleton(2) + '<p class="empty">Checking this computer…</p>';
  await refreshSetup();
}

async function refreshSetup() {
  try {
    [setup.diag, setup.profiles] = await Promise.all([api('/system/diagnostics'), api('/setup/profiles')]);
  } catch (e) {
    $('#setupBody').innerHTML = `<p class="empty">⚠️ Can't reach the Abhify.AI backend (${esc(e.message)}).<br>Start it with start.bat, then reopen this window.</p>`;
    return;
  }
  renderSetup();
}

function renderSetup() {
  const d = setup.diag, P = setup.profiles, rec = d.recommended_profile.profile;
  const gpu = d.gpus.length ? d.gpus.map((g) => `${g.name} (${g.vram_gb} GB)`).join(', ') : 'No NVIDIA GPU, CPU mode';
  const ol = d.ollama;
  const active = P.profiles[P.active] || P.profiles.balanced;
  const pulling = Object.values(setup.pullStatus).find((p) => p.status === 'downloading');

  let dl = '';
  if (!ol.reachable) {
    dl = `<p>⚠️ <b>Ollama isn't running.</b> ${ol.installed ? 'Start the Ollama app' : 'Install it from <a href="https://ollama.com/download" target="_blank" rel="noopener">ollama.com/download</a>, then start it'} and press Re-check.</p>
          <button class="btn btn-ghost" data-setup="recheck">↻ Re-check</button>`;
  } else if (active.missing.length) {
    dl = active.disk.map((c) => (c.ok
      ? `<div class="row"><span class="grow">${esc(c.model)}: about <b>${c.estimated_download_gb} GB</b> download (${c.free_gb} GB free)</span>
         <button class="btn btn-primary btn-sm" data-setup="pull" data-model="${esc(c.model)}" ${pulling ? 'disabled' : ''}>Download</button></div>`
      : `<p class="stat bad">❌ ${esc(c.model)} needs about ${c.required_free_gb} GB free in ${esc(c.models_dir)}, but only ${c.free_gb} GB is available. Free up space or choose a smaller profile.</p>`)).join('');
    if (pulling) {
      const pct = pulling.total ? Math.round((100 * pulling.completed) / pulling.total) : 0;
      dl += `<p class="small">Downloading ${esc(pulling.model)}: ${esc(pulling.message)} ${pct ? pct + '%' : ''}</p>
             <div class="progress-line"><span style="width:${pct}%"></span></div>`;
    }
  } else {
    dl = `<p>✅ All models for <b>${esc(active.label)}</b> are installed.</p>`;
  }

  $('#setupBody').innerHTML = `
    <div class="step"><h3>⚡ Set up automatically</h3>
      <p class="small">Abhify.AI picks the AI that fits this computer, downloads it once, checks that it really works, and then runs without internet.</p>
      <div class="row"><button class="btn btn-primary btn-sm" data-setup="auto-plan">See what will be installed</button></div>
      <div id="autoBox"></div></div>
    <div class="step"><h3>1 · This computer</h3><div class="stats">
      ${stat('System', `${d.os.system} ${d.os.release} (${d.os.arch})`)}
      ${stat('CPU', `${d.cpu.logical_cores} threads`)}
      ${stat('RAM', d.ram.total_gb ? `${d.ram.total_gb} GB (${d.ram.available_gb} free)` : 'unknown')}
      ${stat('GPU', gpu, d.gpus.length ? 'ok' : 'warn')}
      ${stat('Free disk (models)', gb(d.disk.models_free_gb), d.disk.models_free_gb < 6 ? 'warn' : 'ok')}
      ${stat('Ollama', ol.reachable ? `running ${ol.version || ''}` : 'not running', ol.reachable ? 'ok' : 'bad')}
    </div></div>
    <div class="step"><h3>2 · Choose AI models</h3>
      <p class="muted small">Recommended: <b>${esc(P.profiles[rec].label)}</b> (${esc(d.recommended_profile.reason)}). You can change this later.</p>
      <div class="profiles">${Object.entries(P.profiles).map(([k, p]) => `
        <button class="profile ${k === P.active ? 'active' : ''}" data-setup="profile" data-profile="${k}">
          <b>${esc(p.label)}</b>${k === rec ? '<span class="badge rec">Recommended</span>' : ''}${p.installed ? '<span class="badge">Installed</span>' : ''}
          <div class="small muted">${esc(p.llm)} + ${esc(p.embed)} · ~${p.download_gb.toFixed(1)} GB</div>
          <div class="small">${esc(p.note)}</div>
        </button>`).join('')}</div></div>
    <div class="step"><h3>3 · Download</h3>${dl}
      <p class="muted small">Models download once and then work offline. Nothing downloads unless you click.</p></div>
    <div class="step"><h3>4 · Test</h3>
      <button class="btn btn-ghost" data-setup="test" ${!ol.reachable || active.missing.length ? 'disabled' : ''}>▶ Run a quick test</button>
      <span class="small" id="setupTest">${setup.testMsg}</span></div>`;
  $('#setupFinish').disabled = !(ol.reachable && !active.missing.length);
}

async function setupAction(el) {
  const a = el.dataset.setup;
  try {
    if (a === 'recheck') await refreshSetup();
    else if (a === 'profile') {
      await api('/setup/profile', { method: 'POST', body: { profile: el.dataset.profile } });
      setup.testMsg = ''; await refreshSetup();
    } else if (a === 'pull') {
      const m = el.dataset.model;
      const c = setup.profiles.profiles[setup.profiles.active].disk.find((x) => x.model === m);
      if (!confirm(`Download ${m}?\n\nAbout ${c.estimated_download_gb} GB will be downloaded once and stored by Ollama in:\n${c.models_dir}`)) return;
      await api('/setup/models/pull', { method: 'POST', body: { model: m, confirm: true } });
      pollPull();
    } else if (a === 'auto-plan') {
      renderAutoPlan(await api('/setup/plan'));
    } else if (a === 'auto-start') {
      const p = setup.autoPlan;
      if (!confirm(`Start automatic setup?\n\nAbout ${p.download_gb} GB will be downloaded once:\n${p.components.filter((c) => c.action !== 'ok').map((c) => `• ${c.label}`).join('\n')}\n\nYou can pause or cancel at any time; nothing already downloaded is lost.`)) return;
      await api('/setup/auto', { method: 'POST', body: { confirm: true } });
      pollAuto();
    } else if (a === 'auto-pause' || a === 'auto-cancel') {
      await api(`/setup/auto/${a === 'auto-pause' ? 'pause' : 'cancel'}`, { method: 'POST' });
    } else if (a === 'test') {
      $('#setupTest').textContent = ' Testing… (the first run loads the model and can take a minute)';
      const r = await api('/setup/test', { method: 'POST', timeout: 200000 });
      setup.testMsg = r.ok ? ` ✅ Working: answered in ${r.seconds}s` : ` ❌ ${esc(r.error)}`;
      $('#setupTest').innerHTML = setup.testMsg;
    }
  } catch (e) { toast(adminError(e)); }
}

function renderAutoPlan(p) {
  setup.autoPlan = p;
  const rows = p.components.map((c) => `<li>${c.action === 'ok' ? '✅' : '⬇️'} ${esc(c.label)}${c.download_gb ? ` · ~${c.download_gb} GB` : ''}${c.action === 'start' ? ' · will be started' : ''}</li>`).join('');
  const disk = p.disk.models ? `Models need ~${p.disk.models.required_free_gb} GB free (${p.disk.models.free_gb} GB free).` : '';
  $('#autoBox').innerHTML = `
    <p class="small">Recommended for this computer: <b>${esc(p.profile_label)}</b> (${esc(p.reason)}).</p>
    <ul class="small">${rows}</ul>
    <p class="small">${p.nothing_to_do ? 'Everything is already installed.' : `Download: about <b>${p.download_gb} GB</b>, once. ${esc(disk)} Internet is needed only for this step.`}</p>
    ${p.problems.length ? `<p class="small bad">⚠️ ${p.problems.map(esc).join('<br>⚠️ ')}</p>` : ''}
    <div class="row"><button class="btn btn-primary btn-sm" data-setup="auto-start" ${p.ready_to_start ? '' : 'disabled'}>${p.nothing_to_do ? 'Check and finish' : 'Start automatic setup'}</button>
      <button class="btn btn-ghost btn-sm" data-setup="auto-plan">↻ Re-check</button></div>`;
}

function pollAuto() {
  clearInterval(setup.autoTimer);
  const tick = async () => {
    let j;
    try { j = await api('/setup/auto'); } catch { return; }
    const pct = j.progress && j.progress.total ? Math.round((100 * j.progress.done) / j.progress.total) : null;
    const running = j.status === 'running';
    $('#autoBox').innerHTML = `
      <p class="small"><b>${esc(j.status === 'done' ? '✅ Done' : j.status === 'failed' ? '❌ Stopped' : j.status === 'paused' ? '⏸ Paused' : j.status === 'cancelled' ? '⏹ Cancelled' : '⏳ Working')}</b> · ${esc(j.message || '')}</p>
      ${pct !== null && running ? `<div class="progress-line"><span style="width:${pct}%"></span></div><p class="small">${pct}%</p>` : ''}
      ${j.error ? `<p class="small bad">${esc(j.error)}</p>` : ''}
      <div class="row">${running ? '<button class="btn btn-ghost btn-sm" data-setup="auto-pause">⏸ Pause</button><button class="btn btn-ghost btn-sm danger" data-setup="auto-cancel">Cancel</button>'
        : j.status === 'done' ? '' : '<button class="btn btn-primary btn-sm" data-setup="auto-plan">↻ Resume / retry</button>'}</div>`;
    if (!running) { clearInterval(setup.autoTimer); if (j.status === 'done') { toast('✅ Setup complete'); refreshSetup(); checkConnection(); } }
  };
  tick(); setup.autoTimer = setInterval(tick, 1500);
}

function pollPull() {
  clearInterval(setup.polling);
  setup.polling = setInterval(async () => {
    try {
      setup.pullStatus = (await api('/setup/models/pull')).pulls;
      const list = Object.values(setup.pullStatus);
      if (!list.some((p) => p.status === 'downloading')) {
        clearInterval(setup.polling);
        const failed = list.find((p) => p.status === 'failed');
        if (failed) toast(`Download failed: ${failed.message}`);
        await refreshSetup();
      } else renderSetup();
    } catch { clearInterval(setup.polling); }
  }, 1500);
}

async function finishSetup() {
  try { await api('/setup/complete', { method: 'POST' }); } catch (e) { toast(adminError(e)); return; }
  closeSheet('setupSheet'); toast('🎉 Abhify.AI is ready!');
  state.apiBase = null; await checkConnection();
}

/* -- memory & history manager -- */
async function openMemory() {
  closeSheet('settingsSheet'); closePages(); openSheet('memorySheet');
  $('#memoryList').innerHTML = skeleton(1);
  try {
    const [prefs, m] = await Promise.all([api('/preferences'), api('/memory')]);
    $('#prefMemory').checked = prefs.memory_enabled;
    $('#prefHistory').checked = prefs.save_history;
    $('#prefRetention').value = String(prefs.history_retention_days);
    renderMemories(m.memories);
  } catch (e) { $('#memoryList').innerHTML = `<p class="empty">Memory needs the backend (${esc(e.message)}).</p>`; }
}

function renderMemories(list) {
  $('#memoryList').innerHTML = list.length ? list.map((m) => `
    <div class="list-item" data-id="${m.id}">
      <div class="text"><span class="mem-text">${esc(m.content)}</span><div class="meta">${esc(m.category.replace('_', ' '))} · ${m.source === 'assistant' ? 'saved from chat' : 'added by you'} · ${esc(m.updated_at)}</div></div>
      <div class="actions">
        <button class="mini-btn ${m.pinned ? 'on' : ''}" data-mem="pin" title="${m.pinned ? 'Unpin' : 'Pin: always use'}">📌</button>
        <button class="mini-btn" data-mem="edit" title="Edit">✏️</button>
        <button class="mini-btn" data-mem="delete" title="Delete">🗑️</button>
      </div>
    </div>`).join('') : '<p class="empty">No memories yet. Add one above, or say "remember that…" in chat.</p>';
}

async function memoryAction(el) {
  const item = el.closest('[data-id]'), id = item.dataset.id;
  try {
    if (el.dataset.mem === 'delete') {
      if (!confirm('Delete this memory?')) return;
      await api(`/memory/${id}`, { method: 'DELETE' });
    } else if (el.dataset.mem === 'pin') {
      await api(`/memory/${id}`, { method: 'PATCH', body: { pinned: !el.classList.contains('on') } });
    } else if (el.dataset.mem === 'edit') {
      const cur = item.querySelector('.mem-text').textContent;
      const next = prompt('Edit memory', cur);
      if (!next || next === cur) return;
      await api(`/memory/${id}`, { method: 'PATCH', body: { content: next } });
    }
    renderMemories((await api('/memory')).memories);
  } catch (e) { toast(e.message); }
}

async function savePref(body) {
  try { await api('/preferences', { method: 'PUT', body }); toast('Saved'); } catch (e) { toast(e.message); }
}

/* -- system & data -- */
async function openSystem() {
  closeSheet('settingsSheet'); closePages(); openSheet('systemSheet');
  $('#systemInfo').innerHTML = skeleton(1);
  if (state.remote) {
    $('#systemInfo').innerHTML = '<p class="empty">System details, backups and model setup are only available on the computer running Abhify.AI. Your AI runs there; this device only shows the app.</p>';
    $('#dataRoot').textContent = ''; $('#backupList').innerHTML = '';
    return;
  }
  try {
    const [d, b] = await Promise.all([api('/system/diagnostics'), api('/backup')]);
    const gpu = d.gpus.length ? d.gpus.map((g) => `${g.name} · ${g.vram_gb} GB`).join(', ') : 'none (CPU mode)';
    $('#systemInfo').innerHTML = [
      stat('Abhify.AI', `v${d.app.version} · schema v${d.app.schema_version}`),
      stat('Models', `${d.settings.models.llm} + ${d.settings.models.embed}`),
      stat('Profile', d.settings.model_profile),
      stat('Ollama', d.ollama.reachable ? 'running' : 'not running', d.ollama.reachable ? 'ok' : 'bad'),
      stat('Internet', d.internet ? 'connected' : 'offline', d.internet ? 'ok' : 'warn'),
      stat('GPU', gpu, d.gpus.length ? 'ok' : 'warn'),
      stat('RAM', d.ram.total_gb ? `${d.ram.total_gb} GB` : '?'),
      stat('Data size', `${d.disk.data_used_mb} MB · ${d.disk.data_free_gb} GB free`),
    ].join('');
    $('#dataRoot').textContent = `📁 ${b.data_root}`;
    $('#exportBackup').href = `${state.apiBase}/backup/export`;
    renderBackups(b.backups);
  } catch (e) {
    $('#systemInfo').innerHTML = stateHtml('error', "Couldn't read this computer's details", e.message === 'offline' ? 'The Abhify.AI app on this computer is not reachable. Backups and setup need it.' : e.message);
    $('#dataRoot').textContent = '–'; $('#backupList').innerHTML = '';
  }
}

function renderBackups(list) {
  $('#backupList').innerHTML = list.slice(0, 5).map((b) =>
    `<div class="list-item"><div class="text">${esc(b.name)}<div class="meta">${(b.size / 1048576).toFixed(1)} MB · ${esc(b.created)}</div></div></div>`).join('')
    || '<p class="muted small">No backups yet.</p>';
}

async function systemAction(id) {
  try {
    if (id === 'backupNow') {
      const r = await api('/backup', { method: 'POST', timeout: 300000 });
      toast(`💾 Backup saved: ${r.file}`); renderBackups((await api('/backup')).backups);
    } else if (id === 'moveData') {
      const path = prompt('Move your Abhify.AI data to which folder? (absolute path, e.g. E:\\AbhifyData)\nYour current data is copied, not deleted.');
      if (!path) return;
      const r = await api('/system/data-location', { method: 'POST', body: { path }, timeout: 600000 });
      alert(`Data copied to:\n${r.new_root}\n\nRestart Abhify.AI to start using it. The old folder is kept; delete it yourself once everything works.`);
    }
  } catch (e) { toast(adminError(e)); }
}

async function restoreBackup(file) {
  if (!file) return;
  if (!confirm(`Restore "${file.name}"?\n\nOn the next restart your current data is moved to a "pre-restore" backup folder (not deleted) and replaced by this backup.`)) return;
  const form = new FormData(); form.append('file', file);
  try {
    await api('/backup/restore', { method: 'POST', form, timeout: 600000 });
    alert('Backup staged. Restart Abhify.AI to apply it.');
  } catch (e) { toast(adminError(e)); }
}

function bindV2Events() {
  $('#newChat').addEventListener('click', newChat);
  $('#openMemory').addEventListener('click', openMemory);
  $('#openSystem').addEventListener('click', openSystem);
  $$('[data-close]').forEach((b) => b.addEventListener('click', () => closeSheet(b.dataset.close)));
  ['memorySheet', 'systemSheet'].forEach((id) =>
    $(`#${id}`).addEventListener('click', (e) => { if (e.target.id === id) closeSheet(id); }));

  // setup wizard
  $('#setupBody').addEventListener('click', (e) => { const el = e.target.closest('[data-setup]'); if (el) setupAction(el); });
  $('#setupSkip').addEventListener('click', () => {
    sessionStorage.setItem('abhify_setup_skipped', '1'); closeSheet('setupSheet');
    toast('Limited mode: news, quizzes and saved content still work. Finish setup any time in Settings → System.');
  });
  $('#setupFinish').addEventListener('click', finishSetup);

  // memory
  $('#memoryList').addEventListener('click', (e) => { const el = e.target.closest('[data-mem]'); if (el) memoryAction(el); });
  $('#memoryForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const content = $('#memoryText').value.trim(); if (!content) return;
    try {
      await api('/memory', { method: 'POST', body: { content, category: $('#memoryCat').value } });
      $('#memoryText').value = ''; renderMemories((await api('/memory')).memories);
    } catch (err) { toast(err.message); }
  });
  $('#prefMemory').addEventListener('change', (e) => savePref({ memory_enabled: e.target.checked }));
  [['#setDetail', 'answer_detail'], ['#setDepth', 'answer_depth'], ['#setStyle', 'answer_style']].forEach(([sel, key]) =>
    $(sel)?.addEventListener('change', (e) => savePref({ [key]: e.target.value })));
  $('#setLanguage')?.addEventListener('change', (e) => {
    const v = e.target.value.trim();
    if (v && !/^[\p{L}]+(?:[ -][\p{L}]+)*$/u.test(v)) { toast('Use a language name only, e.g. Hindi.'); return; }
    savePref({ answer_language: v });
  });
  $('#prefHistory').addEventListener('change', (e) => savePref({ save_history: e.target.checked }));
  $('#prefRetention').addEventListener('change', (e) => savePref({ history_retention_days: +e.target.value }));
  $('#clearHistory').addEventListener('click', async () => {
    if (!confirm('Delete ALL saved chat conversations on this computer?')) return;
    try { const r = await api('/conversations?confirm=true', { method: 'DELETE' }); newChat(); toast(`Deleted ${r.deleted} conversations`); }
    catch (e) { toast(e.message); }
  });
  $('#clearMemories').addEventListener('click', async () => {
    if (!confirm('Delete ALL personal memories? This cannot be undone unless you have a backup.')) return;
    try { await api('/memory?confirm=true', { method: 'DELETE' }); renderMemories([]); } catch (e) { toast(e.message); }
  });

  // system & data
  $('#backupNow').addEventListener('click', () => systemAction('backupNow'));
  $('#moveData').addEventListener('click', () => systemAction('moveData'));
  $('#restoreFile').addEventListener('change', (e) => { restoreBackup(e.target.files[0]); e.target.value = ''; });
  $('#rerunSetup').addEventListener('click', () => { closeSheet('systemSheet'); sessionStorage.removeItem('abhify_setup_skipped'); openSetup(); });
  bindM2Events();
}

/* ---------- 12. M2: KNOWLEDGE FOLDER, COMPANION ACCESS, UPDATES ---------- */
const knowledgeUI = { timer: null, filter: '' };
const STATUS_ICON = { indexed: '✅', pending: '⏳', failed: '❌', skipped: '⏭️', removed: '🗑️' };
const ago = (ts) => (ts ? timeAgo(ts * 1000) : 'never');

async function openKnowledge() {
  closeSheet('settingsSheet'); closePages(); openSheet('knowledgeSheet');
  await refreshKnowledge();
  clearInterval(knowledgeUI.timer);
  knowledgeUI.timer = setInterval(() => {
    if ($('#knowledgeSheet').classList.contains('hidden')) { clearInterval(knowledgeUI.timer); return; }
    refreshKnowledge(true);
  }, 2000);
}

async function refreshKnowledge(quiet = false) {
  let st;
  try { st = await api('/knowledge/status'); } catch (e) {
    if (!quiet) $('#kStats').innerHTML = `<p class="empty">Needs the Abhify.AI backend on this computer (${esc(e.message)}).</p>`;
    return;
  }
  const c = st.counts;
  $('#kFolder').textContent = `📁 ${st.folder}`;
  $('#kStats').innerHTML = [
    stat('Status', st.running ? (st.paused ? 'Paused' : st.scanning ? 'Scanning…' : 'Watching') : 'Stopped', st.running && !st.paused ? 'ok' : 'warn'),
    stat('Discovered', c.discovered), stat('Indexed', c.indexed, 'ok'),
    stat('Skipped', c.skipped), stat('Failed', c.failed, c.failed ? 'bad' : ''), stat('Pending', c.pending, c.pending ? 'warn' : ''),
    stat('Last indexed', ago(st.last_success_at)), stat('Search index', `${st.chunks} chunks · ${st.index_embed_model || st.configured_embed_model}`),
  ].join('');
  const pct = st.batch_total ? Math.round((100 * st.batch_done) / st.batch_total) : 0;
  $('#kBar').style.width = `${st.current_file || st.queued ? pct : 0}%`;
  $('#kNow').textContent = st.current_file ? `Indexing ${st.current_file}… (${st.batch_done}/${st.batch_total})`
    : st.queued ? `${st.queued} file(s) waiting (files still being copied are indexed once they stop changing)` : (st.last_error ? `Last problem: ${st.last_error}` : '');
  $('#kStartStop').textContent = st.running ? 'Stop watching' : 'Start watching';
  $('#kStartStop').className = `btn btn-sm ${st.running ? 'btn-ghost' : 'btn-primary'}`;
  $('#kPause').textContent = st.paused ? 'Resume' : 'Pause';
  $('#kPause').disabled = $('#kRescan').disabled = !st.running;

  // embedding-model change workflow
  const rb = st.rebuild || {};
  $('#kRebuild').innerHTML = rb.status === 'running'
    ? `<div class="step"><b>Rebuilding the index with ${esc(rb.model)}…</b> ${rb.done}/${rb.total} documents. The old index keeps working until the new one is ready.</div>`
    : st.rebuild_required
      ? `<div class="step"><b>⚠️ Rebuild required.</b> Your documents were indexed with <b>${esc(st.index_embed_model)}</b>, but the configured embedding model is <b>${esc(st.configured_embed_model)}</b>. Their vectors are incompatible, so document search is paused until you rebuild. The current index is kept until the new one is validated.
         <div class="row"><button class="btn btn-primary btn-sm" data-k="rebuild">Rebuild index</button></div></div>`
      : rb.status === 'failed' ? `<div class="step">❌ Last rebuild failed (${esc(rb.error || '')}). The previous index is still active.</div>`
      : st.previous_collections && st.previous_collections.length
        ? `<div class="step small">An older index (${st.previous_collections.length}) is kept as a fallback. <button class="link-btn" data-k="cleanup">Delete old index to free space</button></div>` : '';

  if (!quiet || knowledgeUI.lastCount !== c.discovered + c.failed + c.indexed) {
    knowledgeUI.lastCount = c.discovered + c.failed + c.indexed;
    const { files } = await api(`/knowledge/files${knowledgeUI.filter ? `?status=${knowledgeUI.filter}` : ''}`);
    $('#kListInfo').textContent = `${files.length} file(s)`;
    $('#kFiles').innerHTML = files.map((f) => `
      <div class="list-item" data-doc="${f.id}">
        <div class="text">${STATUS_ICON[f.status] || ''} ${esc(f.path)}
          <div class="meta">${esc(f.status)}${f.chunks ? ` · ${f.chunks} chunks` : ''}${f.error ? ` · ${esc(f.error)}` : ''}</div></div>
        ${f.status === 'failed' ? '<div class="actions"><button class="mini-btn" data-k="retry-one" title="Retry">↻</button></div>' : ''}
      </div>`).join('') || `<p class="muted small">${st.running ? 'No files yet. Drop PDFs or notes into the folder.' : 'Start watching to index this folder.'}</p>`;
  }
}

async function knowledgeAction(action, el) {
  try {
    if (action === 'startstop') {
      const running = $('#kStartStop').textContent.startsWith('Stop');
      await api(`/knowledge/watcher/${running ? 'stop' : 'start'}`, { method: 'POST' });
    } else if (action === 'pause') {
      await api(`/knowledge/watcher/${$('#kPause').textContent === 'Resume' ? 'resume' : 'pause'}`, { method: 'POST' });
    } else if (action === 'rescan') await api('/knowledge/rescan', { method: 'POST' });
    else if (action === 'retry') { const r = await api('/knowledge/retry', { method: 'POST' }); toast(`Retrying ${r.reset} file(s)`); }
    else if (action === 'retry-one') await api(`/knowledge/retry?doc_id=${el.closest('[data-doc]').dataset.doc}`, { method: 'POST' });
    else if (action === 'change') {
      const cur = $('#kFolder').textContent.replace('📁 ', '');
      const path = prompt('Folder to watch (absolute path). Whole drives, your entire user folder and system folders are not allowed.', cur);
      if (!path || path === cur) return;
      const r = await api('/knowledge/folder', { method: 'PUT', body: { path } });
      toast(`Now using ${r.folder}${r.removed_from_old_folder ? ` · removed ${r.removed_from_old_folder} file(s) from the old folder` : ''}`);
    } else if (action === 'rebuild') {
      if (!confirm('Rebuild the document index with the new embedding model?\n\nEvery document is embedded again (this can take a while). The current index stays active until the new one is complete and validated.')) return;
      await api('/knowledge/rebuild', { method: 'POST', body: { confirm: true } });
    } else if (action === 'cleanup') {
      if (!confirm('Delete the old index kept from before the last rebuild?')) return;
      await api('/knowledge/rebuild/cleanup', { method: 'POST' });
    } else if (action === 'help') {
      alert('How the knowledge folder works\n\n• New, changed, renamed and deleted files are picked up automatically.\n• Files still being copied are indexed once they stop changing.\n• A changed file replaces its old version only after the new version is indexed.\n• Deleted files are removed from search.\n• Skipped: unsupported types, empty files, and anything that looks like a password, key or token.\n• Nothing outside this folder is ever read.');
      return;
    }
    await refreshKnowledge();
  } catch (e) { toast(adminError(e)); }
}

/* -- companion access (Mode B) -- */
const ROLE_LABEL = { owner_device: 'Your device', member: 'Guest' };
const fmtTime = (t) => (t ? esc(String(t).replace('T', ' ').slice(0, 16)) + ' UTC' : '—');

async function renderCompanion() {
  const box = $('#companionBox');
  try {
    const st = await api('/companion');
    if (!st.this_device_is_host) {
      box.innerHTML = `<p class="small">This device${st.device_name ? ` (<b>${esc(st.device_name)}</b>)` : ''} is connected to Abhify.AI on another computer${st.authorized ? ` as ${st.role === 'member' ? '<b>a guest</b>: news, quizzes and guest chat only' : '<b>one of the owner\'s devices</b>'} ✅` : ' (not paired)'}. Membership and companion settings can only be changed on that computer.</p>`;
      return;
    }
    if (!st.enabled) {
      box.innerHTML = '<div class="row"><button class="btn btn-ghost btn-sm" data-c="on">Turn on companion access</button></div>';
      return;
    }
    const a = await api('/companion/admin');
    const pub = a.registration_mode === 'public';
    const devices = a.members.filter((m) => m.status === 'active');
    const openInv = a.invitations.filter((i) => i.status === 'active');
    box.innerHTML = `
      <p class="small">✅ On · port <b>${esc(a.port)}</b> · ${a.listening_on_lan ? '⚠️ listening on your local network' : 'this computer only (use Tailscale)'}${a.lan_access !== a.listening_on_lan ? ' · <b>restart Abhify.AI to apply the network setting</b>' : ''}</p>
      <p class="small">Phone over HTTPS (recommended): install Tailscale on this computer and the phone, then run <code class="break">${esc(a.tailscale_command)}</code></p>
      <div class="step small"><b>Registration: ${pub ? '⚠️ Public' : '🔒 Invite-only'}</b><br>
        ${pub ? 'Anyone who can reach this computer can join as a <b>guest</b> (news, quizzes, guest chat; never your data).'
              : 'Only devices with an invitation you create here can connect.'}
        <div class="row"><button class="btn btn-ghost btn-sm ${pub ? '' : 'danger'}" data-c="${pub ? 'mode-invite' : 'mode-public'}">${pub ? 'Return to invite-only' : 'Enable public registration…'}</button></div></div>
      <div class="step small"><b>Invite a device</b>
        <div class="row"><input id="invLabel" placeholder="Name (e.g. Asha's phone)" maxlength="40">
          <select id="invRole"><option value="member">Guest (no access to your data)</option><option value="owner_device">My own device (my chats, memories, documents)</option></select>
          <select id="invHours"><option value="24">valid 1 day</option><option value="72" selected>valid 3 days</option><option value="168">valid 7 days</option></select>
          <button class="btn btn-primary btn-sm" data-c="invite">Create invitation</button></div>
        <div id="invResult"></div></div>
      <div class="small"><b>Devices (${devices.length})</b>${devices.map((m) => `
        <div class="list-item"><div class="text">${esc(m.name)} <span class="muted">· ${ROLE_LABEL[m.role] || esc(m.role)}${m.origin === 'legacy' ? ' · paired before 2.0.0-dev.6' : ''}</span>
          <div class="meta">added ${fmtTime(m.created_at)} · last seen ${fmtTime(m.last_seen_at)}${m.expires_at ? ` · expires ${fmtTime(m.expires_at)}` : ''}</div></div>
          <div class="actions"><button class="mini-btn" data-c="rev-member" data-id="${m.id}" title="Revoke this device">Revoke</button></div></div>`).join('') || '<p class="muted">No devices yet.</p>'}</div>
      ${openInv.length ? `<div class="small"><b>Open invitations (${openInv.length})</b>${openInv.map((i) => `
        <div class="list-item"><div class="text">${esc(i.label || 'Invitation')} <span class="muted">· ${ROLE_LABEL[i.role]}</span><div class="meta">expires ${fmtTime(i.expires_at)}</div></div>
          <div class="actions"><button class="mini-btn" data-c="rev-inv" data-id="${i.id}">Cancel</button></div></div>`).join('')}</div>` : ''}
      <div class="row">
        <button class="btn btn-ghost btn-sm" data-c="${a.lan_access ? 'lan-off' : 'lan-on'}">${a.lan_access ? 'Stop local-network access' : 'Allow local-network access (HTTP)…'}</button>
        <button class="btn btn-ghost btn-sm danger" data-c="off">Turn off (revokes all devices)</button></div>`;
  } catch (e) { box.innerHTML = `<p class="muted small">${esc(e.message)}</p>`; }
}

async function companionAction(a, el) {
  try {
    if (a === 'off') {
      if (!confirm('Turn off companion access?\n\nEvery paired device and open invitation is revoked. Your chats, memories and documents are not affected.')) return;
      await api('/companion/disable', { method: 'POST' }); return renderCompanion();
    }
    if (a === 'mode-public') {
      if (!confirm('Enable PUBLIC registration?\n\nAnyone who can reach this computer (for example through your Tailscale network or local network) can register a device WITHOUT an invitation.\n\nThey join as GUESTS only: news, interview questions, quizzes and a guest chat. Guests never see your chats, memories or documents, and can never change settings.\n\nThis is not a hosted website: it only affects people who can already reach this computer.\n\nYou can return to invite-only at any time; existing guest devices stay until you revoke them.')) return;
      await api('/companion/registration-mode', { method: 'POST', body: { mode: 'public', confirm: true } }); return renderCompanion();
    }
    if (a === 'mode-invite') { await api('/companion/registration-mode', { method: 'POST', body: { mode: 'invite_only' } }); toast('Registration is invite-only again'); return renderCompanion(); }
    if (a === 'lan-on') {
      if (!confirm('Allow access from your local network?\n\nAfter a restart, Abhify.AI listens on ALL network connections of this computer (plain HTTP, not encrypted). Anyone on the same network can reach it; paired-device checks still apply.\n\nTailscale does NOT need this. Use it only on a trusted home network.')) return;
      await api('/companion/lan-access', { method: 'POST', body: { enabled: true, confirm: true } }); toast('Restart Abhify.AI to apply'); return renderCompanion();
    }
    if (a === 'lan-off') { await api('/companion/lan-access', { method: 'POST', body: { enabled: false } }); toast('Restart Abhify.AI to apply'); return renderCompanion(); }
    if (a === 'rev-member') {
      if (!confirm('Revoke this device? It loses access immediately.')) return;
      await api(`/companion/members/${el.dataset.id}/revoke`, { method: 'POST' }); return renderCompanion();
    }
    if (a === 'rev-inv') { await api(`/companion/invitations/${el.dataset.id}/revoke`, { method: 'POST' }); return renderCompanion(); }
    if (a === 'invite') {
      const r = await api('/companion/invitations', { method: 'POST', body: { label: $('#invLabel').value.trim(), role: $('#invRole').value, hours: +$('#invHours').value } });
      const lan = (r.lan_urls || [])[0];
      $('#invResult').innerHTML = `<p class="small"><b>Invitation code (shown once, single use, expires ${fmtTime(r.expires_at)}):</b><br><code class="break">${esc(r.code)}</code></p>
        <p class="small">On the phone, open <code class="break">https://&lt;your-pc&gt;.&lt;tailnet&gt;.ts.net/#invite=${esc(r.code)}</code> (after <code>${esc(r.tailscale_command)}</code>)${lan ? `, or on your local network <code class="break">${esc(lan)}/#invite=${esc(r.code)}</code>` : ''}. Send it privately.</p>`;
      return;
    }
    if (a === 'on' && !confirm('Turn on companion access?\n\nYour phone will be able to use Abhify.AI through this computer with an access token. Admin actions (backups, moving data, model downloads) stay limited to this computer. Never forward this port to the internet; use your home network or a private tunnel such as Tailscale.')) return;
    const r = await api('/companion/enable', { method: 'POST' });
    const link = `${r.lan_urls[0] || 'https://<your-pc>.<tailnet>.ts.net'}/#pair=${r.token}`;   // real port: see the Tailscale command
    $('#companionBox').innerHTML = `
      <div class="step small">
        <b>Your access token (shown once):</b><br><code class="break">${esc(r.token)}</code><br><br>
        On your phone, open <code class="break">${esc(link)}</code> (this pairs it automatically),
        or enter the address and token in Settings.<br><br>${esc(r.next_steps)}
      </div>
      <div class="row"><button class="btn btn-ghost btn-sm" data-c="done">I've saved it</button></div>`;
  } catch (e) { toast(adminError(e)); }
}

/** Invitation link: https://pc-address/#invite=<code>. The code is redeemed ONCE for this device's own
 *  credential; the code is removed from the address bar immediately. */
async function readInviteLink() {
  const m = location.hash.match(/invite=([A-Za-z0-9_\-]{16,64})/);
  if (!m) return false;
  history.replaceState(null, '', location.pathname + location.search);
  await state.ready;
  const guess = /iphone/i.test(navigator.userAgent) ? 'iPhone' : /ipad/i.test(navigator.userAgent) ? 'iPad' : /android/i.test(navigator.userAgent) ? 'Android phone' : 'Browser';
  const name = (prompt('Name this device (shown to the computer\'s owner):', guess) || guess).slice(0, 40);
  try {
    const r = await api('/companion/redeem', { method: 'POST', body: { code: m[1], device_name: name } });
    store.set(LS.token, r.token);
    await checkConnection();
    toast(r.role === 'member' ? '✅ Joined as a guest' : '✅ Paired with your computer');
  } catch (e) { toast(`❌ ${e.message}`); }
  return true;
}

async function joinAsGuest() {
  const guess = /iphone/i.test(navigator.userAgent) ? 'iPhone' : /android/i.test(navigator.userAgent) ? 'Android phone' : 'Browser';
  try {
    const r = await api('/companion/register', { method: 'POST', body: { device_name: guess } });
    store.set(LS.token, r.token);
    await checkConnection();
    toast('✅ Joined as a guest');
  } catch (e) { toast(`❌ ${e.message}`); }
}

/** Phone pairing: https://pc-address/#pair=<token> stores the token and removes it from the URL.
 *  Runs at start-up AND on hashchange, so a link opened while the app is already open works too.
 *  Other open windows of the app pick the token up through the `storage` event. The fragment is never
 *  sent to the server, and the "Paired" message is only shown after the computer accepts the token. */
function readPairingLink() {
  const m = location.hash.match(/pair=([A-Za-z0-9_\-]{16,})/);
  if (!m) return false;
  store.set(LS.token, m[1]);
  history.replaceState(null, '', location.pathname + location.search);
  state.ready.then(() => setTimeout(() => {
    const h = state.health;
    toast(!h ? '🔗 Token saved. Your computer isn\'t reachable yet.'
      : h.authorized === false ? '❌ Your computer rejected this pairing link. Create a new one there.'
      : '✅ Paired with your computer');
  }, 300));
  return true;
}

async function checkUpdates() {
  $('#updateResult').textContent = 'Checking…';
  try {
    const r = await api('/updates/check', { method: 'POST' });
    const when = r.last_check_at ? ` · checked ${timeAgo(r.last_check_at * 1000)}` : '';
    $('#updateResult').innerHTML = r.error ? esc(r.error)
      : !r.configured ? `v${esc(r.current)} · no update source configured`
      : r.update_available ? `🎉 v${esc(r.latest)} available (signed by your trusted publisher)${!r.upgrade_supported ? ' · ⚠️ needs an intermediate version first' : ''}. <button class="btn btn-sm btn-primary" data-u="download">Download</button>`
      : `✅ Up to date (v${esc(r.current)})${when}`;
  } catch (e) { $('#updateResult').textContent = e.message; }
}

async function updateAction(a) {
  try {
    if (a === 'download') {
      $('#updateResult').textContent = 'Downloading and verifying…';
      const r = await api('/updates/download', { method: 'POST', timeout: 3600000 });
      $('#updateResult').innerHTML = `✅ v${esc(r.version)} downloaded and verified (size and SHA-256 match the signed manifest). <button class="btn btn-sm btn-primary" data-u="install">Install…</button>`;
    } else if (a === 'install') {
      if (!confirm('Install the update now?\n\nThe installer stops Abhify.AI, backs up your data and keeps it. No administrator rights are needed. The previous installer is kept so you can go back if needed.\n\nStart Abhify.AI again when the installer finishes.')) return;
      const r = await api('/updates/install', { method: 'POST', body: { confirm: true } });
      $('#updateResult').textContent = r.note;
    }
  } catch (e) { $('#updateResult').textContent = e.message; }
}

function bindM2Events() {
  $('#openKnowledge').addEventListener('click', openKnowledge);
  $('#kbInfo').addEventListener('click', openKnowledge);
  $('#knowledgeSheet').addEventListener('click', (e) => {
    if (e.target.id === 'knowledgeSheet') closeSheet('knowledgeSheet');
    const el = e.target.closest('[data-k]'); if (el) knowledgeAction(el.dataset.k, el);
  });
  const map = { kStartStop: 'startstop', kPause: 'pause', kRescan: 'rescan', kRetry: 'retry', kChange: 'change', kOpenHelp: 'help' };
  Object.entries(map).forEach(([id, a]) => $(`#${id}`).addEventListener('click', () => knowledgeAction(a)));
  $('#kFilter').addEventListener('change', (e) => { knowledgeUI.filter = e.target.value; refreshKnowledge(); });
  $('#companionBox').addEventListener('click', (e) => {
    const el = e.target.closest('[data-c]'); if (!el) return;
    if (el.dataset.c === 'done') renderCompanion(); else companionAction(el.dataset.c === 'new' ? 'on' : el.dataset.c, el);
  });
  $('#openSystem').addEventListener('click', renderCompanion);
  $('#checkUpdates').addEventListener('click', checkUpdates);
  $('#updateResult').addEventListener('click', (e) => { const el = e.target.closest('[data-u]'); if (el) updateAction(el.dataset.u); });
}

document.addEventListener('DOMContentLoaded', boot);
