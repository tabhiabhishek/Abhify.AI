/* =====================================================================
   study-core.js: Study 7.2 deterministic rules for the Web app (spec: docs/specs/ABHIFY_STUDY_V1.md).
   Port of backend/study_core.py (the reference): it must reproduce backend/tests/data/study_cases.json exactly.
   Pure functions, no AI, no storage. Dates are ISO strings (YYYY-MM-DD).
   ===================================================================== */
export const PREFERRED_HEAD = /\b(preferred|nice to have|nice-to-have|good to have|bonus|a plus|plus points?|desirable|optional)\b/i;
export const REQUIRED_HEAD = /\b(requirements?|required|must have|must-have|qualifications?|what you('|’)ll need|you have|skills|responsibilities)\b/i;
export const BEHAVIOURAL = 'Behavioural';
export const MAX_GAPS = 12;
export const DEFAULT_DAYS = 14;
export const MAX_DAYS = 60;
export const REVIEW_STEPS = [1, 3, 7, 14, 30];
export const LESSON_MINUTES = 30;
export const WEAK_MIN_ANSWERS = 3;
export const WEAK_ACCURACY = 0.6;

/** Python's round(): halves go to the even neighbour (round(40.5) == 40). */
export function pyRound(x) {
  const f = Math.floor(x), diff = x - f;
  if (Math.abs(diff - 0.5) < 1e-9) return f % 2 === 0 ? f : f + 1;
  return Math.round(x);
}

export function addDays(iso, n) {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
export function daysBetween(fromIso, toIso) {
  return Math.round((Date.parse(`${toIso}T00:00:00Z`) - Date.parse(`${fromIso}T00:00:00Z`)) / 86400000);
}
export function todayIso(now = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`;
}

function norm(s) {
  let t = String(s).toLowerCase().replace(/[^a-z0-9+#.]+/g, ' ');
  t = t.replace(/\.(?=\s|$)/g, ' ');
  return ` ${t.replace(/\s+/g, ' ').trim()} `;
}
const terms = (skill) => [skill.name, ...(skill.aliases || [])];

export function findSkills(text, taxonomy) {
  const t = norm(text || '');
  const found = [];
  for (const s of taxonomy) {
    let pos = -1;
    for (const term of terms(s)) { const p = t.indexOf(norm(term)); if (p >= 0 && (pos < 0 || p < pos)) pos = p; }
    if (pos >= 0) found.push([pos, s.name]);
  }
  found.sort((a, b) => a[0] - b[0] || (a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0));
  return found.map((x) => x[1]);
}

const isUpper = (s) => s.toUpperCase() === s && s.toLowerCase() !== s;

export function extractSkills(jd, taxonomy) {
  const required = [], preferred = [];
  let mode = 'required';
  for (const line of String(jd || '').split(/\r\n|\r|\n/)) {
    const stripped = line.trim();
    if (!stripped) continue;
    const isHead = stripped.length <= 60 && (stripped.endsWith(':') || isUpper(stripped) || stripped.startsWith('#'));
    if (isHead) {
      mode = PREFERRED_HEAD.test(stripped) ? 'preferred' : REQUIRED_HEAD.test(stripped) ? 'required' : mode;
      continue;
    }
    for (const sentence of stripped.split(/(?<=[.;!?])\s+/)) {
      const target = (mode === 'preferred' || PREFERRED_HEAD.test(sentence)) ? preferred : required;
      for (const s of findSkills(sentence, taxonomy)) if (!target.includes(s)) target.push(s);
    }
  }
  return { required, preferred: preferred.filter((s) => !required.includes(s)) };
}

export function gaps(jdSkills, resumeSkills) {
  const have = new Set(resumeSkills);
  const out = [
    ...jdSkills.required.filter((s) => !have.has(s)).map((s) => ({ skill: s, why: 'required, not on your resume' })),
    ...jdSkills.preferred.filter((s) => !have.has(s)).map((s) => ({ skill: s, why: 'preferred, not on your resume' })),
    ...jdSkills.required.filter((s) => have.has(s)).map((s) => ({ skill: s, why: 'required: refresh' })),
  ];
  return out.slice(0, MAX_GAPS);
}

export function planDays(interview, today) {
  if (!interview) return DEFAULT_DAYS;
  return Math.max(1, Math.min(MAX_DAYS, daysBetween(today, interview)));
}

export function makePlan(gapList, interview, today, minutes) {
  const n = planDays(interview, today);
  minutes = Math.max(15, Math.min(240, Math.trunc(minutes || 60)));
  const skills = gapList.length ? gapList.map((g) => g.skill) : ['General interview preparation'];
  const days = [];
  for (let d = 0; d < n; d++) {
    const last = n >= 3 && d === n - 1;
    let items;
    if (last) {
      items = [{ type: 'mcq', skill: 'All skills', minutes: pyRound(minutes * 0.5), title: 'Mixed MCQs across all your plan skills' },
        { type: 'story', skill: BEHAVIOURAL, minutes: pyRound(minutes * 0.3), title: 'Rehearse your STAR stories out loud' },
        { type: 'review', skill: 'All skills', minutes: minutes - pyRound(minutes * 0.5) - pyRound(minutes * 0.3), title: 'Review everything you missed' }];
    } else {
      const skill = skills[d % skills.length];
      items = [{ type: 'lesson', skill, minutes: pyRound(minutes * 0.45), title: `Learn: ${skill}` },
        { type: 'mcq', skill, minutes: pyRound(minutes * 0.25), title: `Practice MCQs: ${skill}` }];
      if (d % 2 === 1) items.push({ type: 'story', skill: BEHAVIOURAL, minutes: pyRound(minutes * 0.2), title: 'Write or improve one STAR story' });
      if (d >= 1) items.push({ type: 'review', skill: 'Missed questions', minutes: pyRound(minutes * 0.1), title: 'Review questions you missed' });
    }
    days.push({ date: addDays(today, d), day: d + 1, items });
  }
  return days;
}

export function skillScore(answered, correct, lessonsDone, lessonsTotal) {
  const acc = answered ? correct / answered : 0;
  const confidence = Math.min(1, answered / 10);
  const lessons = lessonsTotal ? lessonsDone / lessonsTotal : 0;
  return pyRound(100 * (0.6 * acc * confidence + 0.4 * lessons));
}

export function level(score) { return score < 40 ? 'Not ready' : score < 70 ? 'Getting there' : 'Ready'; }

export function readiness(skills, mcq, lessons) {
  const rows = skills.map((s) => {
    const m = mcq[s] || {}, l = lessons[s] || {};
    const a = Math.trunc(m.answered || 0), c = Math.trunc(m.correct || 0), ld = Math.trunc(l.done || 0), lt = Math.trunc(l.total || 0);
    const sc = skillScore(a, c, ld, lt);
    return { skill: s, score: sc, level: level(sc), answered: a, lessons_done: ld, lessons_total: lt };
  });
  const overall = rows.length ? pyRound(rows.reduce((t, r) => t + r.score, 0) / rows.length) : 0;
  const weak = [...rows].sort((a, b) => a.score - b.score || (a.skill < b.skill ? -1 : a.skill > b.skill ? 1 : 0))
    .filter((r) => r.score < 40).map((r) => r.skill);
  return { overall, level: level(overall), skills: rows, weak };
}

/** [lapses] = earlier misses: from the second miss on, the question comes back the same day. */
export function nextReview(timesRight, answeredOn, correct, lapses = 0) {
  if (!correct) return { due: addDays(answeredOn, lapses >= 1 ? 0 : REVIEW_STEPS[0]), times_right: 0, done: false, lapses: lapses + 1 };
  const t = timesRight + 1;
  if (t >= REVIEW_STEPS.length) return { due: null, times_right: t, done: true, lapses };
  return { due: addDays(answeredOn, REVIEW_STEPS[t]), times_right: t, done: false, lapses };
}

/** A new plan version: skills already Ready (70+) go last as a light refresh. */
export function adaptGaps(gapList, rows) {
  const ready = new Set(rows.filter((r) => (r.score || 0) >= 70).map((r) => r.skill));
  return [...gapList.filter((g) => !ready.has(g.skill)), ...gapList.filter((g) => ready.has(g.skill)).map((g) => ({ skill: g.skill, why: 'already Ready: light refresh' }))];
}

export function streak(activityDays, today) {
  const days = new Set(activityDays);
  const start = days.has(today) ? today : addDays(today, -1);
  let n = 0;
  while (days.has(addDays(start, -n))) n++;
  return n;
}

export function lessonSkills(tracks) {
  const out = {};
  for (const t of tracks.tracks) for (const m of t.modules) for (const l of m.lessons) out[l.id] = m.skills;
  return out;
}

export function lessonsPerSkill(tracks, doneIds) {
  const out = {};
  for (const [lid, skills] of Object.entries(lessonSkills(tracks))) {
    for (const s of skills) {
      const r = out[s] ??= { done: 0, total: 0 };
      r.total += 1; r.done += doneIds.has(lid) ? 1 : 0;
    }
  }
  return out;
}

/* ---- 7.2 course journey ---- */
export function courseSchedule(modules, minutes, start, optional = [], done = []) {
  const opt = new Set(optional || []), doneSet = new Set(done || []);
  const ordered = [...modules.filter((m) => !opt.has(m.id)), ...modules.filter((m) => opt.has(m.id))];
  const queue = [];
  for (const m of ordered) for (const l of m.lessons) if (!doneSet.has(l.id)) queue.push(l.id);
  const perDay = Math.max(1, Math.trunc(Math.max(15, Math.min(240, Math.trunc(minutes || 60))) / LESSON_MINUTES));
  const days = [];
  let d = 0, i = 0;
  while (i < queue.length) {
    const day = addDays(start, d);
    if (d % 7 === 6) days.push({ date: day, kind: 'revision', lessons: [] });
    else { days.push({ date: day, kind: 'learn', lessons: queue.slice(i, i + perDay) }); i += perDay; }
    d++;
  }
  const weeks = [], seen = new Set(), covered = new Set(doneSet);
  for (let w = 0; w < days.length; w += 7) {
    const chunk = days.slice(w, w + 7);
    const lessons = chunk.flatMap((x) => x.lessons);
    lessons.forEach((l) => covered.add(l));
    const finished = ordered.filter((m) => !seen.has(m.id) && m.lessons.every((l) => covered.has(l.id))).map((m) => m.id);
    finished.forEach((m) => seen.add(m));
    weeks.push({ week: w / 7 + 1, from: chunk[0].date, to: chunk[chunk.length - 1].date, lessons: lessons.length, modules_completed: finished });
  }
  return { per_day: perDay, days, weeks };
}

export function moduleMastery(module, done, stats) {
  const s = stats[module.id] || {};
  const a = Math.trunc(s.answered || 0), c = Math.trunc(s.correct || 0);
  const doneSet = new Set(done);
  const ld = module.lessons.filter((l) => doneSet.has(l.id)).length;
  const score = skillScore(a, c, ld, module.lessons.length);
  const weak = a >= WEAK_MIN_ANSWERS && c / a < WEAK_ACCURACY;
  return { module: module.id, score, level: level(score), answered: a, correct: c, lessons_done: ld, lessons_total: module.lessons.length, weak };
}

export function nextLesson(modules, done, optional = []) {
  const opt = new Set(optional || []), doneSet = new Set(done);
  for (const m of [...modules.filter((x) => !opt.has(x.id)), ...modules.filter((x) => opt.has(x.id))]) {
    for (const l of m.lessons) if (!doneSet.has(l.id)) return l.id;
  }
  return null;
}

/* ---- 7.2.0-dev.2 shared rules (port of study_core.py: fact_guard, mock_plan, check_feedback, mock_summary, quality_verdict) ---- */
const NUM_RE = /\d+(?:[.,]\d+)?/g;
export function factGuard(source, rewritten, taxonomy) {
  const haveN = new Set(String(source || '').match(NUM_RE) || []);
  const newN = [...new Set((String(rewritten || '').match(NUM_RE) || []).filter((n) => !haveN.has(n)))].sort();
  const haveS = new Set(findSkills(source || '', taxonomy));
  const newS = findSkills(rewritten || '', taxonomy).filter((s) => !haveS.has(s));
  return { ok: !newN.length && !newS.length, new_numbers: newN, new_skills: newS };
}
export function mockPlan(skills, competencies, count) {
  count = Math.max(3, Math.min(12, Math.trunc(count || 6)));
  const tech = skills.map((s) => ({ kind: 'technical', skill: s }));
  const beh = competencies.map((c) => ({ kind: 'behavioural', competency: c.id, question: c.question }));
  const out = []; let i = 0, j = 0;
  while (out.length < count && (i < tech.length || j < beh.length)) {
    if ((out.length % 2 === 0 && i < tech.length) || j >= beh.length) out.push(tech[i++]);
    else out.push(beh[j++]);
  }
  return out;
}
const squash = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();
export function checkFeedback(o, answer) {
  if (!o || typeof o !== 'object' || !o.scores || typeof o.scores !== 'object') return null;
  const scores = {};
  for (const k of ['relevance', 'depth', 'structure']) {
    const v = o.scores[k];
    if (typeof v !== 'number' || !(v >= 1 && v <= 5)) return null;
    scores[k] = pyRound(v);
  }
  const ans = squash(answer);
  const evidence = (Array.isArray(o.evidence) ? o.evidence : []).map((q) => String(q).trim()).filter((q) => q && squash(q).length >= 4 && ans.includes(squash(q))).map((q) => q.slice(0, 200)).slice(0, 3);
  const tips = (Array.isArray(o.tips) ? o.tips : []).map((t) => String(t).trim()).filter(Boolean).map((t) => t.slice(0, 300)).slice(0, 3);
  return { scores, total: scores.relevance + scores.depth + scores.structure, evidence, tips };
}
export function mockSummary(rows) {
  const scored = rows.filter((r) => r.feedback).map((r) => r.feedback);
  if (!scored.length) return { answered: 0, percent: null, means: {}, weakest: null };
  const means = {};
  for (const k of ['relevance', 'depth', 'structure']) means[k] = pyRound(10 * scored.reduce((t, f) => t + f.scores[k], 0) / scored.length) / 10;
  const percent = pyRound(100 * scored.reduce((t, f) => t + f.total, 0) / (15 * scored.length));
  const weakest = Object.keys(means).sort((a, b) => means[a] - means[b] || (a < b ? -1 : 1))[0];
  return { answered: scored.length, percent, means, weakest };
}
export function qualityVerdict(probes) {
  const n = ['json_mcq', 'lesson_sections', 'fact'].filter((k) => probes[k]).length;
  return n === 3 ? 'good' : n === 2 ? 'weak' : 'poor';
}
