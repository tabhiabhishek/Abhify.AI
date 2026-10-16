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
  userName: store.get(LS.name) || 'Abhi',
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
};
store.set(LS.user, state.userId);

/* ---------- 2. UTILITIES ---------- */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** Tiny, safe Markdown renderer for chat answers (escape first, then format). */
function md(text) {
  const blocks = [];
  let s = esc(text).replace(/```(\w*)\n?([\s\S]*?)```/g, (_, _l, code) => {
    blocks.push(`<pre><code>${code.trim()}</code></pre>`); return `\u0000${blocks.length - 1}\u0000`;
  });
  s = s
    .replace(/`([^`\n]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<i>$2</i>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener">$2</a>');
  // lists + paragraphs
  const out = []; let list = null;
  for (const line of s.split('\n')) {
    const ul = line.match(/^\s*[-*•]\s+(.*)/), ol = line.match(/^\s*\d+[.)]\s+(.*)/);
    if (ul || ol) {
      const type = ul ? 'ul' : 'ol';
      if (list !== type) { if (list) out.push(`</${list}>`); out.push(`<${type}>`); list = type; }
      out.push(`<li>${(ul || ol)[1]}</li>`);
    } else {
      if (list) { out.push(`</${list}>`); list = null; }
      if (line.trim()) out.push(`<p>${line}</p>`);
    }
  }
  if (list) out.push(`</${list}>`);
  return out.join('').replace(/\u0000(\d+)\u0000/g, (_, i) => blocks[i]);
}

function toast(msg, ms = 2800) {
  const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}
function stars(diff) { const n = { easy: 2, medium: 3, hard: 5 }[diff] || 3; return '★'.repeat(n) + '☆'.repeat(5 - n); }
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

/** Probe /health on each candidate. The AI counts as usable only when the model is ready AND, on a
 *  phone, this device is paired (the backend reports `authorized` for the token we send). */
async function checkConnection() {
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
      return [paired ? 'Not paired · access token rejected' : 'Not paired', 'off',
        paired ? '🔗 <strong>The access token was rejected</strong> (it may have been replaced or turned off). Pair again from your computer.'
          : '🔗 <strong>Not paired.</strong> Open the pairing link from your computer, or enter the access token in Settings.'];
    }
    if (!h.ollama) return ['Computer connected · Ollama not running', 'warn',
      '⚠️ <strong>Your computer is connected, but Ollama isn\'t running there.</strong> Start Ollama on the computer.'];
    if (!h.llm_ready) return ['Computer connected · AI model not installed', 'warn',
      '⚠️ <strong>Your computer is connected, but its AI model isn\'t installed.</strong> Finish setup on the computer.'];
    return ['AI ready · runs on your computer', 'on', ''];
  }
  if (on) return [h.internet ? 'Online · AI ready' : 'Local AI ready · no internet', 'on', ''];
  return [h.ollama ? 'Backend up · model not installed' : 'Backend up · Ollama offline', 'off',
    '📴 <strong>Local AI unavailable</strong>: showing saved content. Open Settings → System & data → Models & setup.'];
}

function setOnline(on, health) {
  const was = state.online;
  state.online = on;
  state.health = health || null;
  const [text, dot, banner] = connectionStatus(on, health || null);
  $('#statusDot').className = 'dot ' + dot;
  $('#statusText').textContent = text;
  $('#offlineText').innerHTML = banner;
  $('#offlineBanner').classList.toggle('hidden', on);
  if (health && health.knowledge_chunks != null) $('#kbInfo').textContent = `📚 ${health.knowledge_chunks} chunks`;
  if (on && !was) { flushQueue(); refreshProgress(); }
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
    </article>`).join('') || '<p class="empty">No news right now.</p>';
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

function visibleQuestions() {
  const pool = state.questions.length ? state.questions : window.MOCK.questions;
  return state.category === 'all' ? pool : pool.filter((q) => q.category === state.category);
}

function renderQuestions() {
  const qs = visibleQuestions();
  $('#questionList').innerHTML = qs.length ? qs.map((q, i) => `
    <article class="card">
      <div class="q-head">
        <span class="q-cat">${esc((CATEGORIES.find(([k]) => k === q.category) || [0, q.category])[1]).toUpperCase()}</span>
        <span class="stars" title="${esc(q.difficulty)}">${stars(q.difficulty)}</span>
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

/* ---------- 6. MCQ QUIZ ---------- */
async function startQuiz() {
  if (state.busy.mcq) return;
  state.busy.mcq = true;
  $('#mcqBox').innerHTML = skeleton(1) + '<p class="empty">Generating a fresh quiz with AI…</p>';
  let items = null;
  if (state.online) {
    try {
      const data = await api('/generate-mcq', {
        method: 'POST', timeout: 240000,
        body: { category: state.mcqCategory, count: 5, user_id: state.userId },
      });
      items = data.mcqs;
      store.setJson(LS.mcqs, items);
    } catch (e) { toast(`Using saved quiz (${e.message})`); }
  }
  if (!items) {
    const saved = store.json(LS.mcqs, []);
    items = saved.length ? saved : window.MOCK.mcqs;
  }
  state.quiz = { items, index: 0, answers: {}, selected: null };
  state.busy.mcq = false;
  renderMCQ();
}

function renderMCQ() {
  const { items, index, answers } = state.quiz;
  const box = $('#mcqBox');
  if (!items.length) { box.innerHTML = '<p class="empty">No quiz available.</p>'; return; }

  if (index >= items.length) {                                   // finished screen
    const right = items.filter((m, i) => answers[i] === m.correctAnswer).length;
    box.innerHTML = `
      <div class="card done">
        <div class="big">${right}/${items.length}</div>
        <p>${right === items.length ? `Perfect score, ${esc(state.userName)}! 🎉` : `Nice work, ${esc(state.userName)}! Keep going 💪`}</p>
        <div class="mcq-controls"><button class="btn btn-primary" data-action="new-quiz">✨ New quiz</button></div>
      </div>`;
    return;
  }

  const m = items[index], answered = index in answers, sel = answered ? answers[index] : state.quiz.selected;
  box.innerHTML = `
    <div class="card">
      <div class="mcq-stats"><span>Question ${index + 1} of ${items.length}</span><span>Score: ${state.progress.mcq_correct}/${state.progress.mcq_total}</span></div>
      <div class="bar"><span style="width:${((index + (answered ? 1 : 0)) / items.length) * 100}%"></span></div>
      <p class="mcq-q">${esc(m.question)}</p>
      <div class="options">
        ${m.options.map((o, i) => {
          let cls = 'option';
          if (answered) cls += i === m.correctAnswer ? ' correct' : i === sel ? ' wrong' : '';
          else if (i === sel) cls += ' selected';
          return `<button class="${cls}" data-action="pick" data-i="${i}" ${answered ? 'disabled' : ''}>
                    <span class="letter">${'ABCD'[i]}</span><span>${esc(o)}</span></button>`;
        }).join('')}
      </div>
      ${answered ? `<div class="feedback ${sel === m.correctAnswer ? 'ok' : 'bad'}">
          <strong>${sel === m.correctAnswer ? '✅ Correct!' : '❌ Not quite'}</strong>${esc(m.explanation)}</div>` : ''}
      <div class="mcq-controls">
        <button class="btn btn-ghost" data-action="prev" ${index === 0 ? 'disabled' : ''}>← Prev</button>
        ${answered
          ? `<button class="btn btn-primary" data-action="next">${index === items.length - 1 ? 'Finish' : 'Next →'}</button>`
          : `<button class="btn btn-primary" data-action="submit" ${sel == null ? 'disabled' : ''}>Submit</button>`}
      </div>
    </div>`;
}

function submitMCQ() {
  const q = state.quiz, m = q.items[q.index];
  if (q.selected == null || q.index in q.answers) return;       // count each question once
  q.answers[q.index] = q.selected;
  const correct = q.selected === m.correctAnswer;
  recordProgress({ kind: 'mcq', category: m.category, correct, item: m.question });
  renderMCQ();
}

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

async function sendChat(text, { speakAnswer = false } = {}) {
  text = text.trim();
  if (!text || state.busy.chat) return;
  switchTab('chat');
  addMsg('user', esc(text));
  if (!state.online) {
    addMsg('bot', '📴 I\'m offline right now. Chat needs the AI backend. News, interview questions and quizzes still work from saved content.');
    return;
  }
  state.busy.chat = true;
  const typing = addMsg('bot', '<span class="typing"><span></span><span></span><span></span></span>');
  try {
    const data = await api('/chat', { method: 'POST', timeout: 240000, body: { message: text, thread_id: state.threadId } });
    state.threadId = data.thread_id; store.set(LS.thread, data.thread_id);
    typing.querySelector('.bubble').innerHTML = botHtml(data.answer, data.tools_used, data.sources);
    if (speakAnswer) speak(data.answer);
  } catch (e) {
    typing.querySelector('.bubble').innerHTML = `⚠️ ${esc(e.message === 'offline' ? 'Lost connection to the backend.' : e.message)}`;
  } finally {
    state.busy.chat = false;
    $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
  }
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

/* ---------- 8. VOICE ---------- */
const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const voice = { rec: null, mediaRec: null, chunks: [], listening: false, mode: 'command' };

function speak(text) {
  if (!('speechSynthesis' in window)) return;
  const plain = text.replace(/```[\s\S]*?```/g, ' code example ').replace(/[*_`#>\[\]()]/g, '').replace(/https?:\/\/\S+/g, '').slice(0, 600);
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(plain); u.lang = 'en-US'; u.rate = 1;
  speechSynthesis.speak(u);
}

/** mode: 'command' (floating button) or 'dictate' (chat mic) */
async function startListening(mode) {
  if (voice.listening) { stopListening(); return; }
  voice.mode = mode;
  const useWhisper = state.usePrivateWhisper || !SR;
  if (useWhisper && !state.online) { toast(SR ? 'Private voice mode needs the backend' : 'Voice needs the backend in this browser'); return; }
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
  $('#testResult').textContent = '';
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) && !navigator.standalone;
  const android = /android/i.test(navigator.userAgent);
  $('#installHint').textContent = ios ? '📱 To install on iPhone/iPad: open this page in Safari, tap Share, then "Add to Home Screen".'
    : android ? '📱 To install on Android: in Chrome tap ⋮ then "Install app" (needs HTTPS, see the mobile guide).' : '';
  $('#settingsSheet').classList.remove('hidden');
}
function closeSettings() { $('#settingsSheet').classList.add('hidden'); }
async function saveSettings() {
  state.userName = $('#setName').value.trim() || 'there';
  store.set(LS.name, state.userName);
  if (state.apiBase !== null) api('/preferences', { method: 'PUT', body: { user_name: state.userName } }).catch(() => {});
  store.set(LS.api, $('#setApi').value.trim());
  store.set(LS.token, $('#setToken').value.trim());
  state.usePrivateWhisper = $('#setWhisper').checked;
  store.set(LS.whisper, state.usePrivateWhisper ? '1' : '0');
  $('#userName').textContent = state.userName;
  closeSettings();
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
let current = 'news';
const loaded = { news: false, interview: false, mcq: false, chat: false };

function switchTab(name) {
  current = name;
  $$('.tab').forEach((b) => { const on = b.dataset.tab === name; b.classList.toggle('active', on); b.setAttribute('aria-selected', on); });
  $$('.panel').forEach((p) => p.classList.toggle('active', p.id === `tab-${name}`));
  document.body.classList.toggle('on-chat', name === 'chat');
  $('#content').scrollTop = 0;
  if (name === 'interview' && !loaded.interview) {
    loaded.interview = true; renderQuestions();
    if (!state.questions.length && state.online) generateQuestions();
  }
  if (name === 'mcq' && !loaded.mcq) { loaded.mcq = true; startQuiz(); }
  if (name === 'chat') {
    if (!loaded.chat) { loaded.chat = true; loadChatHistory(); }
    setTimeout(() => $('#chatText').focus({ preventScroll: true }), 50);
  }
}

function bindEvents() {
  $$('.tab').forEach((b) => b.addEventListener('click', () => switchTab(b.dataset.tab)));
  $('#newsRefresh').addEventListener('click', () => loadNews(true));
  $('#genQuestions').addEventListener('click', generateQuestions);
  renderCategoryChips('#categoryChips', state.category, (c) => { state.category = c; renderQuestions(); });
  renderCategoryChips('#mcqChips', state.mcqCategory, (c) => { state.mcqCategory = c; startQuiz(); });

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
    } else if (a === 'pick') { state.quiz.selected = +el.dataset.i; renderMCQ(); }
    else if (a === 'submit') submitMCQ();
    else if (a === 'next') { state.quiz.index++; state.quiz.selected = null; renderMCQ(); }
    else if (a === 'prev') { state.quiz.index = Math.max(0, state.quiz.index - 1); state.quiz.selected = null; renderMCQ(); }
    else if (a === 'new-quiz') startQuiz();
  });

  // Chat
  const ta = $('#chatText');
  $('#chatForm').addEventListener('submit', (e) => { e.preventDefault(); const v = ta.value; ta.value = ''; ta.style.height = ''; sendChat(v); });
  ta.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#chatForm').requestSubmit(); } });
  ta.addEventListener('input', () => { ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 140) + 'px'; });
  $('#kbFile').addEventListener('change', (e) => { uploadKnowledge(e.target.files[0]); e.target.value = ''; });

  // Voice
  $('#voiceFab').addEventListener('click', () => startListening('command'));
  $('#chatMic').addEventListener('click', () => startListening('dictate'));
  $('#voiceStop').addEventListener('click', stopListening);

  // Settings / install
  $('#settingsBtn').addEventListener('click', openSettings);
  $('#closeSettings').addEventListener('click', closeSettings);
  $('#saveSettings').addEventListener('click', saveSettings);
  $('#testApi').addEventListener('click', testApi);
  $('#settingsSheet').addEventListener('click', (e) => { if (e.target.id === 'settingsSheet') closeSettings(); });
  $('#resetProgress').addEventListener('click', () => {
    if (!confirm('Reset your local score and saved quiz progress on this device?')) return;
    state.progress = { mcq_total: 0, mcq_correct: 0, interview_viewed: 0, streak_days: 0 };
    store.setJson(LS.progress, state.progress); updateProgressPill(); toast('Local progress reset');
  });
  $('#installBtn').addEventListener('click', installApp);
  bindV2Events();

  // Connectivity changes
  window.addEventListener('hashchange', () => { if (readPairingLink()) checkConnection(); });
  window.addEventListener('storage', (e) => { if (e.key === LS.token || e.key === LS.api) checkConnection(); });   // paired in another window
  window.addEventListener('online', checkConnection);
  window.addEventListener('offline', () => setOnline(false));
  document.addEventListener('visibilitychange', () => { if (!document.hidden) checkConnection(); });
}

function tickClock() { $('#clock').textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }

async function boot() {
  $('#userName').textContent = state.userName;
  updateProgressPill();
  bindEvents();
  tickClock(); setInterval(tickClock, 15000);

  // Service worker (needs http/https; skipped when opened as a local file)
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
    navigator.serviceWorker.register('sw.js').catch((e) => console.warn('SW registration failed', e));
  }

  readPairingLink();
  state.ready = checkConnection();
  await state.ready;
  setInterval(checkConnection, 30000);
  loadNews();
  loadPreferences();
  // Mode A vs Mode B: is the AI running on THIS device or on a paired computer? (/health says so;
  // /companion is the fallback for an older backend)
  if (state.health && typeof state.health.remote !== 'boolean') {
    try { state.remote = !(await api('/companion')).this_device_is_host; } catch { /* keep the guess */ }
    setOnline(state.online, state.health);
  }
  if (!state.remote && state.health && state.health.setup_completed === false) openSetup();   // first run on this computer

  // Deep links from manifest shortcuts, e.g. ./?tab=mcq
  const tab = new URLSearchParams(location.search).get('tab');
  if (['news', 'interview', 'mcq', 'chat'].includes(tab)) switchTab(tab);
}

/* ---------- 11. ABHIFY.AI v2: CHAT HISTORY, SETUP, MEMORY, SYSTEM ---------- */
const TOOL_TAGS = {
  web_search: '<span class="tag">🌐 Web search</span>',
  knowledge_base_search: '<span class="tag kb">📚 Your documents</span>',
  search_memory: '<span class="tag kb">🧠 Your memory</span>',
  save_memory: '<span class="tag kb">🧠 Memory saved</span>',
};

/** Format an assistant answer with tool badges and sources (used live and for history). */
function botHtml(answer, tools = [], sources = []) {
  const tags = tools.map((t) => TOOL_TAGS[t] || '').join('');
  const src = sources.length ? `<div class="sources">Sources: ${sources.map((s) => {
    if (!/^https?:/.test(s)) return esc(s);
    try { return `<a href="${esc(s)}" target="_blank" rel="noopener">${esc(new URL(s).hostname)}</a>`; } catch { return esc(s); }
  }).join(' · ')}</div>` : '';
  return md(answer) + (tags ? `<div class="meta-tags">${tags}</div>` : '') + src;
}

async function loadChatHistory() {
  await state.ready;                       // the tab can open before the first connection check finishes
  if (!state.threadId || state.apiBase === null) return;
  try {
    const { messages } = await api(`/conversations/${encodeURIComponent(state.threadId)}?limit=50`);
    for (const m of messages) {
      addMsg(m.role === 'user' ? 'user' : 'bot', m.role === 'user' ? esc(m.content) : botHtml(m.content, m.tools, m.sources));
    }
  } catch { /* offline: keep the welcome message only */ }
}

function newChat() {
  state.threadId = null; store.set(LS.thread, '');
  $('#chatLog').querySelectorAll('.msg').forEach((m, i) => { if (i > 0) m.remove(); });   // keep the welcome bubble
  toast('New conversation started');
}

async function loadPreferences() {
  if (state.apiBase === null) return;
  try {
    const p = await api('/preferences');
    if (p.user_name && !store.get(LS.name)) { state.userName = p.user_name; $('#userName').textContent = p.user_name; }
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
const setup = { diag: null, profiles: null, polling: null, pullStatus: {}, testMsg: '' };

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
    } else if (a === 'test') {
      $('#setupTest').textContent = ' Testing… (the first run loads the model and can take a minute)';
      const r = await api('/setup/test', { method: 'POST', timeout: 200000 });
      setup.testMsg = r.ok ? ` ✅ Working: answered in ${r.seconds}s` : ` ❌ ${esc(r.error)}`;
      $('#setupTest').innerHTML = setup.testMsg;
    }
  } catch (e) { toast(adminError(e)); }
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
  closeSheet('settingsSheet'); openSheet('memorySheet');
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
  closeSheet('settingsSheet'); openSheet('systemSheet');
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
  } catch (e) { $('#systemInfo').innerHTML = `<p class="empty">Needs the backend (${esc(e.message)}).</p>`; }
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
  closeSheet('settingsSheet'); openSheet('knowledgeSheet');
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
async function renderCompanion() {
  const box = $('#companionBox');
  try {
    const st = await api('/companion');
    if (!st.this_device_is_host) {
      box.innerHTML = `<p class="small">This device is connected to Abhify.AI on another computer${st.authorized ? ' ✅' : ' (token missing or wrong)'}. Companion settings can only be changed on that computer.</p>`;
      return;
    }
    box.innerHTML = st.enabled
      ? `<p class="small">✅ On${st.listening_on_lan ? ', listening on your network' : '. Restart Abhify.AI so it listens on your network'}.
           ${st.lan_addresses.length ? `Addresses: ${st.lan_addresses.map((ip) => `<code>http://${esc(ip)}:8000</code>`).join(', ')}` : ''}</p>
         <div class="row"><button class="btn btn-ghost btn-sm" data-c="new">New token</button><button class="btn btn-ghost btn-sm danger" data-c="off">Turn off</button></div>`
      : `<div class="row"><button class="btn btn-ghost btn-sm" data-c="on">Turn on companion access</button></div>`;
  } catch (e) { box.innerHTML = `<p class="muted small">${esc(e.message)}</p>`; }
}

async function companionAction(a) {
  try {
    if (a === 'off') { await api('/companion/disable', { method: 'POST' }); return renderCompanion(); }
    if (a === 'on' && !confirm('Turn on companion access?\n\nYour phone will be able to use Abhify.AI through this computer with an access token. Admin actions (backups, moving data, model downloads) stay limited to this computer. Never forward this port to the internet; use your home network or a private tunnel such as Tailscale.')) return;
    const r = await api('/companion/enable', { method: 'POST' });
    const link = `${r.lan_urls[0] || 'http://<this-computer>:8000'}/#pair=${r.token}`;
    $('#companionBox').innerHTML = `
      <div class="step small">
        <b>Your access token (shown once):</b><br><code class="break">${esc(r.token)}</code><br><br>
        On your phone, open <code class="break">${esc(link)}</code> (this pairs it automatically),
        or enter the address and token in Settings.<br><br>${esc(r.next_steps)}
      </div>
      <div class="row"><button class="btn btn-ghost btn-sm" data-c="done">I've saved it</button></div>`;
  } catch (e) { toast(adminError(e)); }
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
    const r = await api('/system/update-check');
    $('#updateResult').innerHTML = r.error ? esc(r.error)
      : !r.configured ? `v${esc(r.current)} · no update source configured`
      : r.update_available ? `🎉 v${esc(r.latest)} available. <a href="${esc(r.download_url)}" target="_blank" rel="noopener">Download</a> · SHA-256 <code class="break">${esc(r.sha256)}</code>`
      : `✅ Up to date (v${esc(r.current)})`;
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
    if (el.dataset.c === 'done') renderCompanion(); else companionAction(el.dataset.c === 'new' ? 'on' : el.dataset.c);
  });
  $('#openSystem').addEventListener('click', renderCompanion);
  $('#checkUpdates').addEventListener('click', checkUpdates);
}

document.addEventListener('DOMContentLoaded', boot);
