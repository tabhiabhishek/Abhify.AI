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
