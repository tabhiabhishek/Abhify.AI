/* 7.1 voice pipeline (web): runs the pure voice helpers from frontend/app.js in Node.
   Usage: node voice_harness.js <path to app.js>   -> prints JSON results for test_voice_71.py */
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

const ctx = { Math, String, Array, Boolean, Object, console, state: { speakMode: 'voice' } };
vm.createContext(ctx);
// 7.2.1: the microphone level constants the VAD and verdicts use
const consts = ['MIC_SILENT', 'MIC_QUIET'].map((n) => { const m = src.match(new RegExp(`\\nconst ${n} = [^;]+;`)); if (!m) throw new Error(`missing ${n}`); return m[0]; });
vm.runInContext(consts.join('\n') + '\n' + ['voicePlan', 'makeVad', 'micVerdict', 'micSilentMessage', 'pickVoice', 'micErrorMessage', 'srErrorMessage', 'shouldSpeak', 'makeReader'].map(extract).join('\n'), ctx);

const out = {};
const ready = { available: true, installed: true, ready: true, download_mb: 145, download: { state: 'idle' } };
const missing = { available: true, installed: false, ready: false, download_mb: 145, download: { state: 'idle' } };
out.plan = {
  local_ready: ctx.voicePlan('local', ready, true, true),
  local_missing: ctx.voicePlan('local', missing, true, true),
  local_downloading: ctx.voicePlan('local', { ...missing, download: { state: 'downloading' } }, true, true),
  local_failed: ctx.voicePlan('local', { ...missing, download: { state: 'error', error: 'offline' } }, true, true),
  local_no_lib: ctx.voicePlan('local', { available: false }, true, true),
  local_unknown: ctx.voicePlan('local', null, true, true),
  local_no_backend_sr: ctx.voicePlan('local', null, true, false),
  local_no_backend_no_sr: ctx.voicePlan('local', null, false, false),
  browser_sr: ctx.voicePlan('browser', null, true, true),
  browser_no_sr_local_ready: ctx.voicePlan('browser', ready, false, true),
  browser_no_sr_nothing: ctx.voicePlan('browser', missing, false, false),
};

// VAD at 10 Hz: 0.5 s quiet room, 2 s speech, then quiet -> stops 1.6 s after speech
function run(levels, opts) {
  const v = ctx.makeVad(opts); let t = 0, r = 'waiting';
  for (const l of levels) { r = v.feed(l, t); if (r.startsWith('stop:')) return { result: r, at: t }; t += 100; }
  return { result: r, at: t };
}
const quiet = (n, l = 0.004) => Array(n).fill(l);
out.vad = {
  speech_then_pause: run([...quiet(5), ...quiet(20, 0.08), ...quiet(30)]),
  silence_only: run(quiet(120)),
  noisy_room_no_speech: run(quiet(120, 0.03)),                            // steady fan noise: learnt as the floor, never "speech"
  noisy_room_speech: run([...quiet(5, 0.03), ...quiet(20, 0.2), ...quiet(30, 0.03)]),
  long_speech: run(quiet(700, 0.1)),                                      // talking from the first moment for 70 s: the 60 s limit
  speaks_at_once: run([...quiet(25, 0.1), ...quiet(30)]),                  // speech from 0 ms is not learnt as noise
  // 7.2.1: a faint laptop microphone (floor 0.0003, speech 0.007) is heard as speech, not "no speech"
  faint_speech: run([...quiet(5, 0.0003), ...quiet(20, 0.007), ...quiet(30, 0.0003)]),
  very_quiet_room_no_speech: run(quiet(120, 0.0003)),
};
out.verdict = [[0, true], [0.0004, true], [0.002, true], [0.007, true], [0.02, true], [0.3, true], [0, false]].map(([p, m]) => ctx.micVerdict(p, m));
out.silent_message = ctx.micSilentMessage('Microphone Array (Intel Smart Sound)');

const V = (name, lang, local, def = false) => ({ name, lang, localService: local, default: def, voiceURI: name });
const voices = [V('Google US English', 'en-US', false, true), V('Microsoft Zira', 'en-US', true), V('Microsoft Hemant', 'hi-IN', true)];
out.voice = {
  default_en: ctx.pickVoice(voices, null, 'en-IN').name,
  saved: ctx.pickVoice(voices, 'Google US English', 'en').name,
  saved_gone: ctx.pickVoice(voices, 'Old voice', 'en').name,
  hindi: ctx.pickVoice(voices, null, 'hi-IN').name,
  only_online: ctx.pickVoice([V('Google UK', 'en-GB', false)], null, 'en').name,
  none: ctx.pickVoice([], null, 'en'),
};
// 7.1.1: the standalone Web app never needs a computer; desktop recognition doesn't depend on which AI answers
out.plan711 = {
  web_sr: ctx.voicePlan('local', null, true, false, 'web'),
  web_sr_engine_browser: ctx.voicePlan('browser', null, true, false, 'web'),
  web_no_sr: ctx.voicePlan('local', null, false, false, 'web'),
  desktop_default_platform: ctx.voicePlan('local', ready, true, true),
};
ctx.PLATFORM = 'web';
out.mic_web = ['NotAllowedError', 'NotFoundError', 'NotReadableError', 'insecure'].map((n) => ctx.micErrorMessage({ name: n }));
delete ctx.PLATFORM;
out.mic = ['NotAllowedError', 'NotFoundError', 'OverconstrainedError', 'NotReadableError', 'insecure', 'Weird'].map((n) => ctx.micErrorMessage({ name: n }));
out.sr = ['not-allowed', 'network', 'no-speech', 'audio-capture', 'xyz'].map((c) => ctx.srErrorMessage(c));
out.speak = ['voice', 'always', 'off'].map((m) => { ctx.state.speakMode = m; return [ctx.shouldSpeak(true), ctx.shouldSpeak(false)]; });

// the reader uses the chosen voice and its language
const spoken = [];
const synth = { speak: (u) => { spoken.push({ text: u.text, lang: u.lang, voice: u.voice && u.voice.name }); setTimeout(() => u.onend && u.onend(), 0); }, cancel: () => {} };
function U(t) { this.text = t; }
const r = ctx.makeReader(synth, U);
r.voice = voices[2]; r.load([{ text: 'नमस्ते', item: 0 }], 'msg:1'); r.play(0);
setTimeout(() => { out.reader = { spoken, state: r.state, owner: r.owner }; console.log(JSON.stringify(out)); }, 20);
