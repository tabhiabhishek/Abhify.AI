/* 5.6 news image regression (web): runs the real renderNewsCenter / newsOpen / newsImg / newsImgFallback from
   frontend/app.js in Node with a tiny fake DOM. Usage: node news_images_harness.js <path to app.js> -> JSON for
   test_news_images_web.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');
function extract(name) {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`missing function ${name}`);
  let i = src.indexOf('{', src.indexOf(')', start)), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`unbalanced ${name}`);
}
const asyncNames = new Set(['newsOpen']);
const names = ['esc', 'timeAgo', 'newsTime', 'newsImg', 'newsImgFallback', 'newsCatLabel', 'stateHtml', 'renderNewsCenter', 'newsOpen'];

const els = {};
const el = (id) => (els[id] ||= { id, innerHTML: '', textContent: '', disabled: false, classList: { toggle() {}, add() {}, remove() {} } });
const pending = [];
const ctx = {
  $: (sel) => el(sel.replace(/^#/, '')), $$: () => [],
  skeleton: () => '<div class="skeleton"></div>', toast: () => {}, loadNews: () => { ctx.out.classic = true; },
  newsCenterInit: async () => true,
  api: (path) => new Promise((resolve, reject) => pending.push({ path, resolve, reject })),
  document: { createElement: (tag) => ({ tag, className: '', attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } }) },
  NEWS_ACC: { foryou: 'chat', world: 'docs', technology: 'docs' },
  out: {}, Date, encodeURIComponent, Set, String, Promise,
};
vm.createContext(ctx);
vm.runInContext(`const news = { cats: [{ id: 'foryou', label: 'For You' }, { id: 'world', label: 'World' }, { id: 'technology', label: 'Technology' }], prefs: null, cat: 'foryou', result: null, query: '', saved: [] };
let newsSeq = 0;
${names.map((n) => (asyncNames.has(n) ? 'async ' : '') + extract(n)).join('\n')}
this.news = news; this.getSeq = () => newsSeq;`, ctx);

/** [{id, img_for, src, ph}] for every card in #newsList, in order */
function cards() {
  const html = el('newsList').innerHTML, out = [];
  const re = /<article class="ncard[^"]*"[^>]*data-id="([^"]*)">\s*(<img class="nimg" data-for="([^"]*)" src="([^"]*)"|<div class="nimg ph" data-for="([^"]*)")/g;
  let m; while ((m = re.exec(html))) out.push({ id: m[1], img_for: m[3] ?? m[5], src: m[4] ?? null, ph: !m[4] });
  return { list: out, articles: (html.match(/<article /g) || []).length };
}
const art = (id, img, cat = 'world') => ({ id, title: `Story ${id}`, summary: `About ${id}`, publisher: `Pub ${id}`, published_at: Date.now() - 60000, category: cat, image_url: img, url: `https://example.com/${id}` });
const flush = () => new Promise((r) => setImmediate(r));

(async () => {
  const out = ctx.out;
  // 1) each card's image comes from its own article, keyed by id; missing image -> placeholder
  ctx.renderNewsCenter({ articles: [art('a1', 'https://img.example/a1.jpg'), art('a2', null), art('a3', 'https://img.example/a3.jpg', 'technology')], retrieved_at: Date.now() });
  out.first = cards();
  out.first_meta = /class="ncat">· World</.test(el('newsList').innerHTML);
  // 2) a refresh whose first article changed shows the new image (and not the old one)
  let p = ctx.newsOpen('foryou', true); await flush();
  pending.shift().resolve({ articles: [art('b1', 'https://img.example/b1.jpg'), art('a1', 'https://img.example/a1.jpg'), art('a2', null)], retrieved_at: Date.now() });
  await p; out.refreshed = cards(); out.refresh_meta = el('newsMeta').textContent;
  // 3) an older response (lower newsSeq) can never overwrite a newer one
  const pOld = ctx.newsOpen('world'); await flush(); const reqOld = pending.shift();
  const pNew = ctx.newsOpen('technology'); await flush(); const reqNew = pending.shift();
  reqNew.resolve({ articles: [art('n1', 'https://img.example/n1.jpg', 'technology')], retrieved_at: Date.now() }); await pNew;
  reqOld.resolve({ articles: [art('o1', 'https://img.example/o1.jpg'), art('o2', 'https://img.example/o2.jpg')], retrieved_at: Date.now() }); await pOld;
  out.after_race = cards(); out.race_paths = [reqOld.path, reqNew.path]; out.result_ids = ctx.news.result.articles.map((a) => a.id);
  // 4) a broken image gets the same placeholder class as a missing one, still keyed to its story
  let replaced = null;
  const broken = { className: 'nimg', getAttribute: (k) => (k === 'data-for' ? 'n1' : null), replaceWith: (n) => { replaced = n; } };
  ctx.newsImgFallback(broken);
  out.fallback = { className: replaced.className, for: replaced.attrs['data-for'], hidden: replaced.attrs['aria-hidden'] };
  out.missing_class = (el('newsList').innerHTML.match(/<div class="(nimg ph)"/) || [])[1] || null;
  ctx.renderNewsCenter({ articles: [art('m1', null)], retrieved_at: Date.now() });
  out.missing_class = (el('newsList').innerHTML.match(/<div class="(nimg ph)"/) || [])[1] || null;
  process.stdout.write(JSON.stringify(out));
})().catch((e) => { console.error(e && e.stack || e); process.exit(1); });
