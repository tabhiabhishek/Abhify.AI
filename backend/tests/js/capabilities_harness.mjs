/* 7.0.2: browser capability report + storage-aware model choice from frontend/ondevice.js, in Node with fake
   navigator objects (no browser or GPU). Usage: node capabilities_harness.mjs <frontend dir> -> JSON for pytest. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const dir = process.argv[2];
const tmp = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'abhify-cap-')), 'ondevice.mjs');
fs.copyFileSync(path.join(dir, 'ondevice.js'), tmp);
const od = await import(pathToFileURL(tmp).href);
fs.rmSync(path.dirname(tmp), { recursive: true, force: true });

const adapter = { info: { vendor: 'qualcomm', architecture: 'adreno-7xx' }, limits: { maxBufferSize: 1073741824 } };
const phone = { gpu: { requestAdapter: async () => adapter }, hardwareConcurrency: 8, deviceMemory: 8,
  storage: { estimate: async () => ({ quota: 4_000_000_000, usage: 100_000_000 }) } };
const noGpu = { hardwareConcurrency: 4, storage: { estimate: async () => ({ quota: 2_000_000_000, usage: 0 }) } };
const noAdapter = { gpu: { requestAdapter: async () => null }, hardwareConcurrency: 4 };
const win = { isSecureContext: true, WebAssembly: {}, crossOriginIsolated: false };

const out = {
  phone: await od.capabilities(phone, win),
  noGpu: await od.capabilities(noGpu, { ...win, isSecureContext: false }),
  noAdapter: await od.capabilities(noAdapter, win),
  choose: {
    roomy: od.chooseModel({ webgpu: true, deviceMemory: 8, storageFreeMB: 3900 }),
    tight: od.chooseModel({ webgpu: true, deviceMemory: 8, storageFreeMB: 900 }),      // big doesn't fit, 1B does
    full: od.chooseModel({ webgpu: true, deviceMemory: 8, storageFreeMB: 500 }),       // nothing fits
    unknown: od.chooseModel({ webgpu: true, deviceMemory: 8, storageFreeMB: null }),   // storage not reported: memory decides
  },
};
process.stdout.write(JSON.stringify(out));
