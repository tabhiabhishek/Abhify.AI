/* 7.2 Study (Web, no backend): frontend/study-local.js scenarios with an in-memory storage and a fake AI.
   Usage: node study_local_harness.js [frontend dir]. Prints {"passed", "failed"}; exit 1 on failure. */
'use strict';
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');
const FRONT = process.argv[2] || path.resolve(__dirname, '..', '..', '..', 'frontend');
let passed = 0; const failed = [];
function ok(name, cond, extra) { if (cond) passed++; else failed.push({ name, extra: extra === undefined ? '' : JSON.stringify(extra).slice(0, 300) }); }

const LESSON = ['In simple words', 'Going deeper', 'Advanced: production and architecture', 'Common mistakes', 'Practice exercise']
  .map((h) => `## ${h}\n${'Text about the topic. '.repeat(8)}`).join('\n');
let n = 0;
let ai = async function aiImpl(prompt, opts = {}) {
  if (!opts.json) return prompt.includes('Write an accurate lesson') ? LESSON : 'A kind tutor answer.';
  if (prompt.includes('syllabus')) return JSON.stringify({ summary: 'Prepares you.', objectives: ['x'], optional: ['aiml-math', 'nope'], focus: { 'aiml-llm': 'core', zz: 'no' } });
  if (prompt.includes('flashcards')) return JSON.stringify({ cards: [1, 2, 3, 4, 5, 6].map((i) => ({ front: `Question ${i}?`, back: `Answer ${i}.` })) });
  if (prompt.includes('capstone')) return JSON.stringify({ title: 'RAG app', brief: 'Build.', milestones: ['a', 'b', 'c', 'd'] });
  if (prompt.includes('STAR')) return JSON.stringify({ scores: { situation: 4, task: 3, action: 4, result: 2 }, missing: ['numbers'], improved: 'I led it.' });
  if (prompt.includes('multiple-choice')) {
    n++;
    return 'Sure! ' + JSON.stringify({ questions: [0, 1, 2, 3, 4, 5].map((i) => ({ q: `Batch ${n} question number ${i} about the topic?`, options: ['a1', 'b2', 'c3', 'd4'], answer: 2, explanation: 'Because.' }))
      .concat([{ q: 'bad', options: ['x'], answer: 9, explanation: '' }]) });
  }
  return '{}';
};

(async () => {
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'abhify-sl-'));
  let mod;
  try {
    fs.copyFileSync(path.join(FRONT, 'study-core.js'), path.join(tmp, 'study-core.mjs'));
    fs.writeFileSync(path.join(tmp, 'study-local.mjs'), fs.readFileSync(path.join(FRONT, 'study-local.js'), 'utf8').replace("'./study-core.js'", "'./study-core.mjs'"));
    mod = await import(pathToFileURL(path.join(tmp, 'study-local.mjs')).href);
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  const mem = new Map();
  const storage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, String(v)) };
  const loadJson = async (f) => JSON.parse(fs.readFileSync(path.join(FRONT, f), 'utf8'));
  const L = mod.createLocal({ storage, ai: (p2, o) => ai(p2, o), loadJson });
  const h = (m, p, b) => L.handle(m, p, b);
  const err = async (fn) => { try { await fn(); return null; } catch (e) { return e; } };
  const todayIso = new Date().toISOString().slice(0, 10);

  const c = await h('POST', '/learn/courses', { track: 'aiml' });
  ok('course', c.id === 'c-aiml');
  ok('one per track', (await h('POST', '/learn/courses', { track: 'aiml' })).id === 'c-aiml');
  ok('unknown track', (await err(() => h('POST', '/learn/courses', { track: 'zz' })))?.status === 400);
  await h('PUT', '/learn/courses/c-aiml/prefs', { target: 'LLM / GenAI Engineer', level: 'advanced', minutes: 60 });
  const ov = await h('POST', '/learn/courses/c-aiml/overview', {});
  ok('overview keeps only real modules', JSON.stringify(ov.optional) === '["aiml-math"]' && Object.keys(ov.focus).join() === 'aiml-llm', ov);
  let dash = await h('GET', '/learn/courses/c-aiml/dashboard');
  ok('nothing invented', dash.progress === 0 && dash.assessment.percent === null && dash.seconds === 0, dash.assessment);
  ok('optional last', dash.next_lesson.id.startsWith('aiml-ml'), dash.next_lesson);
  const lid = dash.next_lesson.id;
  const les = await h('POST', '/learn/lesson', { course_id: 'c-aiml', lesson_id: lid });
  ok('lesson', !les.cached && les.markdown.includes('## In simple words'));
  ok('lesson cached', (await h('POST', '/learn/lesson', { course_id: 'c-aiml', lesson_id: lid })).cached === true);
  await h('POST', '/learn/lesson/done', { course_id: 'c-aiml', lesson_id: lid });
  await h('POST', '/learn/courses/c-aiml/exercise', { course_id: 'c-aiml', lesson_id: lid });
  await h('POST', '/learn/courses/c-aiml/time', { seconds: 300 });
  await h('POST', '/learn/courses/c-aiml/time', { seconds: 9999 });                    // capped at 600 per report
  const b1 = await h('POST', '/learn/mcq', { course_id: 'c-aiml', round: 0, level: 'auto' });
  ok('mcq batch validated', b1.questions.length === 5 && b1.questions.every((q) => q.options[q.answer] === 'c3'), b1.questions.length);
  ok('mcq skips optional', b1.module_id !== 'aiml-math');
  const b2 = await h('POST', '/learn/mcq', { course_id: 'c-aiml', round: 1, avoid: b1.questions.map((q) => q.q) });
  ok('next module', b2.module_id !== b1.module_id);
  const q = b1.questions[0];
  for (const right of [false, false, false, true]) await h('POST', '/learn/mcq/answer', { skill: q.skill, question: q, correct: right, course_id: 'c-aiml', module_id: q.module_id });
  dash = await h('GET', '/learn/courses/c-aiml/dashboard');
  ok('dashboard', dash.lessons_done === 1 && dash.exercises_done === 1 && dash.seconds === 900 && dash.assessment.percent === 25, dash);
  ok('weak topic', dash.weak[0]?.id === q.module_id && dash.revision.wrong_this_week === 3 && dash.streak === 1, dash.weak);
  ok('weak practice', (await h('POST', '/learn/mcq', { course_id: 'c-aiml', round: 0, weak: true })).module_id === q.module_id);
  await h('POST', '/learn/courses/c-aiml/cards', { course_id: 'c-aiml', lesson_id: lid });
  const due = (await h('GET', '/learn/cards?course_id=c-aiml')).cards;
  ok('cards due', due.length === 6);
  const g = await h('POST', `/learn/cards/${due[0].id}`, { remembered: true });
  ok('card scheduled', g.times_right === 1 && g.due > todayIso, g);
  ok('one less due', (await h('GET', '/learn/cards?course_id=c-aiml')).cards.length === 5);
  ok('weekly revision', (await h('GET', '/learn/cards?course_id=c-aiml&weekly=true')).cards.length === 6);
  ok('cards need lesson', (await err(() => h('POST', '/learn/courses/c-aiml/cards', { lesson_id: 'aiml-ops-1' })))?.status === 400);
  await h('POST', '/learn/courses/c-aiml/capstone', {});
  await h('POST', '/learn/courses/c-aiml/capstone/step', { index: 1 });
  ok('tutor', (await h('POST', '/learn/courses/c-aiml/tutor', { lesson_id: lid, question: 'Why?' })).answer.length > 0);
  await h('PUT', '/learn/courses/c-aiml/prefs', { target: 'My own goal', level: 'Somewhere between', minutes: 30 });
  dash = await h('GET', '/learn/courses/c-aiml/dashboard');
  ok('prefs change keeps progress', dash.lessons_done === 1 && dash.assessment.answered === 4 && dash.capstone.done === 1 && dash.prefs.level_key === 'custom' && dash.overview === null, dash.prefs);

  const jd = 'Machine Learning Engineer\nRequirements:\n- 3+ years of Python and SQL\n- Strong knowledge of machine learning\nNice to have:\n- Docker, Kubernetes';
  const plan = await h('POST', '/learn/plan', { jd, resume_text: 'Python and SQL', minutes: 60 });
  ok('plan', plan.days.length === 14 && plan.gaps[0].skill === 'Machine Learning', plan.gaps);
  ok('plan item', (await h('POST', '/learn/plan/item', { day: 1, index: 0 })).done[0] === '1:0');
  const st = await h('POST', '/learn/stories', { title: 'Migration', competencies: ['leadership', 'fake'], situation: 'Old slow system here.', task: 'Move it.', action: 'I led it.', result: 'Faster.' });
  ok('story', JSON.stringify(st.competencies) === '["leadership"]');
  const fb = await h('POST', `/learn/stories/${st.id}/coach`, {});
  ok('coach', fb.scores.result === 2);
  await h('POST', '/learn/stories', { id: st.id, title: 'Migration 2' });
  ok('edit keeps coach', (await h('GET', '/learn/stories')).stories[0].coach?.scores?.action === 4);
  const state = await h('GET', '/learn/state');
  ok('state', state.courses.length === 1 && state.stories === 1 && state.today[0].done === true && state.readiness.level === 'Not ready', state.readiness);
  ok('unknown route', (await err(() => h('GET', '/learn/nope')))?.status === 404);
  // ---- audit: integrity, versions, safeguards
  const plan2 = await h('POST', '/learn/plan', { jd, resume_text: 'Python and SQL', minutes: 60 });
  ok('plan versions kept', (await h('GET', '/learn/state')).plan_history.length === 1 && plan2.created >= plan.created);
  const st2 = await h('POST', '/learn/stories', { title: 'Numbers', situation: 'The report took 3 hours to run every week.', task: 'Make it faster.', action: 'I added indexes.', result: 'Faster.' });
  const realAi = ai;
  ai = async (p2, o) => (p2.includes('STAR') ? JSON.stringify({ scores: { situation: 4, task: 4, action: 4, result: 3 }, improved: 'I cut it by 95% for 12 teams.' }) : realAi(p2, o));
  const fb2 = await h('POST', `/learn/stories/${st2.id}/coach`, {});
  ok('STAR guard drops invented numbers', fb2.improved === '' && fb2.tips.some((t) => t.includes('added numbers')), fb2);
  ai = realAi;
  const qq = { key: 'lap1', q: 'Lapse question?' };
  await h('POST', '/learn/mcq/answer', { skill: 'SQL', question: qq, correct: false });
  await h('POST', '/learn/mcq/answer', { skill: 'SQL', question: qq, correct: false });
  const rv = JSON.parse(mem.get('abhify.study.v1')).reviews.lap1;
  ok('repeated miss comes back the same day', rv.lapses === 2 && rv.due === JSON.parse(mem.get('abhify.study.v1')).activity.slice(-1)[0], rv);
  const exp = await h('GET', '/learn/export');
  ok('export', exp.format === 'abhify-study-export-1' && exp.data.courses['c-aiml']);
  ok('import refuses other files', (await err(() => h('POST', '/learn/import', { format: 'x' })))?.status === 400);
  await h('DELETE', '/learn/courses/c-aiml');
  ok('delete course removes its cards', Object.keys(JSON.parse(mem.get('abhify.study.v1')).cards).length === 0);
  // ---- dev.2: mock interview, tailoring, brain, quality
  const realAi2 = ai;
  ai = async (p2, o) => {
    if (p2.includes('realistic interview question')) return JSON.stringify({ questions: [{ skill: 'Machine Learning', question: 'How do you avoid overfitting?' }] });
    if (p2.includes('fair interviewer')) return JSON.stringify({ scores: { relevance: 4, depth: 3, structure: 4 }, evidence: ['use cross validation', 'I invented GPT'], tips: ['Add numbers.'] });
    if (p2.includes('Rewrite these resume bullet points')) return JSON.stringify({ bullets: [{ i: 0, text: 'Designed Python REST APIs for 10k users' }, { i: 1, text: 'Cut runtime 45% with Kubernetes' }] });
    if (p2.includes('17 multiplied by 23')) return '391';
    if (p2.includes('HTTP caching')) return '## In simple words\nx\n## Going deeper\ny\n## Common mistakes\nz';
    if (p2.includes('Python lists')) return JSON.stringify({ questions: [{ q: 'Q?', options: ['a', 'b', 'c', 'd'], answer: 0, explanation: 'e' }] });
    return realAi2(p2, o);
  };
  const ms1 = await h('POST', '/learn/mock', { count: 4 });
  ok('mock plan alternates', ms1.questions.map((x) => x.kind).join() === 'technical,behavioural,technical,behavioural', ms1.questions);
  const fbm = await h('POST', `/learn/mock/${ms1.id}/answer`, { i: 0, answer: 'I use cross validation and regularisation.', seconds: 60 });
  ok('mock feedback evidence only quotes the answer', JSON.stringify(fbm.feedback.evidence) === '["use cross validation"]', fbm);
  const fin = await h('POST', `/learn/mock/${ms1.id}/finish`, {});
  ok('mock summary', fin.summary.answered === 1 && fin.summary.percent === 73, fin.summary);
  ok('mock list', (await h('GET', '/learn/mock')).sessions[0].answered === 1);
  const tl = await h('POST', '/learn/resume/tailor', { jd: 'Backend Engineer\nRequirements:\n- Python, SQL, Kubernetes and AWS experience', resume_text: '- Built REST APIs in Python serving 10k users\n- Cut report runtime by 30% using SQL indexes' });
  ok('tailoring keeps facts, rejects additions', tl.bullets.some((x) => x.rewritten === 'Designed Python REST APIs for 10k users') && tl.bullets.some((x) => x.rejected && x.rejected.includes('45')), tl.bullets);
  const qc = await h('POST', '/learn/quality', {});
  ok('quality verdict', qc.verdict === 'good' && (await h('GET', '/learn/quality')).result.verdict === 'good', qc);
  ai = realAi2;
  const r = await h('POST', '/learn/import', exp);
  ok('import restores and keeps the previous data', r.courses === 1 && [...mem.keys()].some((k) => k.startsWith('abhify.study.v1.before-import-')));
  // corrupt storage is kept, never overwritten
  mem.set('abhify.study.v1', '{"courses": {"c-x":');
  ok('corrupt storage starts empty', (await h('GET', '/learn/state')).courses.length === 0);
  ok('corrupt storage kept aside', [...mem.keys()].some((k) => k.startsWith('abhify.study.v1.corrupt-')));
  mem.set('abhify.study.v1', JSON.stringify({ schema: 99, courses: {} }));
  ok('newer schema refused, untouched', (await err(() => h('GET', '/learn/state')))?.message.includes('newer') && JSON.parse(mem.get('abhify.study.v1')).schema === 99);
  mem.delete('abhify.study.v1');
  const full = mod.createLocal({ storage: { getItem: () => null, setItem: () => { throw new Error('QuotaExceededError'); } }, ai, loadJson });
  ok('quota full gives a clear message', /storage is full/.test((await err(() => full.handle('POST', '/learn/courses', { track: 'swe' })))?.message || ''));
  const raw = ['**In simple words**', '---', 'x '.repeat(250), '**Going deeper**', 'y', '1. Common mistakes', 'z', 'Interview questions', '===', 'q'].join('\n');
  ok('lesson check', mod.lessonOk(mod.normalizeLesson(raw)) && !mod.lessonOk(`Sure! ${'x '.repeat(300)}`));
  console.log(JSON.stringify({ passed, failed }));
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.log(JSON.stringify({ passed, failed: [{ name: 'crash', extra: String(e && e.stack) }] })); process.exit(1); });
