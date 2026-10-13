/* =====================================================================
   AI Tech Pulse: frontend logic (vanilla JS, no build step)

   Sections
     1. Config & state           6. MCQ quiz
     2. Utilities                7. Chat + knowledge base upload
     3. API client + offline     8. Voice (Web Speech API / Whisper fallback)
     4. News                     9. Progress, settings, PWA install
     5. Interview questions     10. Boot
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
  api: 'aitp_api', name: 'aitp_name', user: 'aitp_user', whisper: 'aitp_whisper',
  news: 'aitp_cache_news', questions: 'aitp_cache_questions', mcqs: 'aitp_cache_mcqs',
  progress: 'aitp_progress', queue: 'aitp_queue', thread: 'aitp_thread',
};

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

async function api(path, { method = 'GET', body, form, timeout = 120000 } = {}) {
  if (state.apiBase === null) throw new Error('offline');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(state.apiBase + path, {
      method,
      signal: ctrl.signal,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: form || (body ? JSON.stringify(body) : undefined),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.detail || `HTTP ${res.status}`);
    return data;
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('The request timed out');
    if (e instanceof TypeError) { setOnline(false); throw new Error('offline'); }  // network failure
    throw e;
  } finally { clearTimeout(timer); }
}

/** Probe /health on each candidate; first one with a reachable LLM wins. */
async function checkConnection() {
  for (const base of apiCandidates()) {
    try {
      const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), 4000);
      const res = await fetch(base + '/health', { signal: ctrl.signal, cache: 'no-store' });
      clearTimeout(t);
      if (!res.ok) continue;
      const h = await res.json();
      state.apiBase = base;
      setOnline(Boolean(h.ollama && h.llm_ready), h);
      return state.online;
    } catch { /* try next candidate */ }
  }
  state.apiBase = state.apiBase ?? null;
  setOnline(false);
  return false;
}

function setOnline(on, health) {
  const was = state.online;
  state.online = on;
  $('#statusDot').className = 'dot ' + (on ? 'on' : 'off');
  $('#statusText').textContent = on ? 'Online · AI ready'
    : health ? 'Backend up · Ollama offline' : 'Offline';
  $('#offlineBanner').classList.toggle('hidden', on);
  if (health) $('#kbInfo').textContent = `Knowledge base: ${health.knowledge_chunks} chunks`;
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
    const tags = data.tools_used.map((t) => t === 'web_search'
      ? '<span class="tag">🌐 Web search</span>' : '<span class="tag kb">📚 Your notes</span>').join('');
    const src = data.sources.length ? `<div class="sources">Sources: ${data.sources.map((s) =>
      /^https?:/.test(s) ? `<a href="${esc(s)}" target="_blank" rel="noopener">${esc(new URL(s).hostname)}</a>` : esc(s)).join(' · ')}</div>` : '';
    typing.querySelector('.bubble').innerHTML = md(data.answer) + (tags ? `<div class="meta-tags">${tags}</div>` : '') + src;
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
    $('#kbInfo').textContent = `Knowledge base: ${r.total_chunks} chunks`;
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
  $('#setWhisper').checked = state.usePrivateWhisper;
  $('#testResult').textContent = '';
  const ios = /iphone|ipad|ipod/i.test(navigator.userAgent) && !navigator.standalone;
  $('#installHint').textContent = ios ? '📱 To install on iPhone/iPad: tap Share, then "Add to Home Screen".' : '';
  $('#settingsSheet').classList.remove('hidden');
}
function closeSettings() { $('#settingsSheet').classList.add('hidden'); }
async function saveSettings() {
  state.userName = $('#setName').value.trim() || 'there';
  store.set(LS.name, state.userName);
  store.set(LS.api, $('#setApi').value.trim());
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
    const h = await (await fetch(url + '/health', { cache: 'no-store' })).json();
    $('#testResult').textContent = h.ollama ? `✅ OK · ${h.llm_model}${h.llm_ready ? '' : ' (model not pulled)'}` : '⚠️ Backend up, Ollama not reachable';
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
const loaded = { news: false, interview: false, mcq: false };

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
  if (name === 'chat') setTimeout(() => $('#chatText').focus({ preventScroll: true }), 50);
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

  // Connectivity changes
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

  await checkConnection();
  setInterval(checkConnection, 30000);
  loadNews();

  // Deep links from manifest shortcuts, e.g. ./?tab=mcq
  const tab = new URLSearchParams(location.search).get('tab');
  if (['news', 'interview', 'mcq', 'chat'].includes(tab)) switchTab(tab);
}

document.addEventListener('DOMContentLoaded', boot);
