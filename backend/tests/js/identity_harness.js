/* 7.1.0-dev.2: the web app's identity and voice-profile helpers from frontend/app.js, run in Node against the shared cases.
   Usage: node identity_harness.js <app.js> <identity_cases.json> -> JSON for test_identity_wake_712.py */
'use strict';
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync(process.argv[2], 'utf8');
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
function extract(name) {
  const start = src.indexOf(`\nfunction ${name}(`);
  if (start < 0) throw new Error(`missing function ${name}`);
  let i = src.indexOf('{', src.indexOf(')', start)), depth = 0;
  for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1); }
  throw new Error(`unbalanced ${name}`);
}
function extractConstBlock(name) {
  const start = src.indexOf(`\nconst ${name} =`);
  if (start < 0) throw new Error(`missing const ${name}`);
  let i = src.indexOf('=', start) + 1;
  while (src[i] === ' ') i++;
  if (src[i] !== '{') return src.slice(start, src.indexOf('\n', start + 1));
  let depth = 0;
  for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}' && --depth === 0) return src.slice(start, src.indexOf('\n', i)); }
  throw new Error(`unbalanced ${name}`);
}
const ctx = { console, Math, Number, String, Array };
vm.createContext(ctx);
vm.runInContext([extract('validateAssistantName'), extract('identityAnswer'), extractConstBlock('VOICE_STYLES'), extractConstBlock('FEMALE'),
  extractConstBlock('MALE'), extract('voiceGender'), extract('pickVoice'), extract('voiceForProfile'), extract('profileTuning'),
  'var VS = VOICE_STYLES;'].join('\n'), ctx);

const out = { name_mismatches: [], question_mismatches: [] };
for (const c of cases.names) {
  let got; try { got = ctx.validateAssistantName(c.in); } catch { got = null; }
  if (got !== c.out) out.name_mismatches.push([c.in, got]);
}
for (const c of cases.questions) {
  const a = ctx.identityAnswer(c.q, 'Nova');
  const got = a === null ? null : (a.startsWith('Yes.') ? 'rename' : 'name');
  if (got !== c.a) out.question_mismatches.push([c.q, got]);
}
const V = (name, lang, local = true) => ({ name, lang, localService: local, voiceURI: name, default: false });
const voices = [V('Microsoft Zira - English (United States)', 'en-US'), V('Microsoft David - English (United States)', 'en-US'),
  V('Google UK English Female', 'en-GB', false), V('Google UK English Male', 'en-GB', false), V('Microsoft Hemant', 'hi-IN'), V('Microsoft Kalpana', 'hi-IN')];
out.gender = [voices[0], voices[1], voices[2], voices[3], V('Some Voice', 'en-US')].map((v) => ctx.voiceGender(v));
out.female_voice = ctx.voiceForProfile(voices, { style: 'female', lang: 'en-US' }, 'en-US').name;
out.male_voice = ctx.voiceForProfile(voices, { style: 'male', lang: 'en-US' }, 'en-US').name;
out.hindi_voice = ctx.voiceForProfile(voices, { style: 'male', lang: 'hi-IN' }, 'en-US').name;
out.kept_choice = ctx.voiceForProfile(voices, { style: 'female', voiceURI: 'Google UK English Male' }, 'en-US').name;
out.robot = ctx.profileTuning({ style: 'robot', rate: 1 });
out.custom = ctx.profileTuning({ style: 'custom', rate: 1.3, pitch: 1.4, volume: 0.5 });
out.clamped = ctx.profileTuning({ style: 'custom', rate: 9, pitch: -3, volume: 7 });
console.log(JSON.stringify(out));
