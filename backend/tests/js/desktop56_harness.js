/* 5.6: runs the new pure render helpers from frontend/app.js in Node (search groups, filters, status overview, agent
   plan, skills). Usage: node desktop56_harness.js <path to app.js> -> JSON for test_desktop_56.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');
function extract(name) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`missing function ${name}`);
  let i = src.indexOf('{', start), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`unbalanced ${name}`);
}
const ctx = { URLSearchParams, out: {} };
vm.createContext(ctx);
vm.runInContext(['esc', 'palListHtml', 'palSearchQuery', 'overviewHtml', 'agentPlanHtml', 'skillsHtml'].map(extract).join('\n'), ctx);

const out = {};
out.list = ctx.palListHtml([['Voice plan', 'Conversation', null, 'Conversation · 2'], ['Old voice', 'Conversation', null, 'Conversation · 2'],
  ['voice.pdf', 'Knowledge file', null, 'Knowledge file · 1'], ['Ask the AI', 'Chat', null]], 0);
out.query_default = ctx.palSearchQuery('voice', { type: '', days: '', sort: 'relevance' });
out.query_filtered = ctx.palSearchQuery('voice <x>', { type: 'project', days: '7', sort: 'recent' });
out.overview = ctx.overviewHtml({
  name: 'Abhify <b>', archived: false, read_only: true, instructions_excerpt: 'FastAPI', note: 'Read-only.',
  status: { recorded: true, age: 'today', objective: 'Local AI', stack: null, current: 'Routing done' },
  milestones: [{ text: 'Add dark mode', source: 'completed milestone (recorded)' }], issues: [{ text: 'Waiting for Chroma', source: 'open issue / blocker (recorded)' }],
  decisions: [], next: [], documents: [{ name: 'a.pdf', indexed: false }],
  activity: { conversations: 1, conversations_age: 'today', tasks: [], task_counts: {} },
  next_possible_actions: [{ text: 'Resolve: Waiting for Chroma', source: 'open issue (recorded)' }],
});
out.plan = ctx.agentPlanHtml({ title: 'Related documents', sections: [{ title: 'Related documents', lines: [{ text: 'rag.md', source: 'shares terms: rag, local' }] }],
  proposals: [{ id: 'i1', tool: 'doc.link', kind: 'REVERSIBLE', label: 'Link "rag.md"', source: 'shares terms: rag, local' }] });
out.plan_empty = ctx.agentPlanHtml({ title: 'Unfinished work', sections: [], proposals: [] });
out.skills = ctx.skillsHtml({ skills: [{ skill: 'sql', level: 'Needs work', answered: 6, accuracy: 33, answers_label: '6 answers', trend: null },
  { skill: 'react', level: 'Not enough data yet', answered: 2, accuracy: null, answers_label: '2 answers', trend: null }], next: null, min_answers: 5,
  revision: [{ skill: 'sql', reason: 'Needs work: 2 of 6 correct.', answers_label: '6 answers' }],
  learning_path: [{ skill: 'javascript', level: 'Not practised yet', yours: false, answers_label: '0 answers', comes_before: ['react'] }], path_note: 'General guidance.' });
process.stdout.write(JSON.stringify(out));
