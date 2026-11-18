/* Real-time engine (web): runs the live-information render helpers and the speech text builder from frontend/app.js in Node.
   Usage: node realtime_harness.js <path to app.js>   -> prints JSON results for test_realtime_web.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');

function extract(name) {
  let start = src.indexOf(`\nfunction ${name}(`);
  if (start < 0) start = src.indexOf(`\nasync function ${name}(`);
  if (start < 0) throw new Error(`missing function ${name}`);
  let i = src.indexOf('{', src.indexOf(')', start)), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`unbalanced ${name}`);
}
function extractConst(name) {
  const start = src.indexOf(`\nconst ${name} =`);
  if (start < 0) throw new Error(`missing const ${name}`);
  return src.slice(start, src.indexOf('\n', start + 1));
}

const ctx = { URL, Set, Object, String, Math, Date, console };
vm.createContext(ctx);
vm.runInContext([extract('esc'), extract('timeAgo'), extractConst('liveStore'), extractConst('LIVE_STATE_LABEL'), extract('liveSearchingText'),
  extract('liveOldest'), extract('liveStatusText'), extract('liveIndicatorHtml'), extract('liveSafeUrl'), extract('liveWhen'), extract('livePublished'),
  extract('liveBoxHtml'), extract('liveDetailHtml'), extract('speechChunks')].join('\n'), ctx);

const now = Date.now();
const evil = {
  id: 'W1', kind: 'web', title: '<img src=x onerror=alert(1)>Title', publisher: '"><script>alert(2)</script>', url: 'javascript:alert(3)',
  published_at: null, retrieved_at: now, snippet: '<b>bold</b> & "quotes"', source_id: 'web', freshness: 'historical', reliability: 'web',
};
const good = {
  id: 'W2', kind: 'news', title: 'AI story', publisher: 'The Hindu', url: 'https://news.example/a?b=1&c=2', published_at: now - 2 * 3600000,
  retrieved_at: now, snippet: 'A short description.', source_id: 'news', freshness: 'recent', reliability: 'news',
};
const fx = { id: 'W3', kind: 'fx', title: 'USD→INR daily reference rate', publisher: 'ECB via Frankfurter', url: 'https://www.frankfurter.app/',
  published_at: now - 86400000, published_date: '2026-11-17', retrieved_at: now, snippet: '1 USD = 84.1 INR', freshness: 'recent' };
const rec = { id: 'L1', kind: 'project', title: 'Project: Abhify', publisher: 'Your project records', url: null, published_at: null,
  retrieved_at: now, snippet: 'Status', freshness: 'live' };
const out = {};
out.box = ctx.liveBoxHtml({ needed: ['web', 'news'], state: 'verified', sources: [evil, good, fx, rec], notes: ['General web search: DuckDuckGo'],
  problems: [], retrieved_at: now }, 'What is <new>?');
out.detail_evil = ctx.liveDetailHtml(evil);
out.detail_good = ctx.liveDetailHtml(good);
out.cached = ctx.liveStatusText({ state: 'cached', sources: [{ ...good, freshness: 'cached', retrieved_at: now - 12 * 60000 }], retrieved_at: now });
out.unavailable = ctx.liveBoxHtml({ needed: ['weather'], state: 'unavailable', sources: [], notes: ['Weather is off <x>'], problems: ['Weather is off <x>'] }, 'q');
out.none = ctx.liveBoxHtml({ needed: [], state: 'none', sources: [], notes: [] }, 'q');
out.searching = [ctx.liveIndicatorHtml({ phase: 'searching', needed: ['news'] }), ctx.liveIndicatorHtml({ phase: 'searching', needed: ['weather'] }),
  ctx.liveIndicatorHtml({ phase: 'searching', needed: ['news', 'web'] }), ctx.liveIndicatorHtml({ phase: 'searching', needed: ['tasks'] })];
out.speech = ctx.speechChunks('Python 3.14 is out [W1]. See https://python.org and www.python.org for details [W1, W2].\n\nYour project is on track [L1].')
  .map((p) => p.text);
console.log(JSON.stringify(out));
