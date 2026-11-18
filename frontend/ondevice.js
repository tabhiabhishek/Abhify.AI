/* =====================================================================
   ondevice.js: "Abhify Lite": the AI runs ON THIS DEVICE (phone or computer browser), no PC needed.

   * Engine: WebLLM 0.2.85 on the device's GPU through WebGPU, vendored with pinned hashes
     (vendor/webllm/VENDORED.md). The model libraries (WebAssembly) are served from this app, never
     fetched from a third-party code host. Loaded lazily: PC-mode users never download it.
   * Model weights: downloaded ONCE, only after the user confirms (sizes shown), from
     huggingface.co/mlc-ai over HTTPS, and kept in the browser's cache. Nothing the user types
     leaves the device.
   * Data: conversations and memories in this browser's IndexedDB. Clearing site data removes them.
   ===================================================================== */
const VENDOR = new URL('./vendor/webllm/', import.meta.url);
const LIB = 'web-llm-0.2.85.js';

/** Small models only: what fits a phone. Sizes measured from each model's ndarray-cache.json. */
export const MODELS = {
  'Qwen2.5-1.5B-Instruct-q4f16_1-MLC': {
    label: 'Qwen 2.5 · 1.5B', lib: 'Qwen2-1.5B-Instruct-q4f16_1_cs1k-webgpu.wasm', downloadMB: 869, memoryMB: 1630,
    note: 'Better answers; for phones with 6 GB RAM or more.',
  },
  'Llama-3.2-1B-Instruct-q4f16_1-MLC': {
    label: 'Llama 3.2 · 1B', lib: 'Llama-3.2-1B-Instruct-q4f16_1_cs1k-webgpu.wasm', downloadMB: 695, memoryMB: 880,
    note: 'Lighter; for most phones (and iPhones, which don\'t report their memory).',
  },
};

/** Pure: choose a model from what the browser reports. deviceMemory is in GB, rounded and capped at 8
 *  by Chrome; Safari and Firefox don't report it (undefined). */
export function chooseModel({ webgpu, deviceMemory }) {
  if (!webgpu) {
    return { ok: false, reason: 'This browser can\'t run the AI on this device (no WebGPU). Use Chrome on Android 12+, or Safari on iOS 26+, or connect to your computer instead.' };
  }
  if (deviceMemory !== undefined && deviceMemory < 4) {
    return { ok: false, reason: `This device reports about ${deviceMemory} GB of memory, too little for an on-device AI model. Connect to your computer instead.` };
  }
  const id = deviceMemory !== undefined && deviceMemory >= 6 ? 'Qwen2.5-1.5B-Instruct-q4f16_1-MLC' : 'Llama-3.2-1B-Instruct-q4f16_1-MLC';
  return { ok: true, modelId: id, ...MODELS[id] };
}

/** Pure: the messages sent to the on-device model (system prompt + memories + recent history). */
export function buildMessages(userText, history = [], memories = [], userName = '') {
  let system = 'You are Abhify.AI, a friendly AI/ML learning companion running entirely on the user\'s own device. '
    + 'Be concise and clear; use short paragraphs or bullet points. If you are not sure, say so instead of inventing facts. '
    + 'You have no internet access and no access to documents on this device.';
  if (userName) system += ` The user's name is ${userName}.`;
  if (memories.length) system += '\n\nThings the user asked you to remember:\n' + memories.slice(-20).map((m) => `- ${m.content}`).join('\n');
  const recent = history.slice(-8).map((m) => ({ role: m.role === 'user' ? 'user' : 'assistant', content: m.content }));
  return [{ role: 'system', content: system }, ...recent, { role: 'user', content: userText }];
}

/** Pure: "remember that …" / "remember: …" → the fact to store, else null. */
export function memoryCommand(text) {
  const m = String(text).trim().match(/^(?:please\s+)?remember(?:\s+that)?[:,]?\s+(.{3,300})$/i);
  return m ? m[1].trim().replace(/[.!]+$/, '') : null;
}

/* ---------------------------------------------------------------- engine */
let webllm = null;
let engine = null;
let loadedId = null;

async function lib() {
  webllm ??= await import(new URL(LIB, VENDOR).href);
  return webllm;
}

async function appConfig(modelId) {
  const w = await lib();
  const rec = w.prebuiltAppConfig.model_list.find((m) => m.model_id === modelId);
  if (!rec) throw new Error(`Unknown model ${modelId}`);
  return { model_list: [{ ...rec, model_lib: new URL(MODELS[modelId].lib, VENDOR).href }] };   // our pinned copy
}

export async function support() {
  const webgpu = 'gpu' in navigator && Boolean(await navigator.gpu.requestAdapter().catch(() => null));
  return { webgpu, deviceMemory: navigator.deviceMemory, ...chooseModel({ webgpu, deviceMemory: navigator.deviceMemory }) };
}

export async function isDownloaded(modelId) {
  const w = await lib();
  return w.hasModelInCache(modelId, await appConfig(modelId)).catch(() => false);
}

export async function load(modelId, onProgress = () => {}) {
  if (engine && loadedId === modelId) return;
  const w = await lib();
  engine = await w.CreateMLCEngine(modelId, {
    appConfig: await appConfig(modelId),
    initProgressCallback: (p) => onProgress({ progress: p.progress, text: p.text }),
  });
  loadedId = modelId;
}

export function ready() { return Boolean(engine); }
export function loadedModel() { return loadedId; }

export async function chat(messages) {
  if (!engine) throw new Error('The on-device AI isn\'t loaded yet.');
  const r = await engine.chat.completions.create({ messages, temperature: 0.3, max_tokens: 600 });
  return r.choices[0]?.message?.content?.trim() || '…';
}

export async function remove(modelId) {
  const w = await lib();
  if (engine && loadedId === modelId) { await engine.unload(); engine = null; loadedId = null; }
  await w.deleteModelAllInfoInCache(modelId, await appConfig(modelId));
}

/* ---------------------------------------------------------------- device-local storage (IndexedDB) */
const DB_NAME = 'abhify-device', DB_VERSION = 1;
let dbp = null;

function db() {
  dbp ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const d = req.result;
      d.createObjectStore('messages', { keyPath: 'id', autoIncrement: true }).createIndex('cid', 'cid');
      d.createObjectStore('memories', { keyPath: 'id', autoIncrement: true });
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}

async function tx(store, mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const t = d.transaction(store, mode);
    const out = fn(t.objectStore(store));
    t.oncomplete = () => resolve(out && 'result' in out ? out.result : out);
    t.onerror = () => reject(t.error);
  });
}

export const local = {
  addMessage: (cid, role, content) => tx('messages', 'readwrite', (s) => s.add({ cid, role, content, at: Date.now() })),
  history: (cid) => tx('messages', 'readonly', (s) => s.index('cid').getAll(cid)),
  addMemory: (content) => tx('memories', 'readwrite', (s) => s.add({ content, at: Date.now() })),
  memories: () => tx('memories', 'readonly', (s) => s.getAll()),
  clearAll: () => Promise.all([tx('messages', 'readwrite', (s) => s.clear()), tx('memories', 'readwrite', (s) => s.clear())]),
};
