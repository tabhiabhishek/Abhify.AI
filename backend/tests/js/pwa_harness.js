/* Runs selected functions from frontend/app.js in Node with small stubs (no browser needed).
   Usage: node pwa_harness.js <path to app.js>   -> prints JSON results for test_pwa_behaviour.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');

/** Extract a top-level `function name(...) {...}` by brace matching (strings/templates are simple here). */
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

function sandbox({ token = null, remote = true, everConnected = false, online = true, hash = '' } = {}) {
  const ls = new Map(token ? [['abhify_token', token]] : []);
  const toasts = [];
  const ctx = {
    LS: { token: 'abhify_token', api: 'abhify_api' },
    store: { get: (k) => (ls.has(k) ? ls.get(k) : null), set: (k, v) => ls.set(k, v) },
    state: { remote, everConnected, health: null, ready: Promise.resolve() },
    navigator: { onLine: online },
    location: { hash, pathname: '/', search: '' },
    history: { replaceState: (_s, _t, url) => { ctx.location.hash = ''; ctx.replaced = url; } },
    toast: (m) => toasts.push(m),
    setTimeout: (fn) => fn(),
    toasts, ls,
  };
  vm.createContext(ctx);
  vm.runInContext(extract('connectionStatus') + '\n' + extract('readPairingLink'), ctx);
  return ctx;
}

const H = (o) => ({ app: 'Abhify.AI', status: 'ok', ollama: true, llm_ready: true, ...o });
const out = {};
const status = (opts, on, h) => sandbox(opts).connectionStatus(on, h);

out.unpaired_no_token = status({}, false, H({ remote: true, authorized: false, companion_enabled: true }));
out.unpaired_wrong_token = status({ token: 'x'.repeat(32) }, false, H({ remote: true, authorized: false, companion_enabled: true }));
out.companion_off = status({}, false, H({ remote: true, authorized: false, companion_enabled: false }));
out.paired_ready = status({ token: 't'.repeat(32) }, true, H({ remote: true, authorized: true }));
out.paired_no_ollama = status({ token: 't'.repeat(32) }, false, H({ remote: true, authorized: true, ollama: false, llm_ready: false }));
out.paired_no_model = status({ token: 't'.repeat(32) }, false, H({ remote: true, authorized: true, llm_ready: false }));
out.paired_unreachable_never = status({ token: 't'.repeat(32) }, false, null);
out.paired_connection_lost = status({ token: 't'.repeat(32), everConnected: true }, false, null);
out.remote_unpaired_unreachable = status({}, false, null);
out.device_offline = status({ token: 't'.repeat(32), online: false }, false, null);
out.local_ready = status({ remote: false }, true, H({ remote: false, authorized: true, internet: true }));
out.local_no_internet = status({ remote: false }, true, H({ remote: false, authorized: true, internet: false }));
out.local_no_ollama = status({ remote: false }, false, H({ remote: false, authorized: true, ollama: false }));
out.local_lost = status({ remote: false, everConnected: true }, false, null);
out.member_ready = status({ token: 't'.repeat(32) }, true, H({ remote: true, authorized: true, role: 'member' }));
out.public_join = status({}, false, H({ remote: true, authorized: false, companion_enabled: true, registration: 'public' }));
out.invite_only_unpaired = status({}, false, H({ remote: true, authorized: false, companion_enabled: true, registration: 'invite_only' }));

// dev.8 model chip: what the chat shows for each model-list state (pure function, real code)
{
  const mctx = { state: null }; vm.createContext(mctx);
  vm.runInContext([extract('cloudModel'), extract('modelDisplay'), extract('modelChipView'),
    "var PROV_TITLE = { custom: 'Custom endpoint', groq: 'Groq' };"].join('\n'), mctx);   // 7.1: cloud model ids
  const M = (o) => ({ list: [], def: null, ollama: null, error: null, locked: false, loaded: false, loading: false, forbidden: false, ...o });
  const L = [{ name: 'qwen2.5:latest', default: true }, { name: 'qwen2.5:1.5b', default: false }];
  const view = (models, chatModel = null, engine = 'pc', device = {}) => mctx.modelChipView({ engine, device, models: M(models), chatModel });
  out.model_default = view({ loaded: true, ollama: true, list: L, def: 'qwen2.5' });
  out.model_chosen = view({ loaded: true, ollama: true, list: L, def: 'qwen2.5' }, 'qwen2.5:1.5b');
  out.model_removed = view({ loaded: true, ollama: true, list: L, def: 'qwen2.5' }, 'llama3:8b');
  out.model_empty = view({ loaded: true, ollama: true, list: [], def: 'qwen2.5' });
  out.model_ollama_down = view({ loaded: true, ollama: false, error: "Ollama isn't reachable" });
  out.model_loading = view({ loading: true });
  out.model_phone = view({ loaded: true, forbidden: true });
  out.model_device = view({}, null, 'device', { modelLabel: 'Qwen 2.5 · 1.5B' });
}

// News audio (stability milestone): chunking and the read-aloud player, with a fake speech engine (real code)
{
  const actx = {}; vm.createContext(actx);
  vm.runInContext(extract('speechChunks') + '\n' + extract('makeReader'), actx);
  const brief = 'Briefing from 3 stories.\n\n- Markets rose after the **rate** decision [1].\n- A storm hit the coast; 2,000 homes lost power [2, 3]. '
    + 'Repairs continue tonight. See https://example.com/x for more.\n- Science: a new telescope image was released [3].';
  const long = 'This is a sentence about the economy. '.repeat(40);
  const chunks = actx.speechChunks(brief);
  out.audio_chunks = chunks;
  out.audio_long = actx.speechChunks(long).map((p) => p.text.length);
  out.audio_empty = actx.speechChunks('   \n\n  ').length;
  const said = [], cancels = [];
  const synth = { speak: (u) => said.push(u), cancel: () => cancels.push(said.length) };
  function U(text) { this.text = text; }
  const r = actx.makeReader(synth, U);
  const end = () => said[said.length - 1].onend();                  // the engine finished the current part
  const log = [];
  r.onchange = (x) => log.push(`${x.state}:${x.i}`);
  r.load(chunks, 'news');
  r.play(0);
  out.audio_start = { state: r.state, saying: said[0].text, item: r.item(), items: r.items() };
  end();
  out.audio_second = { saying: said[said.length - 1].text, i: r.i };
  r.pause();
  const staleEnd = said[said.length - 1];
  staleEnd.onend();                                                  // a late event from the cancelled part must not advance
  out.audio_pause = { state: r.state, i: r.i, spoken: said.length };
  r.resume();
  out.audio_resume = { state: r.state, saying: said[said.length - 1].text, i: r.i, spoken: said.length };
  r.play(r.i); r.play(r.i);                                          // Listen pressed twice: still one voice
  out.audio_no_duplicates = { spoken_after_two_presses: said.length, last_cancel: cancels[cancels.length - 1] };
  r.skip(1);
  out.audio_next_item = { item: r.item(), saying: said[said.length - 1].text };
  r.skip(-1);
  out.audio_prev_item = { item: r.item() };
  r.setRate(1.5);
  out.audio_rate = { rate: said[said.length - 1].rate, state: r.state };
  while (r.state === 'playing') end();
  out.audio_ended = { state: r.state, i: r.i };
  r.toggle();
  out.audio_replay = { state: r.state, i: r.i, saying: said[said.length - 1].text };
  said[said.length - 1].onerror({ error: 'synthesis-failed' });
  out.audio_error = { state: r.state, error: r.error };
  r.stop();
  out.audio_stop = { state: r.state, i: r.i };
  const none = actx.makeReader(undefined, undefined);
  none.load(chunks, 'news'); none.play(0);
  out.audio_unavailable = { ok: none.ok, state: none.state, error: none.error };
  const empty = actx.makeReader(synth, U); empty.load([], 'news'); empty.play(0);
  out.audio_nothing = { state: empty.state, error: empty.error };
}

// pairing link: fresh load and while the app is already open (hashchange calls the same function)
(async () => {
  const tok = 'A'.repeat(32);
  const fresh = sandbox({ hash: `#pair=${tok}` });
  out.pair_fresh = { stored: fresh.readPairingLink(), token: fresh.ls.get('abhify_token'), hash_cleared: fresh.location.hash === '' };
  const open = sandbox({ token: 'old'.repeat(11) });
  out.pair_no_hash = open.readPairingLink();
  open.location.hash = `#pair=${tok}`;                         // link opened in the already-open tab
  open.state.health = { remote: true, authorized: true };
  out.pair_while_open = { stored: open.readPairingLink(), token: open.ls.get('abhify_token') };
  const bad = sandbox({ hash: `#pair=${tok}` });
  bad.state.health = { remote: true, authorized: false };
  bad.readPairingLink();
  const short = sandbox({ hash: '#pair=short' });
  out.pair_rejects_short = short.readPairingLink();
  await new Promise((r) => setImmediate(r));
  out.pair_while_open.toasts = open.toasts;
  out.pair_rejected_toasts = bad.toasts;
  console.log(JSON.stringify(out));
})();
