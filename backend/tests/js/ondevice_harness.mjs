/* Runs the pure parts of the on-device mode (Abhify Lite) in Node, no browser or GPU needed:
   chooseModel / buildMessages / memoryCommand from frontend/ondevice.js, and deviceStatus from
   frontend/app.js. Usage: node ondevice_harness.mjs <frontend dir>  -> JSON for test_ondevice.py */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { pathToFileURL } from 'node:url';

const dir = process.argv[2];
// ondevice.js is an ES module served as .js; Node needs .mjs to load it that way (a temporary copy).
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'abhify-od-')), 'ondevice.mjs');
fs.copyFileSync(path.join(dir, 'ondevice.js'), tmp);
const od = await import(pathToFileURL(tmp).href);
fs.rmSync(path.dirname(tmp), { recursive: true, force: true });
const out = {};

out.choose = {
  no_webgpu: od.chooseModel({ webgpu: false, deviceMemory: 8 }),
  low_memory: od.chooseModel({ webgpu: true, deviceMemory: 2 }),
  mem_4: od.chooseModel({ webgpu: true, deviceMemory: 4 }),
  mem_6: od.chooseModel({ webgpu: true, deviceMemory: 6 }),
  mem_8: od.chooseModel({ webgpu: true, deviceMemory: 8 }),
  unknown_memory: od.chooseModel({ webgpu: true, deviceMemory: undefined }),   // Safari / iPhone
};
out.models = od.MODELS;

const history = Array.from({ length: 12 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', content: `m${i}` }));
out.messages = od.buildMessages('What is overfitting?', history, [{ content: 'I am learning PyTorch' }], 'Abhi');
out.messages_empty = od.buildMessages('hi');

out.memory = Object.fromEntries(['remember that I prefer short answers.', 'Remember: my exam is on Friday',
  'please remember I like PyTorch', 'do you remember what I said?', 'remember', 'what is a tensor?']
  .map((t) => [t, od.memoryCommand(t)]));

// deviceStatus from app.js (extracted by brace matching, like pwa_harness.js)
const src = fs.readFileSync(path.join(dir, 'app.js'), 'utf8');
function extract(name) {
  const start = src.indexOf(`function ${name}(`);
  let i = src.indexOf('{', start), depth = 0;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1);
  }
  throw new Error(`missing ${name}`);
}
const ctx = { esc: (s) => String(s ?? '').replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`) };
vm.createContext(ctx);
vm.runInContext(extract('deviceStatus'), ctx);
const sup = od.chooseModel({ webgpu: true, deviceMemory: 4 });
const st = (d) => ctx.deviceStatus({ support: sup, downloaded: false, ready: false, loading: null, progress: null, error: null, ...d });
out.status = {
  checking: st({ support: null }),
  unsupported: st({ support: od.chooseModel({ webgpu: false }) }),
  not_set_up: st({}),
  downloading: st({ loading: Promise.resolve(), progress: { progress: 0.42, text: 'Fetching param cache[3/22]' } }),
  starting: st({ downloaded: true, loading: Promise.resolve(), progress: { progress: 0.9, text: 'Loading GPU shader modules' } }),
  downloaded_idle: st({ downloaded: true }),
  ready: st({ downloaded: true, ready: true }),
  failed: st({ error: 'Device lost <b>' }),
};
console.log(JSON.stringify(out));
