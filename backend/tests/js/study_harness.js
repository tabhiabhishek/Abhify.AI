/* 7.2 Study: frontend/study-core.js against backend/tests/data/study_cases.json (the Python reference's output).
   Usage: node study_harness.js <study_cases.json> <skills_taxonomy.json> [frontend dir]. Prints {"passed", "failed"}. */
'use strict';
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const [casesPath, taxPath, frontArg] = process.argv.slice(2);
const FRONT = frontArg || path.resolve(__dirname, '..', '..', '..', 'frontend');
const C = JSON.parse(fs.readFileSync(casesPath, 'utf8'));
const TAX = JSON.parse(fs.readFileSync(taxPath, 'utf8')).skills;
let passed = 0; const failed = [];
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function check(name, got, want) { if (eq(got, want)) passed++; else failed.push({ name, got: JSON.stringify(got).slice(0, 400), want: JSON.stringify(want).slice(0, 400) }); }

(async () => {
  // ES module served as .js; Node 22.1 needs .mjs to load it that way (a temporary copy).
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'abhify-study-'));
  let sc;
  try {
    fs.copyFileSync(path.join(FRONT, 'study-core.js'), path.join(tmp, 'study-core.mjs'));
    sc = await import(pathToFileURL(path.join(tmp, 'study-core.mjs')).href);
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  check('taxonomy size', TAX.length, C.taxonomy_size);
  for (const [k, c] of Object.entries(C.extract)) check(`extract ${k}`, sc.extractSkills(c.jd, TAX), c.expect);
  check('gaps', sc.gaps(C.extract[C.gaps.jd].expect, C.gaps.resume_skills), C.gaps.expect);
  const p = C.plan;
  check('plan', sc.makePlan(p.gaps, p.interview, p.today, p.minutes), p.expect);
  check('plan no date', sc.makePlan(p.gaps, null, p.today, 60).length, p.no_date_days);
  check('plan past date', sc.makePlan(p.gaps, '2026-11-01', p.today, 60).length, p.past_date_days);
  for (const r of C.readiness) {
    check(`score ${r.answered}/${r.correct}`, sc.skillScore(r.answered, r.correct, r.lessons_done, r.lessons_total), r.score);
    check(`level ${r.score}`, sc.level(r.score), r.level);
  }
  const o = C.readiness_overall;
  check('readiness', sc.readiness(o.skills, o.mcq, o.lessons), o.expect);
  for (const r of C.review) check(`review ${r.times_right} ${r.correct} ${r.lapses}`, sc.nextReview(r.times_right, r.answered_on, r.correct, r.lapses), r.expect);
  check('adapt gaps', sc.adaptGaps(C.adapt.gaps, C.adapt.rows), C.adapt.expect);
  for (const r of C.streak) check(`streak ${r.days}`, sc.streak(r.days, r.today), r.expect);
  C.schedule.forEach((c, i) => check(`schedule ${i}`, sc.courseSchedule(c.modules, c.minutes, c.start, c.optional, c.done), c.expect));
  C.mastery.forEach((c, i) => check(`mastery ${i}`, sc.moduleMastery(c.module, c.done, c.stats), c.expect));
  C.next_lesson.forEach((c, i) => check(`next ${i}`, sc.nextLesson(c.modules, c.done, c.optional), c.expect));
  C.fact_guard.forEach((c, i) => check(`fact_guard ${i}`, sc.factGuard(c.source, c.rewritten, TAX), c.expect));
  C.mock_plan.forEach((c, i) => check(`mock_plan ${i}`, sc.mockPlan(c.skills, c.competencies, c.count), c.expect));
  C.feedback.forEach((c, i) => check(`feedback ${i}`, sc.checkFeedback(c.o, c.answer), c.expect));
  C.mock_summary.forEach((c, i) => check(`mock_summary ${i}`, sc.mockSummary(c.rows), c.expect));
  C.quality.forEach((c, i) => check(`quality ${i}`, sc.qualityVerdict(c.probes), c.expect));
  check('pyRound', [40.5, 41.5, 2.5, 3.49, 27].map(sc.pyRound), [40, 42, 2, 3, 27]);
  console.log(JSON.stringify({ passed, failed }));
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.log(JSON.stringify({ passed, failed: [{ name: 'crash', got: String(e && e.stack) }] })); process.exit(1); });
