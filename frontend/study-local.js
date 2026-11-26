/* =====================================================================
   study-local.js: Study 7.2 for the standalone Web app and on-device mode (no backend).
   The same routes and answers as backend/study.py (/learn/*), kept in this browser's storage, with the AI from the
   on-device engine (injected as ai(prompt, {json, maxTokens})). Nothing leaves this browser.
   ===================================================================== */
import * as sc from './study-core.js';

const KEY = 'abhify.study.v1';
export const SCHEMA = 1;
const EMPTY = () => ({ schema: SCHEMA, courses: {}, lessons: {}, plan: null, plan_history: [], stories: [], mcq: {}, reviews: {}, activity: [], cards: {}, seen: [], rounds: {} });
const NUM = /\d+(?:[.,]\d+)?/g;
export const TARGETS = ['Senior AI/ML Engineer', 'LLM / GenAI Engineer', 'AI Platform / MLOps Engineer', 'AI Technical Lead / Architect',
  'Software Engineer', 'Data Scientist / Analyst', 'Cloud Engineer', 'General knowledge / personal learning'];
export const LEVELS = { beginner: 'Beginner: build strong fundamentals', intermediate: 'Intermediate: strengthen and advance',
  advanced: 'Advanced: production and architecture' };
const STAR = ['situation', 'task', 'action', 'result'];

const SECTIONS = ['in simple words', 'going deeper', 'advanced', 'real-world example', 'code example', 'common mistakes',
  'practice exercise', 'interview questions', 'follow-up questions'];
/** Small models write headings in other styles (underlined, **bold**, "1. Title"): make them "## Title". */
export function normalizeLesson(text) {
  return String(text || '').trim()
    .replace(/^(?:\*\*)?([^\n#*][^\n]{1,80}?)(?:\*\*)?\s*\n[-=]{3,}\s*$/gm, (_, h) => `## ${h.trim()}`)
    .replace(/^\s*\*\*([^*\n]{3,80})\*\*:?\s*$/gm, (_, h) => `## ${h.trim()}`)
    .replace(new RegExp(`^\\s*#{0,4}\\s*\\d+[.)]\\s+(?=(?:${SECTIONS.join('|')}))`, 'gmi'), '## ');
}
export function lessonOk(text) {
  const heads = [...String(text || '').matchAll(/^#{1,4}\s+(.+)$/gm)].map((m) => m[1].toLowerCase()).join(' ');
  return String(text || '').trim().length >= 400 && SECTIONS.filter((x) => heads.includes(x)).length >= 4;
}

export class StudyError extends Error {
  constructor(msg, status = 400) { super(msg); this.status = status; }
}

export function createLocal({ storage, ai, loadJson, now = () => new Date(), memories = async () => [] }) {
  let tracksCache = null, taxCache = null;
  const tracks = async () => (tracksCache ??= await loadJson('study-tracks.json'));
  const taxonomy = async () => (taxCache ??= (await loadJson('skills-taxonomy.json')).skills);
  const today = () => sc.todayIso(now());
  const ms = () => now().getTime();
  const id = (p) => p + Math.random().toString(16).slice(2, 12).padEnd(10, '0');
  /** Unreadable saved data is never overwritten: it is kept under '<key>.corrupt-<time>' and Study starts empty. */
  const load = () => {
    const raw = storage.getItem(KEY);
    if (!raw) return EMPTY();
    let d;
    try { d = JSON.parse(raw); } catch { d = null; }
    if (!d || typeof d !== 'object' || Array.isArray(d)) {
      try { storage.setItem(`${KEY}.corrupt-${ms()}`, raw); storage.removeItem?.(KEY); } catch { /* keep the original where it is */ }
      return EMPTY();
    }
    if ((d.schema || 0) > SCHEMA) throw new StudyError('Your study data was saved by a newer Abhify.AI. Update the app to open it; nothing was changed.');
    return { ...EMPTY(), ...d, schema: SCHEMA };
  };
  const save = (d) => {
    d.schema = SCHEMA;
    try { storage.setItem(KEY, JSON.stringify(d)); } catch { throw new StudyError("This browser's storage is full, so this wasn't saved. Export a backup, then delete a course or old flashcards."); }
  };
  const touch = (d) => { const t = today(); if (!d.activity.includes(t)) d.activity = [...d.activity, t].slice(-400); };
  const history = (d, cid, kind, detail) => {
    const c = d.courses[cid]; if (c) c.history = [...(c.history || []), { at: ms(), kind, detail: String(detail).slice(0, 120) }].slice(-300);
  };
  const track = async (tid) => {
    const t = (await tracks()).tracks.find((x) => x.id === tid);
    if (!t) throw new StudyError('Unknown course.');
    return t;
  };
  const outline = async (c) => (c.track ? { title: (await track(c.track)).title, modules: (await track(c.track)).modules } : { title: c.title, modules: c.modules });
  const course = (d, cid) => { const c = d.courses[cid]; if (!c) throw new StudyError('Unknown course.'); return c; };
  const findLesson = async (c, lid) => {
    for (const m of (await outline(c)).modules) for (const l of m.lessons) if (l.id === lid) return [m, l];
    throw new StudyError('Unknown lesson.');
  };
  const prefsOf = (c) => {
    const p = c.prefs || {};
    const target = String(p.target || 'General knowledge / personal learning').trim().slice(0, 80);
    const levelKey = LEVELS[p.level] ? p.level : p.level ? 'custom' : 'intermediate';
    const level = LEVELS[levelKey] || String(p.level).slice(0, 80);
    return { target, level_key: levelKey, level, minutes: Math.trunc(p.minutes || 60), optional: [...((c.overview || {}).optional || [])],
      chosen: Boolean(c.prefs), key: `${target}|${levelKey}|${level}` };
  };
  async function aiJson(prompt) {
    for (let i = 0; i < 2; i++) {
      const text = await ai(prompt, { json: true, maxTokens: 1200 });
      try { return JSON.parse(text); } catch { /* try the {...} inside */ }
      const m = String(text).match(/\{[\s\S]*\}/);
      if (m) { try { return JSON.parse(m[0]); } catch { /* retry */ } }
    }
    throw new StudyError('The AI returned something unreadable. Try again.');
  }
  const KEYS = new Set(['summary', 'objectives', 'prerequisites', 'optional', 'focus', 'completion', 'missing', 'tips', 'milestones', 'stretch']);
  const lst = (o, k, n, len = 200) => (Array.isArray(o?.[k]) ? o[k] : o?.[k] ? [o[k]] : [])
    .flatMap((x) => String(x).split(/;\s+|\n+/)).map((x) => x.trim().replace(/^[ .]+|[ .]+$/g, '').slice(0, len))
    .filter((x) => x && !KEYS.has(x.toLowerCase())).slice(0, n);

  /* ---- courses ---- */
  async function createCourse({ trackId, outlineData }) {
    const d = load();
    let c;
    if (trackId) {
      const t = await track(trackId);
      const existing = Object.values(d.courses).find((x) => x.track === trackId);
      if (existing) return existing;
      c = { id: `c-${trackId}`, track: trackId, title: t.title, created: ms(), done: [] };
    } else c = { id: id('c-'), track: null, title: outlineData.title, modules: outlineData.modules, created: ms(), done: [] };
    d.courses[c.id] = c; save(d); return c;
  }
  async function customCourse(topic) {
    topic = String(topic || '').trim();
    if (topic.length < 2) throw new StudyError('Type a topic for the course, for example "Kubernetes for beginners".');
    const o = await aiJson(`Create a short course outline for learning "${topic}" for a job interview. Reply with JSON only: `
      + '{"title": "...", "modules": [{"title": "...", "lessons": ["lesson title", "..."]}]}. '
      + 'Use 4 to 6 modules with 3 to 5 lessons each, ordered from basics to advanced. Lesson titles only, no content.');
    if (!o || !Array.isArray(o.modules)) throw new StudyError("The AI didn't return a course outline. Try again.");
    const tax = await taxonomy();
    const mods = [];
    o.modules.slice(0, 8).forEach((m, i) => {
      if (!m || !String(m.title || '').trim()) return;
      const lessons = (m.lessons || []).map((x) => String(x).trim().slice(0, 120)).filter(Boolean).slice(0, 6);
      if (lessons.length < 2) return;
      const mid = `m${i + 1}`;
      mods.push({ id: mid, title: String(m.title).trim().slice(0, 120), skills: sc.findSkills(`${topic} ${m.title}`, tax).slice(0, 4),
        lessons: lessons.map((t, j) => ({ id: `${mid}-${j + 1}`, title: t })) });
    });
    if (mods.length < 3) throw new StudyError("The AI's course outline was too short. Try again, or choose one of the ready-made courses.");
    return createCourse({ outlineData: { title: (String(o.title || topic).trim() || topic).slice(0, 120), modules: mods } });
  }
  function deleteCourse(cid) {
    const d = load();
    if (!d.courses[cid]) throw new StudyError('Unknown course.', 404);
    delete d.courses[cid];
    for (const k of Object.keys(d.lessons)) if (k.startsWith(`${cid}/`)) delete d.lessons[k];
    for (const [k, v] of Object.entries(d.cards)) if (v.course === cid) delete d.cards[k];
    save(d); return { deleted: cid };
  }
  async function courseDetail(cid) {
    const d = load(), c = course(d, cid), o = await outline(c);
    const cached = new Set(Object.keys(d.lessons).filter((k) => k.startsWith(`${cid}/`)).map((k) => k.split('/')[1]));
    return { ...c, modules: o.modules.map((m) => ({ ...m, lessons: m.lessons.map((l) => ({ ...l, done: c.done.includes(l.id), ready: cached.has(l.id) })) })) };
  }

  const LANG = { cloud: 'Terraform, Bash or Python', ds: 'Python (pandas) or SQL', behav: 'no' };
  const DEPTH = { beginner: "Keep 'Advanced' short; spend most words on the simple explanation and the example.",
    intermediate: 'Balance all sections.', advanced: "Keep 'In simple words' to 2-3 sentences; spend most words on production trade-offs, scale and architecture." };
  async function lesson(cid, lid, refresh) {
    let d = load();
    const c = course(d, cid), [m, l] = await findLesson(c, lid), key = `${cid}/${lid}`;
    if (!refresh && d.lessons[key]) return { ...d.lessons[key], cached: true };
    const pr = prefsOf(c);
    const [ctx, used] = await brainContext(c);
    let text = await ai(ctx + `Write an accurate lesson on "${l.title}" (module "${m.title}" of the course "${c.title}") for a learner whose goal is `
      + `${pr.target} at ${pr.level} level. Use Markdown with these headings, in order: ## In simple words, ## Going deeper, `
      + '## Advanced: production and architecture, ## Real-world example, '
      + `## Code example (a short ${LANG[c.track] || 'Python'} example in a fenced block; skip if the topic has no code), `
      + '## Common mistakes, ## Practice exercise, ## Interview questions (3 with short model answers), ## Follow-up questions. '
      + `${DEPTH[pr.level_key] || DEPTH.intermediate} Plain, friendly language. Stay on this lesson's topic.`, { maxTokens: 1500 });
    text = normalizeLesson(text);
    if (!lessonOk(text)) throw new StudyError("The AI's lesson was incomplete. Try again.");
    const entry = { course: cid, lesson: lid, title: l.title, markdown: text.trim(), at: ms(), model: 'on-device', prefs: pr.key, used };
    d = load(); d.lessons[key] = entry; history(d, cid, 'lesson', l.title); touch(d); save(d);
    return { ...entry, cached: false };
  }
  async function setDone(cid, lid, done) {
    const d = load(), c = course(d, cid), [, l] = await findLesson(c, lid);
    const s = new Set(c.done); done ? s.add(lid) : s.delete(lid); c.done = [...s].sort();
    if (done) history(d, cid, 'done', l.title);
    touch(d); save(d); return c;
  }

  /* ---- unlimited MCQs (the same contract as career_mcq.generate: validated, de-duplicated, shuffled) ---- */
  const normQ = (s) => String(s).toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();
  const qKey = (s) => { let h = 0; for (const ch of normQ(s)) h = (h * 31 + ch.charCodeAt(0)) >>> 0; return `w${h.toString(16)}`; };
  async function courseMcq(cid, round, { level = 'junior', difficulty = 'medium', count = 5, avoid = [], moduleId = null, weak = false } = {}) {
    const d = load(), c = course(d, cid), mods = (await outline(c)).modules, pr = prefsOf(c);
    const core = mods.filter((x) => !pr.optional.includes(x.id));
    let m = (core.length ? core : mods)[round % (core.length || mods.length)];
    if (moduleId) m = mods.find((x) => x.id === moduleId) || m;
    else if (weak) { const w = weakModules(c, mods); if (w.length) m = mods.find((x) => x.id === w[round % w.length]) || m; }
    if (level === 'auto') level = { beginner: 'fresher', intermediate: 'junior', advanced: 'senior' }[pr.level_key] || 'mid-level';
    const topic = `${m.title} (${c.title})`.slice(0, 120);
    const seen = [...d.seen, ...avoid.map(normQ)];
    const prompt = `Write ${count} ${difficulty} multiple-choice interview questions for a ${level} candidate about the topic "${topic}". `
      + 'Each has exactly 4 different options and one correct answer that is a well-established fact. '
      + (avoid.length ? `Do not repeat these earlier questions: ${avoid.slice(-15).map((a) => a.slice(0, 70)).join(' | ')}. ` : '')
      + 'Answer ONLY with JSON: {"questions": [{"q": question, "options": [4 strings], "answer": index 0-3, "explanation": why}]}.';
    const o = await aiJson(prompt);
    const out = [];
    for (const p of (Array.isArray(o?.questions) ? o.questions : [])) {
      const q = String(p?.q || p?.question || '').trim(), opts = (p?.options || []).map((x) => String(x).trim());
      const ans = Number(p?.answer), why = String(p?.explanation || '').trim();
      if (q.length < 10 || opts.length !== 4 || new Set(opts.map(normQ)).size !== 4 || opts.some((x) => !x) || !(ans >= 0 && ans <= 3) || !why) continue;
      if (seen.includes(normQ(q)) || out.some((x) => normQ(x.q) === normQ(q))) continue;
      const correct = opts[ans], shuffled = [...opts].sort(() => Math.random() - 0.5);
      out.push({ key: qKey(q), q, options: shuffled, answer: shuffled.indexOf(correct), explanation: why, topic, difficulty,
        verification: 'AI-generated: not independently verified', skill: (m.skills || [c.title])[0], module: m.title, module_id: m.id, course_id: cid });
      if (out.length >= count) break;
    }
    if (!out.length) throw new StudyError("The AI's questions didn't pass the checks this time. Scroll again for a new batch.");
    const d2 = load(); d2.seen = [...d2.seen, ...out.map((x) => normQ(x.q))].slice(-3000); save(d2);
    return { questions: out, module: m.title, module_id: m.id, scope: `topic:${topic}` };
  }
  function answer({ skill, question, correct, course_id: courseId, module_id: moduleId }) {
    const d = load(), t = today();
    const s = d.mcq[skill] ??= { answered: 0, correct: 0 };
    s.answered += 1; s.correct += correct ? 1 : 0;
    const c = d.courses[courseId || ''];
    if (c && moduleId) {
      (c.days ??= {})[t] ??= 0;                                    // an active day for the course streak (no time added)
      const m = (c.scores ??= {})[moduleId] ??= { answered: 0, correct: 0 };
      m.answered += 1; m.correct += correct ? 1 : 0;
      if (!correct) c.wrong = [...(c.wrong || []), { ...question, at: t }].slice(-200);
    }
    const key = String(question.key || qKey(question.q || ''));
    const r = d.reviews[key];
    if (r || !correct) d.reviews[key] = { ...(r || { question, skill, course: courseId || null }), ...sc.nextReview(r?.times_right || 0, t, correct, r?.lapses || 0) };
    touch(d); save(d); return { skill, ...s };
  }
  const dueReviews = (d, t, cid = null) => Object.entries(d.reviews)
    .filter(([, v]) => !v.done && v.due && v.due <= t && (cid === null || v.course === cid)).map(([k, v]) => ({ key: k, ...v }));

  /* ---- prep plan ---- */
  async function makePlan({ jd, resume_text: resume = '', interview_date: interview = null, minutes = 60 }) {
    if (String(jd || '').trim().length < 30) throw new StudyError('Paste the job description (at least a few lines).');
    if (interview && !/^\d{4}-\d{2}-\d{2}$/.test(interview)) throw new StudyError("The interview date isn't valid.");
    const tax = await taxonomy(), skills = sc.extractSkills(jd, tax);
    if (!skills.required.length && !skills.preferred.length) throw new StudyError('No known skills were found in this job description. Add the skills section, or pick a ready-made course instead.');
    const resumeSkills = sc.findSkills(resume, tax), d0 = load();
    const g = sc.adaptGaps(sc.gaps(skills, resumeSkills), sc.readiness(sc.gaps(skills, resumeSkills).map((x) => x.skill), d0.mcq, await lessonProgress(d0)).skills);
    const plan = { created: ms(), interview: interview || null, minutes, skills, resume_skills: resumeSkills, gaps: g,
      days: sc.makePlan(g, interview || null, today(), minutes), done: [], jd_title: (String(jd).trim().split(/\r?\n/)[0] || '').slice(0, 100) };
    const d = load();
    if (d.plan) d.plan_history = [...(d.plan_history || []), { ...d.plan, archived_at: ms() }].slice(-10);   // earlier versions are kept
    d.plan = plan; touch(d); save(d); return plan;
  }
  function planItem({ day, index, done = true }) {
    const d = load(), p = d.plan;
    if (!p || !(day >= 1 && day <= p.days.length) || !(index >= 0 && index < p.days[day - 1].items.length)) throw new StudyError("That plan item doesn't exist.");
    const s = new Set(p.done), k = `${day}:${index}`; done ? s.add(k) : s.delete(k); p.done = [...s].sort();
    touch(d); save(d); return p;
  }

  /* ---- STAR stories ---- */
  async function saveStory(st) {
    const title = String(st.title || '').trim().slice(0, 120);
    if (!title) throw new StudyError('Give the story a short title.');
    const comp = new Set((await tracks()).competencies.map((x) => x.id));
    const d = load(), old = d.stories.find((x) => x.id === st.id);
    const s = { id: st.id || id('s-'), title, competencies: (st.competencies || []).filter((x) => comp.has(x)),
      ...Object.fromEntries(STAR.map((f) => [f, String(st[f] || '').trim().slice(0, 2000)])), updated: ms(), coach: st.coach ?? old?.coach ?? null };
    d.stories = [...d.stories.filter((x) => x.id !== s.id), s]; touch(d); save(d); return s;
  }
  async function coach(sid) {
    const d = load(), s = d.stories.find((x) => x.id === sid);
    if (!s) throw new StudyError('Unknown story.');
    if (STAR.reduce((n, f) => n + s[f].length, 0) < 40) throw new StudyError('Write a few sentences in the story first.');
    const o = await aiJson('You are an interview coach. Rate this STAR interview story. Reply with JSON only: '
      + '{"scores": {"situation": 1-5, "task": 1-5, "action": 1-5, "result": 1-5}, "missing": ["..."], "tips": ["..."], '
      + '"improved": "a tighter first-person version, under 180 words, keeping only facts given below"}. Never invent facts.\n\n'
      + STAR.map((f) => `${f[0].toUpperCase()}${f.slice(1)}: ${s[f]}`).join('\n'));
    const scores = {};
    for (const f of STAR) {
      const v = o?.scores?.[f];
      if (typeof v !== 'number' || v < 1 || v > 5) throw new StudyError("The AI's feedback was incomplete. Try again.");
      scores[f] = Math.round(v);
    }
    let fb = { scores, missing: lst(o, 'missing', 5, 300), tips: lst(o, 'tips', 5, 300), improved: String(o.improved || '').trim().slice(0, 3000), at: ms() };
    const have = new Set(STAR.map((f) => s[f]).join(' ').match(NUM) || []);
    if (fb.improved && (fb.improved.match(NUM) || []).some((n) => !have.has(n))) {          // the tighter version may not add numbers
      fb = { ...fb, improved: '', tips: [...fb.tips, "The tighter version was left out because it added numbers that aren't in your story."] };
    }
    await saveStory({ ...s, coach: fb }); return fb;
  }

  /* ---- course journey ---- */
  function setPrefs(cid, { target, level, minutes = 60 }) {
    target = String(target || '').trim().slice(0, 80); level = String(level || '').trim().slice(0, 80);
    if (!target || !level) throw new StudyError('Choose a goal and a level.');
    const d = load(), c = course(d, cid);
    if (c.prefs) c.prefs_history = [...(c.prefs_history || []), { prefs: c.prefs, overview: c.overview || null, at: ms() }].slice(-20);
    c.prefs = { target, level, minutes: Math.max(15, Math.min(240, Math.trunc(minutes) || 60)) };
    c.overview = null; history(d, cid, 'prefs', `${target} · ${LEVELS[level] || level}`); save(d); return c;
  }
  async function buildOverview(cid) {
    const d = load(), c = course(d, cid), pr = prefsOf(c), mods = (await outline(c)).modules;
    const syllabus = mods.map((m) => `- ${m.id}: ${m.title} (${m.lessons.map((l) => l.title).join(', ')})`).join('\n');
    const o = await aiJson(`A learner wants to take the course "${c.title}". Goal: ${pr.target}. Level: ${pr.level}.\nThe course syllabus (module id: title (lessons)):\n${syllabus}\n\n`
      + 'Reply with JSON only: {"summary": "2-3 sentences", "objectives": ["4-6"], "prerequisites": ["3-5"], "optional": ["module ids this learner can treat as optional review"], '
      + '"focus": {"module id": "why it matters for this goal"}, "completion": ["3-4 criteria"]}. Use only modules from the syllabus.');
    if (!o || !String(o.summary || '').trim()) throw new StudyError("The AI's course overview was incomplete. Try again.");
    const ids = new Set(mods.map((m) => m.id));
    const focus = o.focus && typeof o.focus === 'object' ? Object.fromEntries(Object.entries(o.focus).filter(([k, v]) => ids.has(k) && String(v).trim()).map(([k, v]) => [k, String(v).trim().slice(0, 200)])) : {};
    const ov = { summary: String(o.summary).trim().slice(0, 800), objectives: lst(o, 'objectives', 8), prerequisites: lst(o, 'prerequisites', 8),
      completion: lst(o, 'completion', 6).length ? lst(o, 'completion', 6) : ['Finish every core lesson', "Score 70% or more in each module's MCQs", 'Complete the capstone project'],
      optional: (Array.isArray(o.optional) ? o.optional : []).filter((x) => ids.has(x)).slice(0, Math.max(0, ids.size - 2)), focus, at: ms() };
    const d2 = load(); course(d2, cid).overview = ov; save(d2); return ov;
  }
  function addTime(cid, seconds) {
    const d = load(), c = course(d, cid), s = Math.max(0, Math.min(600, Math.trunc(seconds)));
    c.seconds = (c.seconds || 0) + s; const t = today(); (c.days ??= {})[t] = (c.days[t] || 0) + s;
    touch(d); save(d); return { seconds: c.seconds };
  }
  async function setExercise(cid, lid, done) {
    const d = load(), c = course(d, cid), [, l] = await findLesson(c, lid);
    const s = new Set(c.exercises || []); done ? s.add(lid) : s.delete(lid); c.exercises = [...s].sort();
    if (done) history(d, cid, 'exercise', l.title);
    touch(d); save(d); return c;
  }
  function weakModules(c, mods) {
    return mods.map((m) => sc.moduleMastery(m, c.done || [], c.scores || {}))
      .sort((a, b) => a.correct / Math.max(1, a.answered) - b.correct / Math.max(1, b.answered)).filter((r) => r.weak).map((r) => r.module);
  }
  async function dashboard(cid) {
    const d = load(), c = course(d, cid), pr = prefsOf(c), mods = (await outline(c)).modules, t = today();
    const rows = mods.map((m) => sc.moduleMastery(m, c.done || [], c.scores || {}));
    const total = mods.reduce((n, m) => n + m.lessons.length, 0);
    const answered = rows.reduce((n, r) => n + r.answered, 0), correct = rows.reduce((n, r) => n + r.correct, 0);
    const titles = Object.fromEntries(mods.map((m) => [m.id, m.title]));
    const weekAgo = sc.addDays(t, -6);
    const nxt = sc.nextLesson(mods, c.done || [], pr.optional);
    const nl = nxt ? await findLesson(c, nxt) : null;
    const cap = c.capstone;
    return { id: cid, title: c.title, prefs: pr, overview: c.overview || null,
      progress: total ? sc.pyRound(100 * c.done.length / total) : 0, lessons_done: c.done.length, lessons_total: total,
      modules_done: rows.filter((r) => r.lessons_done === r.lessons_total).length, modules_total: mods.length,
      seconds: c.seconds || 0, seconds_week: Object.entries(c.days || {}).filter(([k]) => k >= weekAgo).reduce((n, [, v]) => n + v, 0),
      assessment: { answered, correct, percent: answered ? sc.pyRound(100 * correct / answered) : null },
      exercises_done: (c.exercises || []).length,
      mastery: rows.map((r) => ({ ...r, title: titles[r.module], optional: pr.optional.includes(r.module) })),
      weak: weakModules(c, mods).map((m) => ({ id: m, title: titles[m] })),
      revision: { cards_due: Object.values(d.cards).filter((v) => v.course === cid && !v.done && (v.due || '') <= t).length,
        questions_due: dueReviews(d, t, cid).length, wrong_this_week: (c.wrong || []).filter((w) => (w.at || '') >= weekAgo).length },
      streak: sc.streak([...new Set([...Object.keys(c.days || {}), ...(c.history || []).filter((h) => h.at).map((h) => sc.todayIso(new Date(h.at))),
        ...(c.wrong || []).filter((w) => w.at).map((w) => w.at)])].sort(), t),
      next_lesson: nl ? { id: nxt, title: nl[1].title, module: nl[0].title } : null,
      schedule: sc.courseSchedule(mods, pr.minutes, t, pr.optional, c.done || []),
      capstone: cap ? { title: cap.title, done: cap.milestones.filter((x) => x.done).length, total: cap.milestones.length } : null,
      history: [...(c.history || [])].reverse().slice(0, 30), plan_change: planChange(c, mods) };
  }
  function planChange(c, mods) {
    const hist = c.prefs_history || [];
    if (!hist.length || !c.overview) return null;
    const titles = Object.fromEntries(mods.map((m) => [m.id, m.title]));
    const before = new Set(hist[hist.length - 1].overview?.optional || []), now = new Set(c.overview.optional || []);
    return { previous: hist[hist.length - 1].prefs, now_core: [...before].filter((m) => !now.has(m)).sort().filter((m) => titles[m]).map((m) => titles[m]),
      now_optional: [...now].filter((m) => !before.has(m)).sort().filter((m) => titles[m]).map((m) => titles[m]), kept: (c.done || []).length };
  }
  async function makeCards(cid, lid) {
    const d = load(), c = course(d, cid), [m, l] = await findLesson(c, lid), les = d.lessons[`${cid}/${lid}`];
    if (!les) throw new StudyError('Open the lesson first, then make flashcards from it.');
    const o = await aiJson('Make 6 to 8 flashcards for revising this lesson. Reply with JSON only: {"cards": [{"front": "a short question", "back": "a 1-2 sentence answer"}]}. '
      + `Use only facts from the lesson.\n\n${les.markdown.slice(0, 4000)}`);
    const cards = (Array.isArray(o?.cards) ? o.cards : Array.isArray(o) ? o : []).slice(0, 10)
      .filter((x) => x && String(x.front || '').trim().length >= 5 && String(x.back || '').trim())
      .map((x) => ({ front: String(x.front).trim().slice(0, 300), back: String(x.back).trim().slice(0, 600) }));
    if (cards.length < 3) throw new StudyError('The AI made too few usable flashcards. Try again.');
    const d2 = load(), lk = `${cid}/${lid}`;
    for (const [k, v] of Object.entries(d2.cards)) if (v.lesson === lk) delete d2.cards[k];
    for (const x of cards) d2.cards[id('f-')] = { ...x, course: cid, lesson: lk, module: m.id, title: l.title, due: today(), times_right: 0, done: false };
    history(d2, cid, 'cards', l.title); save(d2); return { cards };
  }
  function dueCards(cid = null, weekly = false) {
    const d = load(), t = today();
    if (weekly) {
      const since = ms() - 7 * 86400000;
      const recent = new Set(Object.entries(d.lessons).filter(([, v]) => (v.at || 0) >= since).map(([k]) => k));
      return { cards: Object.entries(d.cards).filter(([, v]) => recent.has(v.lesson) && (cid === null || v.course === cid)).map(([k, v]) => ({ id: k, ...v })) };
    }
    return { cards: Object.entries(d.cards).filter(([, v]) => !v.done && (v.due || '') <= t && (cid === null || v.course === cid)).map(([k, v]) => ({ id: k, ...v })) };
  }
  function gradeCard(cardId, remembered) {
    const d = load(), v = d.cards[cardId];
    if (!v) throw new StudyError('Unknown flashcard.');
    Object.assign(v, sc.nextReview(remembered ? (v.times_right || 0) : 0, today(), remembered, v.lapses || 0));
    touch(d); save(d); return { id: cardId, ...v };
  }
  async function makeCapstone(cid) {
    const d = load(), c = course(d, cid), pr = prefsOf(c), mods = (await outline(c)).modules;
    const o = await aiJson(`Design one capstone project for the course "${c.title}" (modules: ${mods.map((m) => m.title).join(', ')}) for a learner aiming at ${pr.target}, level ${pr.level}. `
      + 'It must use what the course teaches and be doable on a laptop for free. Reply with JSON only: {"title": "...", "brief": "3-4 sentences", "milestones": ["4-6 concrete steps"], "stretch": ["1-3 optional extras"]}.');
    const msl = lst(o, 'milestones', 6);
    if (!o || !String(o.title || '').trim() || msl.length < 3) throw new StudyError("The AI's capstone project was incomplete. Try again.");
    const cap = { title: String(o.title).trim().slice(0, 120), brief: String(o.brief || '').trim().slice(0, 800), milestones: msl.map((t) => ({ title: t, done: false })), stretch: lst(o, 'stretch', 3) };
    const d2 = load(); course(d2, cid).capstone = cap; history(d2, cid, 'capstone', cap.title); save(d2); return cap;
  }
  function capstoneStep(cid, index, done) {
    const d = load(), cap = d.courses[cid]?.capstone;
    if (!cap || !(index >= 0 && index < cap.milestones.length)) throw new StudyError("That capstone step doesn't exist.");
    cap.milestones[index].done = Boolean(done);
    if (done) history(d, cid, 'capstone step', cap.milestones[index].title);
    touch(d); save(d); return cap;
  }
  async function tutor(cid, lid, question) {
    question = String(question || '').trim();
    if (question.length < 3) throw new StudyError('Type your question.');
    const d = load(), c = course(d, cid), [, l] = await findLesson(c, lid), les = d.lessons[`${cid}/${lid}`]?.markdown || '', pr = prefsOf(c);
    const [ctx] = await brainContext(c);
    const text = String(await ai(ctx + `The learner is studying the lesson "${l.title}" in the course "${c.title}" (goal: ${pr.target}, level: ${pr.level}).\n`
      + (les ? `The lesson:\n${les.slice(0, 4000)}\n\n` : '') + `Their question: ${question.slice(0, 1500)}\n\nAnswer clearly and kindly in Markdown, with a small example if it helps.`, { maxTokens: 900 })).trim();
    if (!text) throw new StudyError("The AI tutor didn't answer. Try again.");
    const d2 = load(); history(d2, cid, 'tutor', l.title); save(d2);
    return { answer: text };
  }
  async function lessonProgress(d) {
    const done = new Set(Object.values(d.courses).flatMap((c) => c.done || []));
    const perSkill = sc.lessonsPerSkill(await tracks(), done);
    for (const c of Object.values(d.courses)) {
      if (c.track) continue;
      for (const m of c.modules) for (const s of m.skills || []) {
        const r = perSkill[s] ??= { done: 0, total: 0 };
        r.total += m.lessons.length; r.done += m.lessons.filter((l) => c.done.includes(l.id)).length;
      }
    }
    return perSkill;
  }
  async function overview() {
    const d = load(), t = await tracks(), td = today();
    const perSkill = await lessonProgress(d);
    const plan = d.plan;
    const target = plan ? plan.gaps.map((g) => g.skill) : Object.keys(d.mcq).sort().slice(0, 12);
    let todayItems = [];
    if (plan) plan.days.forEach((day, i) => { if (day.date === td) todayItems = day.items.map((it, j) => ({ ...it, key: `${i + 1}:${j}`, done: plan.done.includes(`${i + 1}:${j}`) })); });
    const courses = [];
    for (const c of Object.values(d.courses)) {
      const o = await outline(c);
      courses.push({ id: c.id, title: c.title, track: c.track || null, done: c.done.length, total: o.modules.reduce((n, m) => n + m.lessons.length, 0) });
    }
    const comp = Object.fromEntries(t.competencies.map((x) => [x.id, 0]));
    for (const s of d.stories) for (const k of s.competencies || []) comp[k] = (comp[k] || 0) + 1;
    return { courses, plan, plan_history: d.plan_history || [], readiness: sc.readiness(target, d.mcq, perSkill), today: todayItems, streak: sc.streak(d.activity, td),
      reviews_due: dueReviews(d, td).length, stories: d.stories.length, competencies: comp };
  }


  /* ---- 7.2.0-dev.2: Personal AI Brain opt-in (browser memories only; off by default) ---- */
  async function brainSources() {
    const mems = (await memories()) || [];
    return { goals: mems.filter((m) => m.category === 'goal').map((m) => ({ id: m.id, text: String(m.content || m.text || '').slice(0, 200) })),
      memories: mems.filter((m) => m.category !== 'goal').slice(0, 60).map((m) => ({ id: m.id, text: String(m.content || m.text || '').slice(0, 200), category: m.category || 'general' })),
      projects: [] };
  }
  function setBrain(cid, body) {
    const d = load(), c = course(d, cid);
    c.brain = { enabled: Boolean(body.enabled), memories: (body.memories || []).slice(0, 20), projects: [] };
    history(d, cid, 'brain', `Personal context ${c.brain.enabled ? 'on' : 'off'}`); save(d); return c.brain;
  }
  async function brainContext(c) {
    const b = c.brain || {};
    if (!b.enabled) return ['', []];
    const mems = (await memories()) || [];
    const lines = [], used = [];
    for (const id of b.memories || []) {
      const m = mems.find((x) => String(x.id) === String(id));
      if (!m) continue;
      const text = String(m.content || m.text || '');
      lines.push(`- ${m.category === 'goal' ? 'Goal' : 'About me'}: ${text.slice(0, 200)}`);
      used.push(`${m.category === 'goal' ? 'Goal: ' : 'Note: '}${text.slice(0, 60)}`);
    }
    if (!lines.length) return ['', []];
    return [`Learner context, shared by the learner for this course only (use it to choose examples; do not repeat it back):\n${lines.join('\n').slice(0, 1200)}\n\n`, used];
  }

  /* ---- model quality check (3 fixed probes, cached per model) ---- */
  const PROBES = {
    json_mcq: 'Write 1 multiple-choice question about Python lists. Answer ONLY with JSON: {"questions": [{"q": question, "options": [4 strings], "answer": index 0-3, "explanation": why}]}.',
    lesson_sections: 'Write a very short lesson on HTTP caching with exactly these Markdown headings: ## In simple words, ## Going deeper, ## Common mistakes, ## Interview questions.',
    fact: 'Answer with only the number: what is 17 multiplied by 23?',
  };
  function probeOk(name, text) {
    if (name === 'json_mcq') {
      let o = null;
      try { o = JSON.parse(text); } catch { const m = String(text).match(/\{[\s\S]*\}/); try { o = m ? JSON.parse(m[0]) : null; } catch { o = null; } }
      const q = Array.isArray(o?.questions) ? o.questions[0] : null;
      return Boolean(q && Array.isArray(q.options) && q.options.length === 4 && Number.isInteger(q.answer) && q.answer >= 0 && q.answer <= 3 && String(q.q || '').trim());
    }
    if (name === 'lesson_sections') {
      const heads = [...normalizeLesson(text).matchAll(/^#{1,4}\s+(.+)$/gm)].map((m) => m[1].toLowerCase()).join(' ');
      return ['in simple words', 'going deeper', 'common mistakes', 'interview questions'].filter((x) => heads.includes(x)).length >= 3;
    }
    return (String(text).match(/\d+/g) || []).includes('391');
  }
  async function qualityCheck(modelName) {
    const probes = {}, seconds = {};
    for (const [k, prompt] of Object.entries(PROBES)) {
      const t = Date.now();
      probes[k] = probeOk(k, await ai(prompt, { json: k === 'json_mcq', maxTokens: 500 }));
      seconds[k] = Math.round((Date.now() - t) / 100) / 10;
    }
    const verdict = sc.qualityVerdict(probes);
    const r = { model: modelName || 'on-device', verdict, probes, seconds, at: ms(), advice: {
      good: 'This model handles Study well.',
      weak: 'This model sometimes misses the format Study needs. Lessons and questions may need a retry; a larger model gives better results.',
      poor: "This model struggles with Study's lessons and questions. A larger model gives better results." }[verdict] };
    const d = load(); (d.quality ??= {})[r.model] = r; save(d); return r;
  }

  /* ---- resume tailoring (nothing stored) ---- */
  function resumeBullets(text) {
    const lines = String(text || '').split(/\r?\n/).map((l) => l.trimEnd());
    let b = lines.map((l) => l.match(/^\s*(?:[-*•●▪◦]|\d+[.)])\s+(.+)$/)).filter(Boolean).map((m) => m[1].trim());
    if (!b.length) b = lines.filter((l) => l.trim().length > 40).flatMap((l) => l.trim().split(/(?<=[.;])\s+/)).map((x) => x.trim()).filter((x) => x.length > 25);
    return b.filter((x) => x.length >= 15).map((x) => x.slice(0, 400)).slice(0, 60);
  }
  async function tailor({ jd, resume_text: resume = '' }) {
    if (String(jd || '').trim().length < 30) throw new StudyError('Paste the job description (at least a few lines).');
    const bullets = resumeBullets(resume);
    if (!bullets.length) throw new StudyError('No resume bullet points were found. Paste your resume text (each achievement on its own line).');
    const tax = await taxonomy(), js = sc.extractSkills(jd, tax), jdAll = [...js.required, ...js.preferred];
    const pick = bullets.map((b, i) => [-sc.findSkills(b, tax).filter((x) => jdAll.includes(x)).length, i]).sort((a, b) => a[0] - b[0] || a[1] - b[1]).slice(0, 8).map((x) => x[1]);
    const o = await aiJson('Rewrite these resume bullet points so they speak to the job below. Rules: keep every fact exactly as given; never add a number, '
      + 'employer, tool or technology that is not in the bullet or the resume; start with a strong verb; one line each. '
      + `Reply with JSON only: {"bullets": [{"i": number, "text": "rewritten bullet"}]}.\n\nJob skills: ${jdAll.join(', ') || 'see the job text'}\nJob description:\n${String(jd).slice(0, 3000)}\n\nBullets:\n`
      + pick.map((i, k) => `${k}. ${bullets[i]}`).join('\n'));
    const rows = (Array.isArray(o?.bullets) ? o.bullets : []).filter((x) => x && typeof x === 'object' && String(x.text || '').trim());
    let idx = rows.map((x) => x.i);
    if (rows.length && idx.every((i) => Number.isInteger(i))) { if (Math.min(...idx) >= 1 && Math.max(...idx) <= pick.length && !idx.includes(0)) idx = idx.map((i) => i - 1); }
    else idx = rows.map((_, k) => k);                                // models number lists their own way: 0-based, 1-based or not at all
    const got = {};
    rows.forEach((x, k) => { const i = idx[k]; if (Number.isInteger(i) && i >= 0 && i < pick.length && !(i in got)) got[i] = String(x.text).trim().slice(0, 400); });
    const out = pick.map((i, k) => {
      const nw = got[k], g = nw ? sc.factGuard(resume, nw, tax) : null;
      const ok = Boolean(nw && g.ok);
      return { original: bullets[i], rewritten: ok ? nw : null,
        rejected: ok ? null : !nw ? "The AI didn't rewrite this one." : `Left out: it added ${[...g.new_numbers, ...g.new_skills].join(', ')}, which isn't in your resume.`,
        skills: sc.findSkills(nw || bullets[i], tax).filter((x) => jdAll.includes(x)) };
    });
    const covered = [...new Set(bullets.flatMap((b) => sc.findSkills(b, tax)).filter((x) => jdAll.includes(x)))].sort();
    return { bullets: out, job_skills: jdAll, covered, missing: jdAll.filter((x) => !covered.includes(x)), note: 'Only facts already in your resume are allowed; nothing was saved or sent anywhere.' };
  }

  /* ---- mock interview ---- */
  async function mockStart({ count = 6, skills = [] }) {
    const d = load();
    let sk = (skills || []).filter(Boolean);
    if (!sk.length) sk = d.plan ? d.plan.gaps.map((g) => g.skill) : Object.entries(d.mcq).sort((a, b) => a[1].correct / Math.max(1, a[1].answered) - b[1].correct / Math.max(1, b[1].answered)).map((x) => x[0]);
    sk = sk.slice(0, 8);
    const plan = sc.mockPlan(sk.slice(0, 6), (await tracks()).competencies, count);
    const tech = plan.filter((q) => q.kind === 'technical').map((q) => q.skill);
    const written = {};
    if (tech.length) {
      const o = await aiJson(`Write one realistic interview question for a mid-level candidate for each of these skills: ${tech.join(', ')}. Each question should need a 1-2 minute spoken answer. Reply with JSON only: {"questions": [{"skill": "skill name", "question": "..."}]}.`);
      for (const x of (Array.isArray(o?.questions) ? o.questions : [])) if (x && tech.includes(x.skill) && String(x.question || '').trim() && !written[x.skill]) written[x.skill] = String(x.question).trim().slice(0, 300);
    }
    const s = { id: id('m-'), created: ms(), source: d.plan ? 'plan' : 'practice', job: d.plan?.jd_title || null, finished: false, summary: null,
      questions: plan.map((q, i) => ({ i, ...q, question: q.kind === 'behavioural' ? q.question : (written[q.skill] || `Explain ${q.skill} and describe how you have used it in a real project.`), answer: null, seconds: null, feedback: null })) };
    const d2 = load(); d2.mocks = [...(d2.mocks || []), s].slice(-20); touch(d2); save(d2); return s;
  }
  const mockOf = (d, sid) => { const s = (d.mocks || []).find((m) => m.id === sid); if (!s) throw new StudyError('Unknown interview session.'); return s; };
  async function mockAnswer(sid, { i, answer, seconds = 0 }) {
    answer = String(answer || '').trim().slice(0, 4000);
    if (answer.length < 3) throw new StudyError('Say or type your answer first.');
    const q = mockOf(load(), sid).questions[i];
    if (!q) throw new StudyError('Unknown question.');
    const about = q.kind === 'technical' ? `the skill ${q.skill}` : 'behavioural (STAR) answers';
    const prompt = `You are a fair interviewer. Score the candidate's answer to: "${q.question}" (about ${about}). Reply with JSON only: {"scores": {"relevance": 1-5, "depth": 1-5, "structure": 1-5}, "evidence": ["short exact quotes from the answer that support the scores"], "tips": ["1-3 concrete tips"]}. Judge only what the candidate said; never assume experience they did not mention.\n\nAnswer:\n${answer}`;
    let fb = null;
    for (let k = 0; k < 2 && !fb; k++) { try { fb = sc.checkFeedback(await aiJson(prompt), answer); } catch (e) { if (k) throw e; } }
    const d = load(), s = mockOf(d, sid);
    Object.assign(s.questions[i], { answer, seconds: Math.max(0, Math.min(3600, Math.trunc(seconds) || 0)), feedback: fb });
    touch(d); save(d);
    return { feedback: fb, note: fb ? null : "The AI's scores weren't usable this time; your answer is saved. Try scoring again." };
  }
  function mockFinish(sid) {
    const d = load(), s = mockOf(d, sid);
    s.summary = sc.mockSummary(s.questions.map((q) => ({ feedback: q.feedback }))); s.finished = true; save(d); return s;
  }

  /** Restores an export made by 'GET /export' (this browser or another): replaces this browser's Study data after the
   *  user confirmed. The current data is kept under '<key>.before-import-<time>' first. */
  function importData(body) {
    const d = body?.data;
    if (body?.format !== 'abhify-study-export-1' || !d || typeof d !== 'object' || typeof d.courses !== 'object') throw new StudyError("This file isn't an Abhify Study export.");
    if ((d.schema || 0) > SCHEMA) throw new StudyError('This export is from a newer Abhify.AI. Update the app first.');
    const before = storage.getItem(KEY);
    if (before) storage.setItem(`${KEY}.before-import-${ms()}`, before);
    save({ ...EMPTY(), ...d });
    const n = load();
    return { courses: Object.keys(n.courses).length, stories: n.stories.length, cards: Object.keys(n.cards).length };
  }

  /** The /learn/* routes, answered locally. Same paths, bodies and results as main.py. */
  async function handle(method, path, body = {}) {
    const [p, qs] = path.split('?');
    const q = new URLSearchParams(qs || '');
    const seg = p.split('/').filter(Boolean);           // ['study', ...]
    const r = `${method} /${seg.slice(1).map((s, i) => (seg[1] === 'courses' && i === 1) || (seg[1] === 'stories' && i === 1 && s !== 'coach') || (seg[1] === 'cards' && i === 1) || (seg[1] === 'mock' && i === 1) ? ':id' : s).join('/')}`;
    const arg = seg[2];
    switch (r) {
      case 'GET /tracks': return tracks();
      case 'GET /state': return overview();
      case 'GET /prefs/options': return { targets: TARGETS, levels: LEVELS };
      case 'GET /courses/:id': return courseDetail(arg);
      case 'POST /courses': return body.track ? createCourse({ trackId: body.track }) : customCourse(body.topic);
      case 'DELETE /courses/:id': return deleteCourse(arg);
      case 'PUT /courses/:id/prefs': return setPrefs(arg, body);
      case 'POST /courses/:id/overview': return buildOverview(arg);
      case 'GET /courses/:id/dashboard': return dashboard(arg);
      case 'POST /courses/:id/time': return addTime(arg, body.seconds);
      case 'POST /courses/:id/exercise': return setExercise(arg, body.lesson_id, body.done !== false);
      case 'POST /courses/:id/cards': return makeCards(arg, body.lesson_id);
      case 'POST /courses/:id/capstone': return makeCapstone(arg);
      case 'POST /courses/:id/capstone/step': return capstoneStep(arg, body.index, body.done !== false);
      case 'POST /courses/:id/tutor': return tutor(arg, body.lesson_id, body.question);
      case 'POST /lesson': return lesson(body.course_id, body.lesson_id, body.refresh);
      case 'POST /lesson/done': return setDone(body.course_id, body.lesson_id, body.done !== false);
      case 'POST /mcq': return courseMcq(body.course_id, body.round || 0, { level: body.level, difficulty: body.difficulty, count: body.count || 5,
        avoid: body.avoid || [], moduleId: body.module_id, weak: body.weak });
      case 'POST /mcq/answer': return answer(body);
      case 'GET /review': { const d = load(); return { due: dueReviews(d, today()) }; }
      case 'POST /plan': return makePlan(body);
      case 'DELETE /plan': { const d = load(); d.plan = null; save(d); return { plan: null }; }
      case 'POST /plan/item': return planItem(body);
      case 'GET /stories': return { stories: load().stories, competencies: (await tracks()).competencies };
      case 'POST /stories': return saveStory(body);
      case 'DELETE /stories/:id': { const d = load(), n = d.stories.length; d.stories = d.stories.filter((x) => x.id !== arg); if (d.stories.length === n) throw new StudyError('Unknown story.', 404); save(d); return { deleted: arg }; }
      case 'POST /stories/:id/coach': return coach(arg);
      case 'GET /cards': return dueCards(q.get('course_id'), q.get('weekly') === 'true');
      case 'POST /cards/:id': return gradeCard(arg, Boolean(body.remembered));
      case 'GET /brain/sources': return brainSources();
      case 'PUT /courses/:id/brain': return setBrain(arg, body);
      case 'GET /quality': return { result: (load().quality || {})[q.get('model') || 'on-device'] || null };
      case 'POST /quality': return qualityCheck(body.model_label);
      case 'POST /resume/tailor': return tailor(body);
      case 'POST /mock': return mockStart(body);
      case 'GET /mock': return { sessions: [...(load().mocks || [])].reverse().map((m) => ({ id: m.id, created: m.created, job: m.job, questions: m.questions.length,
        answered: m.questions.filter((x) => x.answer).length, summary: m.summary })) };
      case 'GET /mock/:id': return mockOf(load(), arg);
      case 'POST /mock/:id/answer': return mockAnswer(arg, body);
      case 'POST /mock/:id/finish': return mockFinish(arg);
      case 'DELETE /mock/:id': { const d = load(), n = (d.mocks || []).length; d.mocks = (d.mocks || []).filter((m) => m.id !== arg); if (d.mocks.length === n) throw new StudyError('Unknown interview session.', 404); save(d); return { deleted: arg }; }
      case 'GET /export': return { format: 'abhify-study-export-1', exported_at: new Date(ms()).toISOString(), data: load() };
      case 'POST /import': return importData(body);
      default: throw new StudyError('Not available in this version.', 404);
    }
  }
  return { handle, load, prefsOf };
}
