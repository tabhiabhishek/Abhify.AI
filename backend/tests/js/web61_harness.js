/* 6.1 web/desktop: runs the real assistant and shell functions from frontend/app.js in Node with small stubs.
   Usage: node web61_harness.js <path to app.js>   -> prints JSON results for test_web_desktop_61.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');

/** A top-level `function name(...) {...}` (or `async function`) by brace matching. */
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
/** A top-level `const NAME = ...;` statement: up to the first line ending in `;` that is followed by a top-level line. */
function extractConst(name) {
  const start = src.indexOf(`\nconst ${name} =`);
  if (start < 0) throw new Error(`missing const ${name}`);
  let nl = src.indexOf('\n', start + 1);
  while (nl > 0 && !(src[nl - 1] === ';' && src[nl + 1] !== ' ')) nl = src.indexOf('\n', nl + 1);
  if (nl < 0) throw new Error(`unterminated const ${name}`);
  return src.slice(start, nl);
}

const out = {};
const ctx = { URL, URLSearchParams, Set, Object, String, Math, console, answerStore: [], state: { engine: 'pc' }, reader: { ok: true } };   // 7.1: msgTools offers Listen when read-aloud exists
vm.createContext(ctx);
const esc = extract('esc');
vm.runInContext([esc, extractConst('HL_SETS'), extractConst('HL_ALIAS'), extractConst('HL_KW'), extractConst('HL_RE'), extract('highlight'),
  extractConst('unesc'), extract('codeBlockHtml'), extract('md'), extract('contextHtml'), extractConst('ICON_COPY'), extractConst('ICON_REGEN'), extractConst('ICON_SPEAK'),
  extract('msgTools'), extract('followUps'), extract('chatRetryHtml'), extractConst('TAB_NAMES'), extractConst('PATH_TABS'), extract('tabFromUrl')].join('\n'), ctx);

// markdown + code blocks
out.md_code = ctx.md('## Setup\n\nRun **this**:\n\n```python\nimport os  # load\nprint("hi", 42)\n```\nDone.');
out.md_xss = ctx.md('<img src=x onerror=alert(1)>\n```html\n<script>alert(1)</script>\n```');
out.md_table = ctx.md('| a | b |\n|---|---|\n| 1 | <b> |');
out.md_unclosed = ctx.md('text\n```js\nconst a = 1;\n```');
out.hl_sql = ctx.highlight("SELECT name FROM t WHERE id = 3 -- c", 'sql');
out.hl_js = ctx.highlight('const s = "a<b"; f(1) // x', 'js');
out.hl_unknown = ctx.highlight('<b>x</b>', 'brainfuck');
// message tools, follow-ups, context indicator, retry
out.tools_regen = ctx.msgTools('answer', { text: 'q "x"', regen: true });
out.tools_plain = ctx.msgTools('answer', {});
out.followups = ctx.followUps('## Gradient descent\n\nIt uses the **learning rate** and the **loss surface** to move downhill in small steps; '.repeat(2), 'learning');
out.followups_short = ctx.followUps('Short.', null);
out.ctx_used = ctx.contextHtml({ memories: 2, documents: 1, project: 'Thesis', history: 4 });
out.ctx_none = ctx.contextHtml({ memories: 0, documents: 0, project: null, history: 0 });
out.ctx_absent = ctx.contextHtml(null);
out.retry = ctx.chatRetryHtml('why <b>?', true);
// routes
out.routes = ['http://h/?tab=study', 'http://h/assistant', 'http://h/more', 'http://h/#news', 'http://h/#pair=abc', 'http://h/?tab=evil', 'http://h/']
  .map((u) => ctx.tabFromUrl(new URL(u)));

// setOnline: repeated "offline" reports re-render Home once, not in a loop
{
  let renders = 0;
  const c2 = { state: { engine: 'pc', online: true, remote: false, everConnected: true }, current: 'home', navigator: { onLine: true },
    store: { get: () => null }, LS: { token: 't' }, document: { body: { classList: { toggle() {} } }, querySelector: () => null },
    $: () => ({ className: '', textContent: '', innerHTML: '', classList: { toggle() {}, add() {}, remove() {} } }),
    renderHome: () => { renders++; c2.setOnline(false); c2.setOnline(false); }, updateOrb() {}, renderDevice() {},
    flushQueue() {}, refreshProgress() {}, loadModels: () => Promise.resolve(), renderModelChip() {} };
  vm.createContext(c2);
  vm.runInContext(extract('connectionStatus') + '\nlet lastStatusKey = null;\n' + extract('setOnline'), c2);
  for (let i = 0; i < 20; i++) c2.setOnline(false);
  out.offline_home_renders = renders;
}
process.stdout.write(JSON.stringify(out));
