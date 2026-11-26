/* =====================================================================
   study-ui.js: the Study tab (7.2): Today, Courses (personal learning journey per course), Interview plan,
   STAR stories and Discover. A classic script loaded after app.js; it uses app.js helpers ($, esc, md, api, toast…).

   Where things run:
   - Windows desktop (connected to this computer's Abhify.AI): the /learn/* routes; data in <data>/data/study.json;
     the AI is the chat's chosen model (local by default).
   - Web app, on-device mode or a paired phone: study-local.js answers the same routes in this browser; the AI is the
     on-device model (downloaded only after the user confirms). Nothing is sent anywhere.
   Every number shown comes from recorded progress; nothing is estimated or invented.
   ===================================================================== */
(() => {
  'use strict';
  const S = { view: 'today', cid: null, tab: 'roadmap', dash: null, detail: null, lesson: null, tracks: null, prefsOpts: null,
    practice: null, timer: null, pending: 0, cards: null, revision: null };
  let localMod = null, local = null;

  const remote = () => PLATFORM !== 'web' && state.engine !== 'device' && state.apiBase !== null && !state.remote;
  const model = () => (remote() && state.chatModel ? { model: state.chatModel } : {});

  async function studyAI(prompt, { json = false, maxTokens = 900 } = {}) {
    const d = state.device;
    if (!d.support) await checkDevice();
    if (!d.support?.ok) throw new Error(d.support?.reason || "This device can't run the AI itself.");
    if (!d.ready && !(await setupDevice())) throw new Error(d.error || 'The on-device AI isn\'t set up yet. Set it up (a one-time download) and try again.');
    const od = await onDevice();
    return od.chat([{ role: 'system', content: 'You are a patient, accurate tutor helping someone prepare for job interviews.' },
      { role: 'user', content: prompt }], { json, maxTokens });
  }

  /** /learn/* on this computer, or the same routes answered in this browser. */
  async function sApi(path, { method = 'GET', body } = {}) {
    if (remote()) return api(path, { method, body, timeout: method === 'GET' ? 30000 : 600000 });   // local models can be slow
    localMod ??= await import('./study-local.js');
    local ??= localMod.createLocal({ storage: window.localStorage, ai: studyAI,
      memories: async () => { try { return await (await onDevice()).local.memories(); } catch { return []; } },   // only read when a course opts in
      loadJson: async (f) => { const r = await fetch(`./${f}`); if (!r.ok) throw new Error(`Couldn't load ${f}`); return r.json(); } });
    try { return await local.handle(method, path, body || {}); } catch (e) { throw new Error(e.message || String(e)); }
  }

  /* ---------- small helpers ---------- */
  const pct = (n) => `${Math.max(0, Math.min(100, Math.round(n || 0)))}%`;
  const bar = (value, label) => `<div class="s-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${Math.round(value || 0)}" aria-label="${esc(label)}"><span style="width:${pct(value)}"></span></div>`;
  const dur = (sec) => { const m = Math.round((sec || 0) / 60); return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`; };
  const tile = (label, value, sub = '') => `<div class="s-tile"><span class="s-tile-v">${esc(value)}</span><span class="s-tile-l">${esc(label)}</span>${sub ? `<span class="s-tile-s">${esc(sub)}</span>` : ''}</div>`;
  const busyHtml = (text) => `<div class="s-busy" role="status"><span class="typing"><span></span><span></span><span></span></span> ${esc(text)}</div>`;
  const errHtml = (title, e, retry) => `${stateHtml('error', title, e.message || String(e), true)}${retry ? `<button type="button" class="btn btn-sm" data-s="${retry}">Try again</button>` : ''}`;
  const ring = (v, label) => `<div class="s-ring" style="--v:${Math.max(0, Math.min(100, v || 0))}" role="img" aria-label="${esc(label)}: ${Math.round(v || 0)}%"><b>${Math.round(v || 0)}%</b></div>`;
  const iso = () => { const d = new Date(), p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; };
  async function tracks() { return (S.tracks ??= await sApi('/learn/tracks')); }

  /* ---------- navigation ---------- */
  function show(view) {
    if (view !== 'mock') stopMockTimer();
    if (view !== 'lesson' && !(view === 'course' && S.tab === 'practice')) stopTimer();
    S.view = view;
    document.querySelectorAll('#tab-study .study-view').forEach((v) => v.classList.toggle('hidden', v.id !== `sv-${view}`));
    const nav = { course: 'courses', lesson: 'courses', report: 'courses' }[view] || view;
    document.querySelectorAll('#tab-study [data-sv]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.sv === nav)));
    const where = $('#studyWhere');
    if (where) where.textContent = remote() ? 'Saved on this computer · the AI is a local model on this computer; nothing is sent online.'
      : 'Saved in this browser on this device · the AI runs on this device; nothing is sent anywhere.';
    const c = $('#content'); if (c) c.scrollTop = 0;
  }
  function go(view) {
    show(view);
    if (view === 'today') renderToday();
    if (view === 'courses') renderCourses();
    if (view === 'plan') renderPlan();
    if (view === 'stories') renderStories();
    if (view === 'mock') renderMock();
    if (view === 'resume') renderResume();
    if (view === 'discover') { setTimeout(() => $('#discoverQ')?.focus({ preventScroll: true }), 50); if (remote()) renderSkills(); }
  }
  window.studyShow = () => go(S.view === 'lesson' || S.view === 'course' ? S.view : S.view || 'today');

  /* ---------- Today: readiness, streak, revision, today's plan ---------- */
  async function renderToday() {
    const el = $('#sv-today');
    el.innerHTML = skeleton(3);
    let st, due;
    try { [st, due] = await Promise.all([sApi('/learn/state'), sApi('/learn/cards')]); } catch (e) { el.innerHTML = errHtml("Couldn't open Study", e, 'today'); return; }
    const cardsDue = due.cards.length, qDue = st.reviews_due;
    const r = st.readiness;
    const started = st.courses.length || st.plan || st.stories;
    el.innerHTML = `
      <div class="card s-hero">
        ${ring(r.overall, 'Interview readiness')}
        <div class="s-hero-text">
          <h3>${st.plan ? 'Interview readiness' : 'Your readiness'}: ${esc(r.level)}</h3>
          <p class="muted small">${st.plan ? `For: ${esc(st.plan.jd_title || 'your job description')}` : 'From your course practice. Make an interview plan to score it against a job.'}
            ${r.skills.length ? '' : ' Answer some practice questions to see your scores.'}</p>
          <div class="row-tight"><span class="s-pill">🔥 ${st.streak} day${st.streak === 1 ? '' : 's'} streak</span>
            <span class="s-pill">${cardsDue} flashcard${cardsDue === 1 ? '' : 's'} due</span><span class="s-pill">${qDue} missed question${qDue === 1 ? '' : 's'} to review</span></div>
        </div>
      </div>
      <div class="row-tight s-actions">
        <button type="button" class="btn btn-primary btn-sm" data-s="rev-daily" ${cardsDue + qDue ? '' : 'disabled'}>Daily revision${cardsDue + qDue ? ` (${cardsDue + qDue})` : ''}</button>
        <button type="button" class="btn btn-sm" data-s="rev-weekly">Weekly revision</button>
      </div>
      ${st.today.length ? `<div class="card"><div class="card-head"><span class="card-title">Today's plan</span><button type="button" class="chip" data-s="plan">Open plan</button></div>
        <ul class="s-items">${st.today.map((it) => planItemHtml(it, it.key)).join('')}</ul></div>` : ''}
      ${r.weak.length ? `<div class="card"><span class="card-title">Weak areas</span><p class="small">${r.weak.map((w) => `<button type="button" class="chip" data-s="study-skill" data-skill="${esc(w)}">${esc(w)}</button>`).join(' ')}</p></div>` : ''}
      ${st.courses.length ? `<div class="card"><span class="card-title">Continue learning</span>${st.courses.map((c) => `
        <button type="button" class="s-row" data-s="open-course" data-cid="${esc(c.id)}"><span>${esc(c.title)}</span><span class="muted small">${c.done}/${c.total} lessons</span>${bar(c.total ? 100 * c.done / c.total : 0, `${c.title} progress`)}</button>`).join('')}</div>` : ''}
      <div class="card" id="s-quality"></div>
      <div class="card s-data"><span class="card-title">Your study data</span>
        <p class="muted small">${remote() ? 'Saved on this computer and included in Abhify backups.' : 'Saved only in this browser. Export a copy now and then: clearing browser data removes it.'} Nothing is synced between devices.</p>
        <div class="row-tight"><button type="button" class="btn btn-sm" data-s="export">Export</button><button type="button" class="btn btn-sm" data-s="import">Import…</button>
        <input type="file" id="s-import-file" accept="application/json,.json" hidden></div></div>
      ${started ? '' : `<div class="card s-empty"><h3>Start preparing</h3><p class="muted">Pick a course to learn step by step, or paste a job description to get a day-by-day interview plan.</p>
        <div class="row-tight"><button type="button" class="btn btn-primary btn-sm" data-s="courses">Choose a course</button><button type="button" class="btn btn-sm" data-s="plan">Make an interview plan</button></div></div>`}`;
    renderQuality();
  }

  /* ---------- model quality check (dev.2): 3 fixed probes, cached per model, never blocks ---------- */
  const modelLabel = () => (remote() ? (state.chatModel || state.models?.def || '') : (state.device?.support?.modelId || 'on-device'));
  async function renderQuality() {
    const el = $('#s-quality'); if (!el) return;
    let r = null;
    try { r = (await sApi(`/learn/quality${remote() ? '' : `?model=${encodeURIComponent(modelLabel())}`}`)).result; } catch { /* shown as unchecked */ }
    const tone = { good: 's-ok', weak: 's-warn', poor: 's-bad' }[r?.verdict] || '';
    el.innerHTML = `<span class="card-title">AI model for Study</span>
      ${r ? `<p class="small ${tone}"><b>${esc(r.model)}: ${esc({ good: 'good', weak: 'weak', poor: 'poor' }[r.verdict])}</b> · ${esc(r.advice)}</p>
        <p class="muted small">Checked ${esc(new Date(r.at).toLocaleString())}: JSON questions ${r.probes.json_mcq ? '✓' : '✗'} · lesson sections ${r.probes.lesson_sections ? '✓' : '✗'} · a simple fact ${r.probes.fact ? '✓' : '✗'}</p>`
        : '<p class="muted small">Not checked yet. A quick check (3 short prompts) shows whether this model handles lessons and questions well.</p>'}
      <button type="button" class="btn btn-sm" data-s="quality">${r ? 'Check again' : 'Check model quality'}</button>`;
  }

  /* ---------- revision: flashcards + missed questions (spaced repetition) ---------- */
  async function openRevision(kind, cid = null) {
    const el = cid ? $('#s-course-body') : $('#sv-today');
    el.innerHTML = skeleton(2);
    let cards, due = [];
    try {
      cards = (await sApi(`/learn/cards?${new URLSearchParams({ ...(cid ? { course_id: cid } : {}), ...(kind === 'weekly' ? { weekly: 'true' } : {}) })}`)).cards;
      if (kind === 'daily' && !cid) due = (await sApi('/learn/review')).due;
    } catch (e) { el.innerHTML = errHtml("Couldn't load revision", e); return; }
    S.revision = { kind, cid, cards, due, i: 0, right: 0, el };
    renderRevisionStep();
  }
  function renderRevisionStep() {
    const R = S.revision, el = R.el;
    const items = [...R.cards.map((c) => ({ t: 'card', c })), ...R.due.map((q) => ({ t: 'q', q }))];
    const back = R.cid ? '' : '<button type="button" class="chip" data-s="today">← Today</button>';
    if (!items.length) {
      el.innerHTML = `${back}${stateHtml('empty', R.kind === 'weekly' ? 'Nothing to revise from this week' : 'Nothing due today',
        R.kind === 'weekly' ? 'Open lessons and make flashcards; they show up here for a week.' : 'Missed questions and flashcards come back here on the right day.')}`;
      return;
    }
    if (R.i >= items.length) {
      el.innerHTML = `${back}<div class="card s-empty"><h3>Revision done 🎉</h3><p>${R.right} of ${items.length} remembered. Items you missed come back tomorrow.</p></div>`;
      return;
    }
    const it = items[R.i];
    const head = `${back}<p class="muted small">${R.kind === 'weekly' ? 'Weekly' : 'Daily'} revision · ${R.i + 1} of ${items.length}</p>`;
    if (it.t === 'card') {
      el.innerHTML = `${head}<div class="s-flash" tabindex="0" role="button" aria-label="Flashcard: show the answer" data-s="flip">
          <div class="s-flash-front"><span class="muted small">${esc(it.c.title || '')}</span><p>${esc(it.c.front)}</p><span class="muted small">Tap to show the answer</span></div>
          <div class="s-flash-back hidden"><p>${esc(it.c.back)}</p></div></div>
        <div class="row-tight s-actions hidden" id="s-grade"><button type="button" class="btn btn-sm" data-s="grade" data-ok="0">Not yet</button>
          <button type="button" class="btn btn-primary btn-sm" data-s="grade" data-ok="1">I remembered</button></div>`;
    } else {
      const q = it.q.question || {};
      el.innerHTML = `${head}<div class="card"><p class="muted small">You missed this · ${esc(it.q.skill || '')}</p>
        <p class="q-text">${esc(q.q || '')}</p><div class="options">${(q.options || []).map((o, i) => `<button type="button" class="option" data-s="rev-pick" data-i="${i}"><span class="letter">${'ABCD'[i]}</span>${esc(o)}</button>`).join('')}</div>
        <div id="s-rev-why"></div></div>`;
    }
  }

  /* ---------- Courses: my courses, ready-made tracks, a custom topic ---------- */
  async function renderCourses() {
    const el = $('#sv-courses');
    el.innerHTML = skeleton(3);
    let st, t;
    try { [st, t] = await Promise.all([sApi('/learn/state'), tracks()]); } catch (e) { el.innerHTML = errHtml("Couldn't load courses", e, 'courses'); return; }
    const mine = new Set(st.courses.map((c) => c.track).filter(Boolean));
    el.innerHTML = `
      ${st.courses.length ? `<h3 class="set-h">My courses</h3><div class="s-grid">${st.courses.map((c) => `
        <button type="button" class="card card--interactive s-course" data-s="open-course" data-cid="${esc(c.id)}">
          <b>${esc(c.title)}</b><span class="muted small">${c.done} of ${c.total} lessons</span>${bar(c.total ? 100 * c.done / c.total : 0, `${c.title} progress`)}</button>`).join('')}</div>` : ''}
      <h3 class="set-h">${st.courses.length ? 'Add a course' : 'Choose a course'}</h3>
      <div class="s-grid">${t.tracks.filter((x) => !mine.has(x.id)).map((x) => `
        <button type="button" class="card card--interactive s-course" data-s="add-track" data-track="${esc(x.id)}">
          <b>${esc(x.title)}</b><span class="muted small">${esc(x.summary || '')}</span>
          <span class="muted small">${x.modules.length} modules · ${x.modules.reduce((n, m) => n + m.lessons.length, 0)} lessons</span></button>`).join('')}</div>
      <form class="card s-custom" id="s-custom-form"><label class="field"><span>Or any topic</span>
        <input class="input" id="s-custom-topic" maxlength="120" placeholder="e.g. Kafka, System design for fintech, Rust" required></label>
        <p class="muted small">The AI writes a course outline for this topic; check it covers what you need.</p>
        <button class="btn btn-primary btn-sm" type="submit">Create course</button></form>`;
  }

  /* ---------- one course: preferences, overview, dashboard, roadmap, practice, flashcards, capstone, progress ---------- */
  async function openCourse(cid, tab = null) {
    S.cid = cid; if (tab) S.tab = tab;
    show('course');
    const el = $('#sv-course');
    el.innerHTML = skeleton(3);
    try { [S.dash, S.detail] = await Promise.all([sApi(`/learn/courses/${encodeURIComponent(cid)}/dashboard`), sApi(`/learn/courses/${encodeURIComponent(cid)}`)]); } catch (e) { el.innerHTML = errHtml("Couldn't open the course", e); return; }
    if (!S.dash.prefs.chosen) { renderPrefs(); return; }
    renderCourse();
  }

  async function renderPrefs() {
    const el = $('#sv-course'), d = S.dash, p = d.prefs;
    S.prefsOpts ??= await sApi('/learn/prefs/options');
    const o = S.prefsOpts, customTarget = p.chosen && !o.targets.includes(p.target), customLevel = p.level_key === 'custom';
    el.innerHTML = `<button type="button" class="chip" data-s="courses">← Courses</button>
      <div class="card"><h3>${esc(d.title)}: your learning journey</h3>
      <p class="muted small">Tell Abhify what you're aiming for. Lessons, practice level and the plan follow your choice. You can change it any time; your progress is kept.</p>
      <fieldset class="s-pref"><legend>Your goal</legend><div class="s-pref-grid">
        ${o.targets.map((t) => `<label class="s-pref-card"><input type="radio" name="s-target" value="${esc(t)}" ${p.chosen && p.target === t ? 'checked' : ''}><span>${esc(t)}</span></label>`).join('')}
        <label class="s-pref-card"><input type="radio" name="s-target" value="__custom" ${customTarget ? 'checked' : ''}><span>Custom goal</span></label></div>
        <input class="input" id="s-target-custom" maxlength="80" placeholder="e.g. Robotics perception engineer" value="${customTarget ? esc(p.target) : ''}"></fieldset>
      <fieldset class="s-pref"><legend>Your level</legend><div class="s-pref-grid">
        ${Object.entries(o.levels).map(([k, v]) => `<label class="s-pref-card"><input type="radio" name="s-level" value="${esc(k)}" ${p.chosen && p.level_key === k ? 'checked' : ''}><span>${esc(v)}</span></label>`).join('')}
        <label class="s-pref-card"><input type="radio" name="s-level" value="__custom" ${customLevel ? 'checked' : ''}><span>Custom level</span></label></div>
        <input class="input" id="s-level-custom" maxlength="80" placeholder="e.g. Strong in Python, new to cloud" value="${customLevel ? esc(p.level) : ''}"></fieldset>
      <label class="field"><span>Time per day</span><select class="select" id="s-minutes">${[30, 45, 60, 90, 120].map((m) => `<option value="${m}" ${p.minutes === m ? 'selected' : ''}>${m} minutes</option>`).join('')}</select></label>
      <div class="row-tight"><button type="button" class="btn btn-primary btn-sm" data-s="save-prefs">${p.chosen ? 'Update my plan' : 'Build my learning plan'}</button>
        ${p.chosen ? '<button type="button" class="btn btn-sm" data-s="cancel-prefs">Cancel</button>' : ''}</div></div>`;
  }

  async function savePrefs() {
    const pick = (n) => document.querySelector(`input[name="${n}"]:checked`)?.value;
    let target = pick('s-target'), level = pick('s-level');
    if (target === '__custom') target = $('#s-target-custom').value.trim();
    if (level === '__custom') level = $('#s-level-custom').value.trim();
    if (!target || !level) { toast('Choose a goal and a level (or type your own).'); return; }
    const cid = S.cid;
    try { await sApi(`/learn/courses/${encodeURIComponent(cid)}/prefs`, { method: 'PUT', body: { target, level, minutes: +$('#s-minutes').value } }); } catch (e) { toast(e.message, 6000); return; }
    $('#sv-course').innerHTML = busyHtml('Building your learning plan from this course\'s syllabus…');
    try { await sApi(`/learn/courses/${encodeURIComponent(cid)}/overview`, { method: 'POST', body: model() }); } catch (e) { toast(`Your choices are saved. The overview couldn't be written now (${e.message}); try "Build overview" later.`, 8000); }
    openCourse(cid, 'roadmap');
  }

  function renderCourse() {
    const d = S.dash, el = $('#sv-course'), p = d.prefs, ov = d.overview;
    const tabs = [['roadmap', 'Roadmap'], ['practice', 'Practice'], ['cards', 'Flashcards'], ['capstone', 'Capstone'], ['progress', 'Progress']];
    el.innerHTML = `<button type="button" class="chip" data-s="courses">← Courses</button>
      <div class="card s-course-head">
        <div><h3>${esc(d.title)}</h3><p class="muted small">Goal: ${esc(p.target)} · ${esc(p.level)} · ${p.minutes} min a day
          <button type="button" class="link-btn" data-s="edit-prefs">Change</button></p></div>
        ${ring(d.progress, 'Course progress')}
      </div>
      <div class="s-tiles">${tile('Lessons', `${d.lessons_done}/${d.lessons_total}`)}${tile('Modules', `${d.modules_done}/${d.modules_total}`)}
        ${tile('Score', d.assessment.percent == null ? '–' : `${d.assessment.percent}%`, d.assessment.answered ? `${d.assessment.answered} answers` : 'no answers yet')}
        ${tile('Time studied', dur(d.seconds), `${dur(d.seconds_week)} this week`)}${tile('Exercises', String(d.exercises_done))}
        ${tile('Streak', `${d.streak} day${d.streak === 1 ? '' : 's'}`)}${tile('To revise', String(d.revision.cards_due + d.revision.questions_due), `${d.revision.wrong_this_week} wrong this week`)}
        ${d.capstone ? tile('Capstone', `${d.capstone.done}/${d.capstone.total}`) : ''}</div>
      <div class="row-tight s-actions">
        ${d.next_lesson ? `<button type="button" class="btn btn-primary btn-sm" data-s="open-lesson" data-lid="${esc(d.next_lesson.id)}">Next: ${esc(d.next_lesson.title)}</button>` : '<span class="s-pill">All lessons done 🎉</span>'}
        ${d.weak.length ? `<button type="button" class="btn btn-sm" data-s="practice-weak">Revise weak topic: ${esc(d.weak[0].title)}</button>` : ''}
        <button type="button" class="btn btn-sm" data-s="report">Progress report</button>
        <button type="button" class="btn btn-sm" data-s="brain" aria-pressed="${Boolean(S.detail.brain?.enabled)}">Personal context: ${S.detail.brain?.enabled ? 'on' : 'off'}</button>
      </div>
      <div id="s-brain"></div>
      ${d.plan_change ? `<div class="card s-change" role="status"><b>Updated for your new goal</b> (was: ${esc(d.plan_change.previous.target)})
        <p class="small">${d.plan_change.kept} finished lesson${d.plan_change.kept === 1 ? '' : 's'}, your scores and history are kept and won't be assigned again.
        ${d.plan_change.now_core.length ? `Now part of your path: ${d.plan_change.now_core.map(esc).join(', ')}.` : ''}
        ${d.plan_change.now_optional.length ? ` Now optional review: ${d.plan_change.now_optional.map(esc).join(', ')}.` : ''}</p></div>` : ''}
      ${ov ? `<details class="card s-ov" ${d.lessons_done ? '' : 'open'}><summary><b>Course overview</b></summary><p>${esc(ov.summary)}</p>
        ${ov.objectives.length ? `<b class="small">You will be able to</b><ul class="list small">${ov.objectives.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
        ${ov.prerequisites.length ? `<b class="small">Prerequisites</b><ul class="list small">${ov.prerequisites.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
        <b class="small">Completion criteria</b><ul class="list small">${ov.completion.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></details>`
        : `<div class="card"><p class="small">No course overview yet. <button type="button" class="btn btn-sm" data-s="build-ov">Build overview</button></p></div>`}
      <nav class="study-nav s-subnav" role="tablist" aria-label="Course sections">${tabs.map(([k, v]) => `<button type="button" class="chip" role="tab" data-s="ctab" data-tab="${k}" aria-selected="${S.tab === k}">${v}</button>`).join('')}</nav>
      <div id="s-course-body"></div>`;
    renderCourseTab();
  }

  function renderCourseTab() {
    stopPractice();
    if (S.tab !== 'practice') stopTimer();
    const body = $('#s-course-body');
    if (S.tab === 'roadmap') body.innerHTML = roadmapHtml();
    if (S.tab === 'practice') startPractice({});
    if (S.tab === 'cards') openRevision('daily', S.cid);
    if (S.tab === 'capstone') renderCapstone();
    if (S.tab === 'progress') body.innerHTML = progressHtml();
  }

  function roadmapHtml() {
    const d = S.dash, m = Object.fromEntries(d.mastery.map((x) => [x.module, x])), focus = d.overview?.focus || {};
    return `<ol class="s-road">${S.detail.modules.map((mod, i) => {
      const r = m[mod.id];
      return `<li class="card s-mod ${r.optional ? 's-optional' : ''}"><div class="s-mod-head"><span class="s-mod-n">${i + 1}</span><div><b>${esc(mod.title)}</b>
        ${r.optional ? '<span class="s-tag">optional review</span>' : ''}${r.weak ? '<span class="s-tag s-tag-bad">needs revision</span>' : ''}
        ${focus[mod.id] ? `<p class="muted small">${esc(focus[mod.id])}</p>` : ''}</div>
        <span class="muted small">${r.lessons_done}/${r.lessons_total} · ${esc(r.level)}</span></div>${bar(r.score, `${mod.title} mastery`)}
        <ul class="s-lessons">${mod.lessons.map((l) => `<li><button type="button" class="s-lesson ${l.done ? 'done' : ''}" data-s="open-lesson" data-lid="${esc(l.id)}">
          <span aria-hidden="true">${l.done ? '✓' : l.ready ? '●' : '○'}</span>${esc(l.title)}<span class="sr-only">${l.done ? ' (done)' : l.ready ? ' (written)' : ''}</span></button></li>`).join('')}</ul>
        <button type="button" class="chip" data-s="practice-mod" data-mid="${esc(mod.id)}">Practise this module</button></li>`;
    }).join('')}</ol>`;
  }

  function progressHtml() {
    const d = S.dash, sc = d.schedule, today = iso();
    const lessonTitle = Object.fromEntries(S.detail.modules.flatMap((m) => m.lessons.map((l) => [l.id, l.title])));
    const modTitle = Object.fromEntries(S.detail.modules.map((m) => [m.id, m.title]));
    return `<div class="card"><span class="card-title">Weekly milestones</span>${sc.weeks.length ? `<ol class="list small">${sc.weeks.map((w) => `<li><b>Week ${w.week}</b> (${esc(w.from)} → ${esc(w.to)}): ${w.lessons} lessons${w.modules_completed.length ? ` · finish ${w.modules_completed.map((x) => esc(modTitle[x])).join(', ')}` : ''}</li>`).join('')}</ol>` : '<p class="small">Every lesson is done.</p>'}
        <p class="muted small">${sc.per_day} lesson${sc.per_day === 1 ? '' : 's'} a day at ${d.prefs.minutes} min, a revision day every 7th day. It moves with you: done lessons drop out.</p></div>
      <div class="card"><span class="card-title">Next 7 days</span><ul class="list small">${sc.days.slice(0, 7).map((x) => `<li${x.date === today ? ' class="s-today"' : ''}><b>${esc(x.date)}</b>: ${x.kind === 'revision' ? 'Revision day (flashcards and missed questions)' : x.lessons.map((l) => esc(lessonTitle[l] || l)).join(', ')}</li>`).join('')}</ul></div>
      <div class="card"><span class="card-title">Weak topics</span>${d.weak.length ? `<ul class="list small">${d.weak.map((w) => `<li>${esc(w.title)} <button type="button" class="link-btn" data-s="practice-mod" data-mid="${esc(w.id)}">Practise</button></li>`).join('')}</ul>` : '<p class="small muted">None yet: a module is weak after 3+ answers under 60% right.</p>'}</div>
      <div class="card"><span class="card-title">Learning history</span>${d.history.length ? `<ul class="list small">${d.history.map((h) => `<li>${esc(new Date(h.at).toLocaleString())} · ${esc({ lesson: 'Read', done: 'Finished', exercise: 'Exercise done', cards: 'Flashcards made', capstone: 'Capstone planned', 'capstone step': 'Capstone step done', tutor: 'Asked the tutor about', prefs: 'Goal set' }[h.kind] || h.kind)}: ${esc(h.detail)}</li>`).join('')}</ul>` : '<p class="small muted">Nothing yet.</p>'}</div>`;
  }

  async function renderCapstone() {
    const body = $('#s-course-body');
    const cap = S.detail.capstone;
    if (!cap) {
      body.innerHTML = `<div class="card"><h3>Capstone project</h3><p class="small">One project that uses what this course teaches, sized for your goal and level, doable on a laptop for free.</p>
        <button type="button" class="btn btn-primary btn-sm" data-s="make-capstone">Plan my capstone</button></div>`;
      return;
    }
    body.innerHTML = `<div class="card"><h3>${esc(cap.title)}</h3><p>${esc(cap.brief)}</p>
      <ul class="s-items">${cap.milestones.map((m, i) => `<li><label class="field check inline"><input type="checkbox" data-s="cap-step" data-i="${i}" ${m.done ? 'checked' : ''}><span>${esc(m.title)}</span></label></li>`).join('')}</ul>
      ${cap.stretch?.length ? `<b class="small">Stretch goals</b><ul class="list small">${cap.stretch.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
      <button type="button" class="link-btn" data-s="make-capstone">Plan a different capstone</button></div>`;
  }

  /* ---------- a lesson ---------- */
  async function openLesson(lid, refresh = false) {
    const cid = S.cid;
    show('lesson');
    const el = $('#sv-lesson');
    const mod = S.detail.modules.find((m) => m.lessons.some((l) => l.id === lid));
    const l = mod.lessons.find((x) => x.id === lid);
    el.innerHTML = `<button type="button" class="chip" data-s="back-course">← ${esc(S.detail.title)}</button>
      ${busyHtml(l.ready && !refresh ? 'Opening the lesson…' : 'Writing your lesson for your goal and level… on a local model this can take a few minutes.')}`;
    let les;
    try { les = await sApi('/learn/lesson', { method: 'POST', body: { course_id: cid, lesson_id: lid, refresh, ...model() } }); } catch (e) { el.innerHTML = `<button type="button" class="chip" data-s="back-course">← ${esc(S.detail.title)}</button>${errHtml("Couldn't write the lesson", e)}<button type="button" class="btn btn-sm" data-s="open-lesson" data-lid="${esc(lid)}">Try again</button>`; return; }
    l.ready = true;
    S.lesson = { lid, mod, l };
    const all = S.detail.modules.flatMap((m) => m.lessons), idx = all.findIndex((x) => x.id === lid), next = all[idx + 1];
    const ex = (S.detail.exercises || []).includes(lid);
    el.innerHTML = `<button type="button" class="chip" data-s="back-course">← ${esc(S.detail.title)}</button>
      <article class="card s-lesson-body"><p class="muted small">${esc(mod.title)} · lesson ${mod.lessons.indexOf(l) + 1} of ${mod.lessons.length}</p><h3>${esc(l.title)}</h3>
        ${les.used?.length ? `<p class="s-used small">Used your personal context: ${les.used.map(esc).join(' · ')}</p>` : ''}
        <div class="md">${md(les.markdown)}</div>
        <p class="muted small">AI-written for your goal and level${les.cached ? ` (saved ${esc(new Date(les.at).toLocaleDateString())})` : ''}; not independently verified. Check anything important against official docs.</p></article>
      <div class="row-tight s-actions">
        <button type="button" class="btn ${l.done ? '' : 'btn-primary'} btn-sm" data-s="lesson-done" aria-pressed="${l.done}">${l.done ? '✓ Lesson done' : 'Mark lesson done'}</button>
        <button type="button" class="btn btn-sm" data-s="exercise-done" aria-pressed="${ex}">${ex ? '✓ Exercise done' : 'I did the exercise'}</button>
        <button type="button" class="btn btn-sm" data-s="make-cards">Make flashcards</button>
        <button type="button" class="btn btn-sm" data-s="practice-mod" data-mid="${esc(mod.id)}">Practise this module</button>
        <button type="button" class="btn btn-sm" data-s="regen">Rewrite lesson</button>
        ${next ? `<button type="button" class="btn btn-sm" data-s="open-lesson" data-lid="${esc(next.id)}">Next lesson →</button>` : ''}
      </div>
      <form class="card s-tutor" id="s-tutor-form"><label class="field"><span>Ask the AI tutor about this lesson</span>
        <textarea class="input" id="s-tutor-q" rows="2" maxlength="1500" placeholder="e.g. Why is this better than the alternative? Show me another example."></textarea></label>
        <p class="muted small">The tutor sees this lesson and your goal, nothing else from your chats or files.</p>
        <button class="btn btn-sm" type="submit">Ask</button><div id="s-tutor-out"></div></form>`;
    startTimer(cid);
    renderDiagrams($('#sv-lesson .s-lesson-body'));
  }

  /* ---------- Mermaid diagrams (vendored, loaded only when a lesson has one; strict security) ---------- */
  let mermaidLoad = null;
  function loadMermaid() {
    mermaidLoad ??= new Promise((ok, fail) => {
      const el = document.createElement('script');
      el.src = 'vendor/mermaid/mermaid-11.4.1.min.js';
      el.onload = () => { try { window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: document.documentElement.dataset.theme === 'light' ? 'default' : 'dark' }); ok(window.mermaid); } catch (e) { fail(e); } };
      el.onerror = () => fail(new Error('diagram library unavailable'));
      document.head.appendChild(el);
    });
    return mermaidLoad;
  }
  async function renderDiagrams(root) {
    const blocks = [...(root?.querySelectorAll('code.lang-mermaid') || [])];
    if (!blocks.length) return;
    let m; try { m = await loadMermaid(); } catch { return; }          // the text stays readable
    for (const [i, code] of blocks.entries()) {
      const box = code.closest('.code-block'); if (!box) continue;
      try {
        const { svg } = await m.render(`s-mmd-${Date.now()}-${i}`, code.textContent);
        const fig = document.createElement('figure');
        fig.className = 's-diagram'; fig.setAttribute('role', 'img'); fig.setAttribute('aria-label', 'Diagram (the text version follows)');
        fig.innerHTML = svg;
        box.before(fig);
        box.querySelector('.code-head span').textContent = 'diagram source';
      } catch { box.querySelector('.code-head span').textContent = 'diagram (couldn\'t be drawn; text shown)'; }
    }
  }

  /* ---------- Personal AI Brain opt-in for one course (off by default; only ticked items are used) ---------- */
  async function renderBrain() {
    const el = $('#s-brain');
    if (el.innerHTML) { el.innerHTML = ''; return; }
    el.innerHTML = skeleton(1);
    let src; try { src = await sApi('/learn/brain/sources'); } catch (e) { el.innerHTML = errHtml("Couldn't load your personal context", e); return; }
    const b = S.detail.brain || { enabled: false, memories: [], projects: [] };
    const box = (name, id, text, on) => `<label class="field check inline s-brain-item"><input type="checkbox" name="${name}" value="${esc(id)}" ${on ? 'checked' : ''}><span>${esc(text)}</span></label>`;
    const goals = src.goals.map((g) => box('s-bm', g.id, `Goal: ${g.text}`, b.memories.map(String).includes(String(g.id))));
    const mems = src.memories.map((g) => box('s-bm', g.id, g.text, b.memories.map(String).includes(String(g.id))));
    const projs = src.projects.map((x) => box('s-bp', x.id, `Project: ${x.name}`, b.projects.includes(x.id)));
    el.innerHTML = `<form class="card" id="s-brain-form"><h3>Personal context for this course</h3>
      <p class="muted small">Off by default. When on, lessons and the tutor for <b>this course only</b> may use the items you tick to pick better examples. Chats are never used. Each lesson shows what it used.</p>
      ${src.memory_enabled === false ? '<p class="small s-warn" role="status">Memory is off in Settings, so personal context can\'t be used in lessons. Your choices below are kept for when you turn it back on.</p>' : ''}
      <label class="field check inline"><input type="checkbox" id="s-brain-on" ${b.enabled ? 'checked' : ''}><span>Use my personal context in this course</span></label>
      ${goals.length + mems.length + projs.length ? `<div class="s-brain-list">${[...goals, ...projs, ...mems].join('')}</div>`
        : `<p class="small">Nothing to share yet. Add goals in ${remote() ? 'Memory & history' : 'chat ("remember that my goal is …")'}${remote() ? ' or create a project' : ''}.</p>`}
      <div class="row-tight"><button class="btn btn-primary btn-sm" type="submit">Save</button></div></form>`;
  }

  /* ---------- course progress report: a printable page from recorded numbers only ---------- */
  function openReport() {
    const d = S.dash;
    const rows = d.mastery.map((r) => `<tr><td>${esc(r.title)}${r.optional ? ' (optional)' : ''}</td><td>${r.lessons_done}/${r.lessons_total}</td><td>${r.answered ? `${Math.round(100 * r.correct / r.answered)}% of ${r.answered}` : '–'}</td><td>${esc(r.level)}</td></tr>`).join('');
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><title>${esc(d.title)}: progress report</title>
      <style>body{font:14px/1.5 system-ui,sans-serif;color:#111;margin:32px;max-width:760px}h1{font-size:22px;margin:0}h2{font-size:15px;margin:22px 0 6px}
      table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:6px 4px;text-align:left}.muted{color:#555}
      .tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.tile{border:1px solid #ddd;border-radius:8px;padding:8px}.tile b{display:block;font-size:18px}
      @media print{button{display:none}body{margin:12mm}}</style></head><body>
      <button onclick="print()">Print or save as PDF</button>
      <h1>${esc(d.title)}</h1><p class="muted">Goal: ${esc(d.prefs.target)} · ${esc(d.prefs.level)} · ${d.prefs.minutes} min a day · report made ${esc(new Date().toLocaleString())}</p>
      <div class="tiles"><div class="tile"><b>${d.progress}%</b>progress</div><div class="tile"><b>${d.lessons_done}/${d.lessons_total}</b>lessons</div>
      <div class="tile"><b>${d.assessment.percent == null ? '–' : `${d.assessment.percent}%`}</b>score (${d.assessment.answered} answers)</div><div class="tile"><b>${dur(d.seconds)}</b>studied</div>
      <div class="tile"><b>${d.exercises_done}</b>exercises</div><div class="tile"><b>${d.streak}</b>day streak</div><div class="tile"><b>${d.revision.cards_due + d.revision.questions_due}</b>to revise</div>
      <div class="tile"><b>${d.capstone ? `${d.capstone.done}/${d.capstone.total}` : '–'}</b>capstone steps</div></div>
      <h2>Modules</h2><table><tr><th>Module</th><th>Lessons</th><th>Practice</th><th>Level</th></tr>${rows}</table>
      <h2>Weak topics</h2><p>${d.weak.length ? d.weak.map((w) => esc(w.title)).join(', ') : 'None recorded.'}</p>
      ${d.capstone ? `<h2>Capstone</h2><p>${esc(d.capstone.title)}: ${d.capstone.done} of ${d.capstone.total} steps done.</p>` : ''}
      <h2>Recent activity</h2><ul>${d.history.slice(0, 10).map((h) => `<li>${esc(new Date(h.at).toLocaleDateString())}: ${esc(h.kind)}, ${esc(h.detail)}</li>`).join('') || '<li>None yet.</li>'}</ul>
      <p class="muted">Every number is recorded by Abhify.AI ${remote() ? 'on this computer' : 'in this browser'}; nothing is estimated. AI-written lesson content isn't included.</p></body></html>`;
    show('report');
    const body = html.slice(html.indexOf('<body>') + 6, html.indexOf('</body>')).replace('<button onclick="print()">Print or save as PDF</button>', '');
    $('#sv-report').innerHTML = `<div class="row-tight s-noprint"><button type="button" class="chip" data-s="back-course">← ${esc(d.title)}</button>
      <button type="button" class="btn btn-primary btn-sm" data-s="print-report">Print or save as PDF</button></div><article class="card s-report">${body}</article>`;
  }

  /* ---------- unlimited practice: the next batch loads as you scroll ---------- */
  function startPractice({ moduleId = null, weak = false }) {
    const body = $('#s-course-body');
    S.practice = { cid: S.cid, round: 0, moduleId, weak, busy: false, failed: false, asked: [], n: 0, right: 0, answered: 0, obs: null, empty: 0, ended: false };
    const mods = S.detail.modules;
    body.innerHTML = `<div class="card s-practice-head"><label class="field"><span>Practise</span><select class="select" id="s-prac-scope">
        <option value="">All modules (rotating)</option><option value="__weak" ${weak ? 'selected' : ''}>Weak topics first</option>
        ${mods.map((m) => `<option value="${esc(m.id)}" ${moduleId === m.id ? 'selected' : ''}>${esc(m.title)}</option>`).join('')}</select></label>
        <span class="s-pill" id="s-prac-score">0 answered</span></div>
      <p class="muted small">Questions keep coming as you scroll. Level follows your course goal. AI-generated: not independently verified.</p>
      <div id="s-prac-list"></div><div id="s-prac-more" class="s-more" aria-live="polite"></div>`;
    const sentinel = $('#s-prac-more');
    S.practice.obs = new IntersectionObserver((ents) => { if (ents.some((e) => e.isIntersecting)) morePractice(); }, { root: null, rootMargin: '400px' });
    S.practice.obs.observe(sentinel);
    startTimer(S.cid);
    morePractice();
  }
  function stopPractice() { if (S.practice?.obs) S.practice.obs.disconnect(); S.practice = null; }
  async function morePractice(force = false) {
    const P = S.practice;
    if (!P || P.busy || ((P.failed || P.ended) && !force)) return;
    P.busy = true; P.failed = false; P.ended = false;
    const more = $('#s-prac-more');
    more.innerHTML = busyHtml('Writing new questions…');
    try {
      const b = await sApi('/learn/mcq', { method: 'POST', body: { course_id: P.cid, round: P.round, level: 'auto', count: 5, avoid: P.asked.slice(-40),
        ...(P.moduleId ? { module_id: P.moduleId } : {}), ...(P.weak ? { weak: true } : {}), ...model() } });
      if (S.practice !== P) return;
      P.round += 1;
      const list = $('#s-prac-list');
      const fresh = b.questions.filter((q) => !P.asked.some((a) => a.toLowerCase() === q.q.toLowerCase()));
      P.empty = fresh.length ? 0 : P.empty + 1;
      if (P.empty >= 2) {                                        // nothing new twice in a row: stop instead of generating forever
        P.ended = true;
        more.innerHTML = `${stateHtml('empty', 'No new questions for this module right now', 'Try another module or come back later.', true)}<button type="button" class="btn btn-sm" data-s="prac-retry">Try once more</button>`;
        return;
      }
      for (const q of fresh) {
        P.asked.push(q.q);
        const i = P.n++;
        const div = document.createElement('div');
        div.className = 'card s-q'; div.dataset.i = String(i);
        div._q = q;
        div.innerHTML = `<div class="q-head"><span class="muted small">${esc(q.module || b.module || '')}</span><span class="q-ai">AI</span></div>
          <p class="q-text">${i + 1}. ${esc(q.q)}</p><div class="options">${q.options.map((o, k) => `<button type="button" class="option" data-s="pick" data-k="${k}"><span class="letter">${'ABCD'[k]}</span>${esc(o)}</button>`).join('')}</div><div class="s-why"></div>`;
        list.appendChild(div);
      }
      more.innerHTML = '';
    } catch (e) {
      if (S.practice !== P) return;
      P.failed = true;
      more.innerHTML = `${stateHtml('error', "Couldn't write more questions", e.message, true)}<button type="button" class="btn btn-sm" data-s="prac-retry">Try again</button>`;
    } finally { P.busy = false; }
    // a short first batch may not fill the screen: keep going while the end is visible
    if (S.practice === P && !P.failed && !P.ended) {
      const r = $('#s-prac-more')?.getBoundingClientRect();
      if (r && r.top < window.innerHeight + 200) setTimeout(() => morePractice(), 50);
    }
  }
  async function pick(card, k) {
    const q = card._q;
    if (!q || card.dataset.done) return;
    card.dataset.done = '1';
    const ok = k === q.answer;
    card.querySelectorAll('.option').forEach((b, j) => { b.disabled = true; if (j === q.answer) b.classList.add('correct'); else if (j === k) b.classList.add('wrong'); });
    card.querySelector('.s-why').innerHTML = `<p class="small"><b>${ok ? '✅ Correct.' : `❌ Not quite: the answer is ${'ABCD'[q.answer]}.`}</b> ${esc(q.explanation)}</p>`;
    const P = S.practice;
    if (P) { P.answered++; if (ok) P.right++; const s = $('#s-prac-score'); if (s) s.textContent = `${P.right}/${P.answered} right`; }
    try { await sApi('/learn/mcq/answer', { method: 'POST', body: { skill: q.skill || S.detail?.title || 'General', question: q, correct: ok, course_id: q.course_id || S.cid, module_id: q.module_id } }); } catch { toast("Your answer couldn't be saved."); }
  }

  /* ---------- study time (real, only while a lesson or practice is open and visible) ---------- */
  function startTimer(cid) {
    if (S.timer && S.timer.cid === cid) return;
    stopTimer();
    S.timer = { cid, id: setInterval(() => {
      if (document.visibilityState === 'visible' && document.hasFocus() && $('#tab-study')?.classList.contains('active')) S.pending += 15;
      if (S.pending >= 60) flushTime();
    }, 15000) };
  }
  function flushTime() {
    const t = S.timer; if (!t || !S.pending) return;
    const sec = Math.min(600, S.pending); S.pending = 0;
    sApi(`/learn/courses/${encodeURIComponent(t.cid)}/time`, { method: 'POST', body: { seconds: sec } }).catch(() => {});
  }
  function stopTimer() { if (!S.timer) return; flushTime(); clearInterval(S.timer.id); S.timer = null; }
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flushTime(); });

  /* ---------- skills → courses (from the plan and weak areas) ---------- */
  async function studySkill(skill, practise = false) {
    const t = await tracks();
    for (const tr of t.tracks) {
      const mod = tr.modules.find((m) => m.skills.includes(skill));
      if (!mod) continue;
      const c = await sApi('/learn/courses', { method: 'POST', body: { track: tr.id } });
      await openCourse(c.id, practise ? 'practice' : 'roadmap');
      if (!S.dash.prefs.chosen) { toast('Choose your goal and level first; then the course opens.'); return; }
      if (practise) { S.tab = 'practice'; renderCourse(); stopPractice(); startPractice({ moduleId: mod.id }); return; }
      const m = S.detail.modules.find((x) => x.id === mod.id);
      const l = m.lessons.find((x) => !x.done) || m.lessons[0];
      openLesson(l.id);
      return;
    }
    if (confirm(`No ready-made course covers "${skill}". Create a course on it with the AI?`)) createCustom(skill);
  }
  async function createCustom(topic) {
    show('courses');
    $('#sv-courses').innerHTML = busyHtml(`Writing a course outline for "${topic}"…`);
    try { const c = await sApi('/learn/courses', { method: 'POST', body: { topic, ...model() } }); openCourse(c.id, 'roadmap'); } catch (e) { toast(e.message, 7000); renderCourses(); }
  }

  /* ---------- interview plan from a job description ---------- */
  function planItemHtml(it, key) {
    const act = { lesson: ['Study', 'study-skill'], mcq: ['Practise', 'practise-skill'], story: ['Stories', 'stories'], review: ['Revise', 'rev-daily'] }[it.type];
    const realSkill = !['All skills', 'Missed questions', 'Behavioural'].includes(it.skill);
    return `<li><label class="field check inline"><input type="checkbox" data-s="plan-item" data-key="${esc(key)}" ${it.done ? 'checked' : ''}>
      <span>${esc(it.title)} <span class="muted small">· ${it.minutes} min</span></span></label>
      ${act && (realSkill || it.type === 'story' || it.type === 'review') ? `<button type="button" class="link-btn" data-s="${act[1]}" data-skill="${esc(it.skill)}">${act[0]}</button>` : ''}</li>`;
  }
  async function renderPlan(forceForm = false) {
    const el = $('#sv-plan');
    el.innerHTML = skeleton(2);
    let st; try { st = await sApi('/learn/state'); } catch (e) { el.innerHTML = errHtml("Couldn't load the plan", e, 'plan'); return; }
    const p = st.plan;
    if (!p || forceForm) {
      el.innerHTML = `<form class="card" id="s-plan-form"><h3>Interview plan from a job description</h3>
        <p class="muted small">Paste the job description. Abhify finds the skills it asks for, compares them with your resume and makes a day-by-day plan until your interview. Everything stays ${remote() ? 'on this computer' : 'in this browser'}.</p>
        <label class="field"><span>Job description</span><textarea class="input" id="s-jd" rows="8" maxlength="20000" required placeholder="Paste the full job post, including the requirements"></textarea></label>
        <label class="field"><span>Your resume or skills (optional)</span><textarea class="input" id="s-resume" rows="4" maxlength="20000" placeholder="Paste your resume text, or just a list of your skills"></textarea></label>
        <div class="s-form-row"><label class="field"><span>Interview date (optional)</span><input class="input" type="date" id="s-date" min="${iso()}"></label>
          <label class="field"><span>Time per day</span><select class="select" id="s-pmin">${[30, 45, 60, 90, 120].map((m) => `<option value="${m}" ${m === 60 ? 'selected' : ''}>${m} minutes</option>`).join('')}</select></label></div>
        <div class="row-tight"><button class="btn btn-primary btn-sm" type="submit">Make my plan</button>${p ? '<button type="button" class="btn btn-sm" data-s="plan">Cancel</button>' : ''}</div></form>`;
      return;
    }
    const today = iso(), left = p.interview ? Math.round((Date.parse(`${p.interview}T00:00:00`) - Date.parse(`${today}T00:00:00`)) / 86400000) : null;
    el.innerHTML = `<div class="card"><div class="card-head"><div><h3>${esc(p.jd_title || 'Your interview plan')}</h3>
        <p class="muted small">${p.interview ? `Interview ${esc(p.interview)}${left >= 0 ? ` · ${left} day${left === 1 ? '' : 's'} to go` : ' (date passed)'}` : `${p.days.length}-day plan (no interview date)`} · ${p.minutes} min a day</p></div>
        <div class="row-tight"><button type="button" class="chip" data-s="plan-new">New plan</button><button type="button" class="chip" data-s="plan-del">Delete</button></div></div>
        ${ring(st.readiness.overall, 'Readiness for this job')}<p class="small"><b>${esc(st.readiness.level)}</b> · from your practice answers and finished lessons on these skills</p>
        <b class="small">The job asks for</b><p>${p.skills.required.map((s) => `<span class="s-tag">${esc(s)}</span>`).join(' ')}${p.skills.preferred.map((s) => `<span class="s-tag s-tag-soft">${esc(s)} (nice to have)</span>`).join(' ')}</p>
        ${p.resume_skills.length ? `<b class="small">Found on your resume</b><p class="small muted">${p.resume_skills.map(esc).join(', ')}</p>` : ''}
        <b class="small">Focus, in order</b><ol class="list small">${p.gaps.map((g) => { const r = st.readiness.skills.find((x) => x.skill === g.skill); return `<li>${esc(g.skill)} <span class="muted">· ${esc(g.why)}${r ? ` · ${r.score}%` : ''}</span> <button type="button" class="link-btn" data-s="study-skill" data-skill="${esc(g.skill)}">Study</button></li>`; }).join('')}</ol></div>
      ${st.plan_history?.length ? `<details class="card"><summary><b>Earlier plans</b> <span class="muted small">(${st.plan_history.length}, kept for reference)</span></summary>
        <ul class="list small">${st.plan_history.slice().reverse().map((h) => `<li>${esc(h.jd_title || 'Plan')} · made ${esc(new Date(h.created).toLocaleDateString())} · replaced ${esc(new Date(h.archived_at).toLocaleDateString())} · ${h.gaps.length} focus skills</li>`).join('')}</ul></details>` : ''}
      <div class="s-days">${p.days.map((d) => { const doneN = d.items.filter((_, j) => p.done.includes(`${d.day}:${j}`)).length; return `<details class="card s-day ${d.date === today ? 's-today' : ''}" ${d.date === today ? 'open' : ''}>
        <summary><b>Day ${d.day}</b> · ${esc(d.date)}${d.date === today ? ' · today' : ''} <span class="muted small">${doneN}/${d.items.length} done</span></summary>
        <ul class="s-items">${d.items.map((it, j) => planItemHtml({ ...it, done: p.done.includes(`${d.day}:${j}`) }, `${d.day}:${j}`)).join('')}</ul></details>`; }).join('')}</div>`;
  }

  /* ---------- STAR stories ---------- */
  const STAR = [['situation', 'Situation', 'Where and when? What was going on?'], ['task', 'Task', 'What were you responsible for?'],
    ['action', 'Action', 'What did *you* do, step by step?'], ['result', 'Result', 'What changed? Numbers if you have them.']];
  async function renderStories(editId = null) {
    const el = $('#sv-stories');
    el.innerHTML = skeleton(2);
    let r; try { r = await sApi('/learn/stories'); } catch (e) { el.innerHTML = errHtml("Couldn't load stories", e, 'stories'); return; }
    const count = Object.fromEntries(r.competencies.map((c) => [c.id, r.stories.filter((s) => s.competencies.includes(c.id)).length]));
    if (editId !== null) { storyEditor(r, editId === '' ? null : r.stories.find((s) => s.id === editId)); return; }
    el.innerHTML = `<div class="card"><h3>STAR story bank</h3><p class="muted small">Behavioural interviews ask for stories. Write each once (Situation, Task, Action, Result), get AI coaching, and cover all 8 common themes.</p>
        <div class="s-comp">${r.competencies.map((c) => `<span class="s-tag ${count[c.id] ? '' : 's-tag-soft'}" title="${esc(c.question)}">${count[c.id] ? '✓' : '○'} ${esc(c.title)}</span>`).join('')}</div>
        <button type="button" class="btn btn-primary btn-sm" data-s="story-new">Write a story</button></div>
      ${r.stories.length ? r.stories.slice().sort((a, b) => b.updated - a.updated).map((s) => `<button type="button" class="card card--interactive s-row" data-s="story-edit" data-id="${esc(s.id)}">
        <b>${esc(s.title)}</b><span class="muted small">${s.competencies.map((k) => esc(r.competencies.find((c) => c.id === k)?.title || k)).join(', ') || 'no theme chosen'}${s.coach ? ` · coached: ${Object.values(s.coach.scores).reduce((a, b) => a + b, 0)}/20` : ''}</span></button>`).join('')
        : stateHtml('empty', 'No stories yet', `Start with: ${r.competencies[0].question}`)}`;
  }
  function storyEditor(r, s) {
    const el = $('#sv-stories');
    s ||= { id: null, title: '', competencies: [], situation: '', task: '', action: '', result: '', coach: null };
    el.innerHTML = `<button type="button" class="chip" data-s="stories">← Stories</button>
      <form class="card" id="s-story-form" data-id="${esc(s.id || '')}"><label class="field"><span>Title</span><input class="input" id="s-st-title" maxlength="120" required value="${esc(s.title)}" placeholder="e.g. Rescued a failing data migration"></label>
        <fieldset class="s-pref"><legend>Themes this story shows</legend><div class="s-comp">${r.competencies.map((c) => `<label class="s-tag s-check"><input type="checkbox" name="s-comp" value="${esc(c.id)}" ${s.competencies.includes(c.id) ? 'checked' : ''}> ${esc(c.title)}</label>`).join('')}</div></fieldset>
        ${STAR.map(([k, t, h]) => `<label class="field"><span>${t}</span><textarea class="input" data-f="${k}" rows="3" maxlength="2000" placeholder="${esc(h)}">${esc(s[k])}</textarea></label>`).join('')}
        <div class="row-tight"><button class="btn btn-primary btn-sm" type="submit">Save</button><button type="button" class="btn btn-sm" data-s="story-coach">Save and get coaching</button>
          ${s.id ? '<button type="button" class="btn btn-sm" data-s="story-del">Delete</button>' : ''}</div></form>
      <div id="s-coach-out">${s.coach ? coachHtml(s.coach) : ''}</div>`;
  }
  function coachHtml(c) {
    return `<div class="card"><span class="card-title">Coaching</span><div class="s-star">${STAR.map(([k, t]) => `<div><span class="small">${t}</span>${bar(c.scores[k] * 20, `${t} score`)}<b class="small">${c.scores[k]}/5</b></div>`).join('')}</div>
      ${c.missing.length ? `<b class="small">Missing</b><ul class="list small">${c.missing.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
      ${c.tips.length ? `<b class="small">Tips</b><ul class="list small">${c.tips.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}
      ${c.improved ? `<b class="small">A tighter version (only your facts)</b><blockquote class="s-quote">${esc(c.improved)}</blockquote><button type="button" class="link-btn" data-s="copy-improved">Copy</button>` : ''}
      <p class="muted small">AI feedback; your story stays as you wrote it.</p></div>`;
  }
  async function saveStory(coach) {
    const f = $('#s-story-form');
    const body = { title: $('#s-st-title').value.trim(), competencies: [...f.querySelectorAll('input[name="s-comp"]:checked')].map((x) => x.value) };
    if (f.dataset.id) body.id = f.dataset.id;
    f.querySelectorAll('textarea[data-f]').forEach((t) => { body[t.dataset.f] = t.value.trim(); });
    if (!body.title) { toast('Give the story a short title.'); return; }
    let s; try { s = await sApi('/learn/stories', { method: 'POST', body }); } catch (e) { toast(e.message, 6000); return; }
    f.dataset.id = s.id;
    if (!coach) { toast('Story saved'); renderStories(); return; }
    $('#s-coach-out').innerHTML = busyHtml('Your coach is reading the story…');
    try { const c = await sApi(`/learn/stories/${encodeURIComponent(s.id)}/coach`, { method: 'POST', body: model() }); $('#s-coach-out').innerHTML = coachHtml(c); } catch (e) { $('#s-coach-out').innerHTML = errHtml("Couldn't get coaching", e); }
  }

  /* ---------- mock interview (dev.2): questions from the interview plan, spoken with the app's voice; mic only while answering ---------- */
  const MOCK = { s: null, i: 0, t0: 0, tick: 0, rec: null };
  async function renderMock(sid = null) {
    const el = $('#sv-mock');
    stopMockTimer();
    if (sid) {
      el.innerHTML = skeleton(2);
      try { MOCK.s = await sApi(`/learn/mock/${encodeURIComponent(sid)}`); } catch (e) { el.innerHTML = errHtml("Couldn't open the session", e, 'mock'); return; }
      MOCK.i = Math.max(0, MOCK.s.questions.findIndex((q) => !q.answer));
      if (MOCK.s.finished || MOCK.s.questions.every((q) => q.answer)) { mockSummary(); return; }
      mockQuestion(); return;
    }
    el.innerHTML = skeleton(2);
    let lst, st;
    try { [lst, st] = await Promise.all([sApi('/learn/mock'), sApi('/learn/state')]); } catch (e) { el.innerHTML = errHtml("Couldn't load interviews", e, 'mock'); return; }
    el.innerHTML = `<form class="card" id="s-mock-form"><h3>Mock interview</h3>
        <p class="muted small">${st.plan ? `Questions on the skills in your interview plan (${esc(st.plan.jd_title || 'job')}), alternating with behavioural questions.` : 'Make an interview plan first for job-specific questions; otherwise your weakest practice skills are used.'}
        Each answer is scored for relevance, depth and structure, quoting only what you said. ${remote() ? 'Speech is transcribed on this computer.' : ''}</p>
        <label class="field"><span>Questions</span><select class="select" id="s-mock-n">${[4, 6, 8, 10].map((n) => `<option value="${n}" ${n === 6 ? 'selected' : ''}>${n}</option>`).join('')}</select></label>
        <button class="btn btn-primary btn-sm" type="submit">Start interview</button></form>
      ${lst.sessions.length ? `<h3 class="set-h">Earlier interviews</h3>${lst.sessions.map((m) => `<div class="card s-row-flex"><button type="button" class="s-row" data-s="mock-open" data-id="${esc(m.id)}">
        <b>${esc(new Date(m.created).toLocaleString())}${m.job ? ` · ${esc(m.job)}` : ''}</b><span class="muted small">${m.answered}/${m.questions} answered${m.summary?.percent != null ? ` · ${m.summary.percent}%` : ''}</span></button>
        <button type="button" class="link-btn" data-s="mock-del" data-id="${esc(m.id)}" aria-label="Delete this interview">Delete</button></div>`).join('')}` : ''}`;
  }
  function stopMockTimer() { clearInterval(MOCK.tick); MOCK.tick = 0; try { MOCK.rec?.stop(); } catch { /* stopped */ } }
  function mockQuestion() {
    const s = MOCK.s, q = s.questions[MOCK.i], el = $('#sv-mock');
    el.innerHTML = `<button type="button" class="chip" data-s="mock">← Interviews</button>
      <div class="card"><p class="muted small">Question ${MOCK.i + 1} of ${s.questions.length} · ${q.kind === 'technical' ? esc(q.skill) : 'Behavioural'}</p>
        <h3 class="s-mock-q">${esc(q.question)}</h3>
        <div class="row-tight"><button type="button" class="btn btn-sm" data-s="mock-speak">▶ Read it to me</button><span class="s-pill" id="s-mock-timer" aria-live="off">0:00 / 2:00</span></div>
        <label class="field"><span>Your answer</span><textarea class="input" id="s-mock-a" rows="6" maxlength="4000" placeholder="Type, or use the microphone">${esc(q.answer || '')}</textarea></label>
        <div class="row-tight"><button type="button" class="btn btn-sm" data-s="mock-mic" aria-pressed="false">🎤 Answer by voice</button>
          <button type="button" class="btn btn-primary btn-sm" data-s="mock-submit">Submit answer</button>
          <button type="button" class="link-btn" data-s="mock-skip">${MOCK.i + 1 < s.questions.length ? 'Skip' : 'Finish'}</button></div>
        <div id="s-mock-fb">${q.feedback ? feedbackHtml(q.feedback) : ''}</div></div>`;
    MOCK.t0 = Date.now();
    MOCK.tick = setInterval(() => {
      const sec = Math.round((Date.now() - MOCK.t0) / 1000), t = $('#s-mock-timer');
      if (t) { t.textContent = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, '0')} / 2:00`; t.classList.toggle('s-bad', sec > 120); }
    }, 1000);
  }
  function feedbackHtml(f) {
    return `<div class="s-star">${['relevance', 'depth', 'structure'].map((k) => `<div><span class="small">${k[0].toUpperCase() + k.slice(1)}</span>${bar(f.scores[k] * 20, `${k} score`)}<b class="small">${f.scores[k]}/5</b></div>`).join('')}</div>
      ${f.evidence.length ? `<b class="small">From your answer</b><ul class="list small">${f.evidence.map((x) => `<li>“${esc(x)}”</li>`).join('')}</ul>` : ''}
      ${f.tips.length ? `<b class="small">Tips</b><ul class="list small">${f.tips.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : ''}`;
  }
  async function mockSummary() {
    stopMockTimer();
    const s = await sApi(`/learn/mock/${encodeURIComponent(MOCK.s.id)}/finish`, { method: 'POST' });
    MOCK.s = s;
    const m = s.summary;
    $('#sv-mock').innerHTML = `<button type="button" class="chip" data-s="mock">← Interviews</button>
      <div class="card"><h3>Interview result</h3>${m.percent == null ? '<p>No answers were scored.</p>' : `${ring(m.percent, 'Interview score')}
        <p class="small">${m.answered} answer${m.answered === 1 ? '' : 's'} scored · relevance ${m.means.relevance} · depth ${m.means.depth} · structure ${m.means.structure} (out of 5). Work most on <b>${esc(m.weakest)}</b>.</p>`}</div>
      ${s.questions.map((q, i) => `<details class="card"><summary><b>${i + 1}. ${esc(q.question)}</b>${q.feedback ? ` <span class="muted small">${q.feedback.total}/15</span>` : q.answer ? ' <span class="muted small">not scored</span>' : ' <span class="muted small">skipped</span>'}</summary>
        ${q.answer ? `<p class="small"><b>You said:</b> ${esc(q.answer)}${q.seconds != null ? ` <span class="muted">(${q.seconds}s)</span>` : ''}</p>` : ''}${q.feedback ? feedbackHtml(q.feedback) : ''}</details>`).join('')}
      <p class="muted small">AI feedback from a local model; it can be wrong. The transcript is saved ${remote() ? 'on this computer' : 'in this browser'}.</p>`;
  }
  async function mockMic(btn) {
    if (MOCK.rec) { MOCK.rec.stop(); return; }
    const box = $('#s-mock-a');
    if (remote()) {                                                // this computer's Whisper (/transcribe), like the voice button
      let stream; try { stream = await openMic(); } catch (e) { toast(micErrorMessage(e), 8000); return; }
      const chunks = [], rec = new MediaRecorder(stream);
      MOCK.rec = rec; btn.setAttribute('aria-pressed', 'true'); btn.textContent = '■ Stop';
      rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
      rec.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop()); MOCK.rec = null; btn.setAttribute('aria-pressed', 'false'); btn.textContent = 'Transcribing…';
        const form = new FormData(); form.append('file', new Blob(chunks, { type: rec.mimeType || 'audio/webm' }), 'answer.webm');
        try { const r = await api('/transcribe', { method: 'POST', form, timeout: 180000 }); if (r.text) box.value = `${box.value ? `${box.value} ` : ''}${r.text}`; else toast('No speech was recognised. Try again a little closer to the microphone.', 6000); }
        catch (e) { toast(`Transcription failed: ${e.message}`, 7000); }
        btn.textContent = '🎤 Answer by voice';
      };
      rec.start(); setTimeout(() => { if (MOCK.rec === rec) rec.stop(); }, 180000);
      return;
    }
    if (!SR) { toast("This browser can't turn speech into text. Type your answer instead.", 6000); return; }
    if (!store.get('abhify.study.srNotice')) {
      if (!confirm("Speech recognition in the browser is done by the browser's maker (online). Your spoken answer is sent to them to be turned into text. Continue?")) return;
      store.set('abhify.study.srNotice', '1');
    }
    const r = new SR(); r.lang = 'en-US'; r.continuous = true; r.interimResults = false;
    MOCK.rec = r; btn.setAttribute('aria-pressed', 'true'); btn.textContent = '■ Stop';
    r.onresult = (e) => { for (let k = e.resultIndex; k < e.results.length; k++) if (e.results[k].isFinal) box.value = `${box.value ? `${box.value} ` : ''}${e.results[k][0].transcript.trim()}`; };
    r.onerror = (e) => toast(srErrorMessage ? srErrorMessage(e.error) : 'Speech recognition stopped.', 6000);
    r.onend = () => { MOCK.rec = null; btn.setAttribute('aria-pressed', 'false'); btn.textContent = '🎤 Answer by voice'; };
    r.start();
  }

  /* ---------- resume tailoring (dev.2): only facts already in the resume; nothing stored ---------- */
  function renderResume() {
    $('#sv-resume').innerHTML = `<form class="card" id="s-resume-form"><h3>Tailor your resume to a job</h3>
        <p class="muted small">Your most relevant bullet points are rewritten for the job. A rewrite that adds a number or a skill that isn't in your resume is left out. Nothing is saved${remote() ? ' or sent online' : ' or sent anywhere'}.</p>
        <label class="field"><span>Job description</span><textarea class="input" id="s-tl-jd" rows="7" maxlength="20000" required placeholder="Paste the full job post"></textarea></label>
        <label class="field"><span>Your resume</span><textarea class="input" id="s-tl-cv" rows="8" maxlength="30000" required placeholder="Paste your resume text (one achievement per line)"></textarea></label>
        <button class="btn btn-primary btn-sm" type="submit">Tailor my bullets</button></form><div id="s-tl-out"></div>`;
  }
  function tailorHtml(r) {
    const ok = r.bullets.filter((b) => b.rewritten);
    return `<div class="card"><p class="small"><b>The job asks for:</b> ${r.job_skills.map((x) => `<span class="s-tag ${r.covered.includes(x) ? '' : 's-tag-soft'}">${esc(x)}${r.covered.includes(x) ? '' : ' (not in your resume)'}</span>`).join(' ')}</p>
        ${ok.length ? `<button type="button" class="btn btn-sm" data-s="tl-copy-all">Copy all ${ok.length} rewritten bullets</button>` : ''}</div>
      ${r.bullets.map((b, i) => `<div class="card s-tl"><p class="small muted">Original</p><p>${esc(b.original)}</p>
        ${b.rewritten ? `<p class="small muted">Rewritten</p><p class="s-tl-new" id="s-tl-${i}">${esc(b.rewritten)}</p><button type="button" class="link-btn" data-s="tl-copy" data-i="${i}">Copy</button>`
          : `<p class="small s-warn">${esc(b.rejected)}</p>`}</div>`).join('')}
      <p class="muted small">${esc(r.note)} Missing skills are listed so you can decide honestly how to cover them; never add experience you don't have.</p>`;
  }

  /* ---------- events (one delegated handler) ---------- */
  async function onClick(e) {
    const b = e.target.closest('[data-s], [data-sv]');
    if (!b || !$('#tab-study').contains(b)) return;
    if (b.dataset.sv) { go(b.dataset.sv); return; }
    const a = b.dataset.s;
    if (b.tagName === 'INPUT') return;                         // checkboxes are handled on change
    try {
      switch (a) {
        case 'today': case 'courses': case 'plan': case 'stories': go(a); break;
        case 'rev-daily': if (S.view !== 'today') show('today'); openRevision('daily'); break;
        case 'rev-weekly': openRevision('weekly'); break;
        case 'flip': { b.querySelector('.s-flash-back')?.classList.remove('hidden'); b.querySelector('.s-flash-front span:last-child')?.remove(); $('#s-grade')?.classList.remove('hidden'); break; }
        case 'grade': {
          const R = S.revision, card = R.cards[R.i];
          await sApi(`/learn/cards/${encodeURIComponent(card.id)}`, { method: 'POST', body: { remembered: b.dataset.ok === '1' } });
          if (b.dataset.ok === '1') R.right++;
          R.i++; renderRevisionStep(); break;
        }
        case 'rev-pick': {
          const R = S.revision, it = R.due[R.i - R.cards.length], q = it.question, k = +b.dataset.i, ok = k === q.answer;
          b.closest('.options').querySelectorAll('.option').forEach((o, j) => { o.disabled = true; if (j === q.answer) o.classList.add('correct'); else if (j === k) o.classList.add('wrong'); });
          $('#s-rev-why').innerHTML = `<p class="small"><b>${ok ? '✅ Correct.' : '❌ Not yet.'}</b> ${esc(q.explanation || '')}</p><button type="button" class="btn btn-sm" data-s="rev-next">Next</button>`;
          if (ok) R.right++;
          await sApi('/learn/mcq/answer', { method: 'POST', body: { skill: it.skill || 'General', question: q, correct: ok, course_id: it.course || null } });
          break;
        }
        case 'rev-next': S.revision.i++; renderRevisionStep(); break;
        case 'open-course': openCourse(b.dataset.cid, 'roadmap'); break;
        case 'add-track': { const c = await sApi('/learn/courses', { method: 'POST', body: { track: b.dataset.track } }); openCourse(c.id, 'roadmap'); break; }
        case 'save-prefs': savePrefs(); break;
        case 'cancel-prefs': renderCourse(); break;
        case 'edit-prefs': renderPrefs(); break;
        case 'build-ov': $('#sv-course').innerHTML = busyHtml('Building the course overview…');
          try { await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/overview`, { method: 'POST', body: model() }); } catch (err) { toast(err.message, 6000); }
          openCourse(S.cid); break;
        case 'ctab': S.tab = b.dataset.tab; document.querySelectorAll('#sv-course [data-s="ctab"]').forEach((x) => x.setAttribute('aria-selected', String(x === b))); renderCourseTab(); break;
        case 'open-lesson': openLesson(b.dataset.lid); break;
        case 'back-course': stopTimer(); openCourse(S.cid); break;
        case 'regen': openLesson(S.lesson.lid, true); break;
        case 'lesson-done': {
          const done = !S.lesson.l.done;
          await sApi('/learn/lesson/done', { method: 'POST', body: { course_id: S.cid, lesson_id: S.lesson.lid, done } });
          S.lesson.l.done = done; b.textContent = done ? '✓ Lesson done' : 'Mark lesson done'; b.classList.toggle('btn-primary', !done); b.setAttribute('aria-pressed', String(done));
          if (done) toast('Lesson done. Nice work!');
          break;
        }
        case 'exercise-done': {
          const done = b.getAttribute('aria-pressed') !== 'true';
          const c = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/exercise`, { method: 'POST', body: { course_id: S.cid, lesson_id: S.lesson.lid, done } });
          S.detail.exercises = c.exercises; b.textContent = done ? '✓ Exercise done' : 'I did the exercise'; b.setAttribute('aria-pressed', String(done));
          break;
        }
        case 'make-cards': {
          b.disabled = true; b.textContent = 'Making flashcards…';
          try { const r = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/cards`, { method: 'POST', body: { course_id: S.cid, lesson_id: S.lesson.lid, ...model() } }); toast(`${r.cards.length} flashcards added to today's revision`); b.textContent = '✓ Flashcards made'; } catch (err) { toast(err.message, 6000); b.disabled = false; b.textContent = 'Make flashcards'; }
          break;
        }
        case 'practice-mod': case 'practice-weak': {
          if (S.view === 'lesson') { stopTimer(); await openCourse(S.cid); }
          S.tab = 'practice'; renderCourse(); stopPractice();
          startPractice(a === 'practice-weak' ? { weak: true } : { moduleId: b.dataset.mid }); break;
        }
        case 'prac-retry': morePractice(true); break;
        case 'pick': pick(b.closest('.s-q'), +b.dataset.k); break;
        case 'make-capstone': {
          $('#s-course-body').innerHTML = busyHtml('Designing a capstone project for your goal…');
          try { S.detail.capstone = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/capstone`, { method: 'POST', body: model() }); } catch (err) { toast(err.message, 6000); }
          renderCapstone(); break;
        }
        case 'study-skill': studySkill(b.dataset.skill); break;
        case 'practise-skill': studySkill(b.dataset.skill, true); break;
        case 'plan-new': renderPlan(true); break;
        case 'plan-del': if (confirm('Delete this interview plan? Your course progress and stories stay.')) { await sApi('/learn/plan', { method: 'DELETE' }); renderPlan(); } break;
        case 'story-new': renderStories(''); break;
        case 'story-edit': renderStories(b.dataset.id); break;
        case 'story-coach': saveStory(true); break;
        case 'story-del': if (confirm('Delete this story?')) { await sApi(`/learn/stories/${encodeURIComponent($('#s-story-form').dataset.id)}`, { method: 'DELETE' }); renderStories(); } break;
        case 'export': {
          const data = await sApi('/learn/export');
          const a2 = document.createElement('a');
          a2.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' }));
          a2.download = `abhify-study-${iso()}.json`; document.body.appendChild(a2); a2.click(); a2.remove();
          setTimeout(() => URL.revokeObjectURL(a2.href), 5000); toast('Study data exported'); break;
        }
        case 'import': $('#s-import-file').click(); break;
        case 'copy-improved': if (await copyText(b.previousElementSibling.textContent)) toast('Copied'); break;
        case 'quality': {
          b.disabled = true; b.textContent = 'Checking (3 short prompts)…';
          try { await sApi('/learn/quality', { method: 'POST', body: { ...model(), model_label: modelLabel() } }); } catch (err) { toast(err.message, 6000); }
          renderQuality(); break;
        }
        case 'brain': renderBrain(); break;
        case 'report': openReport(); break;
        case 'print-report': document.body.classList.add('s-printing'); window.print(); setTimeout(() => document.body.classList.remove('s-printing'), 500); break;
        case 'mock': renderMock(); break;
        case 'mock-open': renderMock(b.dataset.id); break;
        case 'mock-del': if (confirm('Delete this interview and its transcript?')) { await sApi(`/learn/mock/${encodeURIComponent(b.dataset.id)}`, { method: 'DELETE' }); renderMock(); } break;
        case 'mock-speak': speak(MOCK.s.questions[MOCK.i].question, 'study'); break;
        case 'mock-mic': mockMic(b); break;
        case 'mock-submit': {
          const a = $('#s-mock-a').value.trim(); if (!a) { toast('Say or type your answer first.'); break; }
          stopMockTimer();
          const sec = Math.round((Date.now() - MOCK.t0) / 1000);
          b.disabled = true; $('#s-mock-fb').innerHTML = busyHtml('Scoring your answer…');
          try {
            const r = await sApi(`/learn/mock/${encodeURIComponent(MOCK.s.id)}/answer`, { method: 'POST', body: { i: MOCK.i, answer: a, seconds: sec, ...model() } });
            MOCK.s.questions[MOCK.i] = { ...MOCK.s.questions[MOCK.i], answer: a, seconds: sec, feedback: r.feedback };
            $('#s-mock-fb').innerHTML = (r.feedback ? feedbackHtml(r.feedback) : `<p class="small s-warn">${esc(r.note)}</p>`)
              + `<button type="button" class="btn btn-primary btn-sm" data-s="mock-next">${MOCK.i + 1 < MOCK.s.questions.length ? 'Next question' : 'See result'}</button>`;
          } catch (err) { $('#s-mock-fb').innerHTML = errHtml("Couldn't score the answer", err); b.disabled = false; }
          break;
        }
        case 'mock-skip': case 'mock-next':
          if (MOCK.i + 1 < MOCK.s.questions.length) { MOCK.i++; stopMockTimer(); mockQuestion(); } else mockSummary();
          break;
        case 'tl-copy': if (await copyText($(`#s-tl-${b.dataset.i}`).textContent)) toast('Copied'); break;
        case 'tl-copy-all': if (await copyText([...document.querySelectorAll('.s-tl-new')].map((x) => `• ${x.textContent}`).join('\n'))) toast('Copied'); break;
        default: break;
      }
    } catch (err) { toast(err.message || String(err), 6000); }
  }
  async function onChange(e) {
    const t = e.target;
    try {
      if (t.dataset.s === 'plan-item') {
        const [day, index] = t.dataset.key.split(':').map(Number);
        await sApi('/learn/plan/item', { method: 'POST', body: { day, index, done: t.checked } });
      } else if (t.dataset.s === 'cap-step') {
        S.detail.capstone = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/capstone/step`, { method: 'POST', body: { index: +t.dataset.i, done: t.checked } });
      } else if (t.id === 's-import-file' && t.files?.[0]) {
        const body = JSON.parse(await t.files[0].text()); t.value = '';
        if (!confirm('Replace the Study data here with this file? Your current Study data is kept as a separate copy first.')) return;
        const r = await sApi('/learn/import', { method: 'POST', body });
        toast(`Imported ${r.courses} courses, ${r.stories} stories, ${r.cards} flashcards`); renderToday();
      } else if (t.id === 's-prac-scope') {
        const v = t.value; stopPractice(); startPractice(v === '__weak' ? { weak: true } : v ? { moduleId: v } : {});
      } else if (t.name === 's-target' || t.name === 's-level') {
        const custom = $(t.name === 's-target' ? '#s-target-custom' : '#s-level-custom');
        if (t.value === '__custom') custom.focus();
      }
    } catch (err) { t.checked = !t.checked; toast(err.message, 6000); }
  }
  async function onSubmit(e) {
    const f = e.target;
    if (!$('#tab-study').contains(f) || !f.id?.startsWith('s-')) return;
    e.preventDefault();
    if (f.id === 's-custom-form') createCustom($('#s-custom-topic').value.trim());
    if (f.id === 's-story-form') saveStory(false);
    if (f.id === 's-plan-form') {
      const btn = f.querySelector('button[type="submit"]'); btn.disabled = true;
      try {
        await sApi('/learn/plan', { method: 'POST', body: { jd: $('#s-jd').value, resume_text: $('#s-resume').value, interview_date: $('#s-date').value || null, minutes: +$('#s-pmin').value } });
        renderPlan();
      } catch (err) { toast(err.message, 7000); btn.disabled = false; }
    }
    if (f.id === 's-brain-form') {
      const body = { enabled: $('#s-brain-on').checked, memories: [...f.querySelectorAll('input[name="s-bm"]:checked')].map((x) => (remote() ? +x.value : x.value)),
        projects: [...f.querySelectorAll('input[name="s-bp"]:checked')].map((x) => x.value) };
      try { S.detail.brain = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/brain`, { method: 'PUT', body }); toast(body.enabled ? 'Personal context is on for this course' : 'Personal context is off'); openCourse(S.cid); } catch (err) { toast(err.message, 6000); }
    }
    if (f.id === 's-mock-form') {
      const btn = f.querySelector('button[type="submit"]'); btn.disabled = true; btn.textContent = 'Preparing questions…';
      try { MOCK.s = await sApi('/learn/mock', { method: 'POST', body: { count: +$('#s-mock-n').value, ...model() } }); MOCK.i = 0; mockQuestion(); }
      catch (err) { toast(err.message, 7000); btn.disabled = false; btn.textContent = 'Start interview'; }
    }
    if (f.id === 's-resume-form') {
      const out = $('#s-tl-out'); out.innerHTML = busyHtml('Rewriting your most relevant bullets…');
      try { out.innerHTML = tailorHtml(await sApi('/learn/resume/tailor', { method: 'POST', body: { jd: $('#s-tl-jd').value, resume_text: $('#s-tl-cv').value, ...model() } })); }
      catch (err) { out.innerHTML = errHtml("Couldn't tailor the resume", err); }
    }
    if (f.id === 's-tutor-form') {
      const q = $('#s-tutor-q').value.trim(); if (!q) return;
      const out = $('#s-tutor-out'), item = document.createElement('div');
      item.className = 's-tutor-a'; item.innerHTML = `<p class="small"><b>You:</b> ${esc(q)}</p>${busyHtml('Thinking…')}`;
      out.prepend(item); $('#s-tutor-q').value = '';
      try { const r = await sApi(`/learn/courses/${encodeURIComponent(S.cid)}/tutor`, { method: 'POST', body: { lesson_id: S.lesson.lid, question: q, ...model() } }); item.innerHTML = `<p class="small"><b>You:</b> ${esc(q)}</p><div class="md">${md(r.answer)}</div>`; } catch (err) { item.innerHTML = `<p class="small"><b>You:</b> ${esc(q)}</p>${stateHtml('error', "The tutor couldn't answer", err.message, true)}`; }
    }
  }
  function onKey(e) {
    const b = e.target.closest?.('[data-s="flip"]');
    if (b && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); b.click(); }
  }

  function bind() {
    const root = $('#tab-study'); if (!root) return;
    root.addEventListener('click', onClick);
    root.addEventListener('change', onChange);
    root.addEventListener('submit', onSubmit);
    root.addEventListener('keydown', onKey);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bind); else bind();
  window.abhifyStudy = { sApi, remote, go };                  // for tests and the Home "Practise" shortcut
})();
