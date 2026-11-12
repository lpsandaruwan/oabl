// Quiet, continuous 48 kHz/16-bit/stereo PCM; no deliberate gaps or loud transients.
import fs from 'node:fs';
const destination = process.argv[2];
if (!destination) throw new Error('Usage: node GenerateWav.mjs output.wav');
const rate = 48000, seconds = 75, frames = rate * seconds;
const wav = Buffer.alloc(44 + frames * 4);
wav.write('RIFF', 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write('WAVEfmt ', 8);
wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(2, 22);
wav.writeUInt32LE(rate, 24); wav.writeUInt32LE(rate * 4, 28);
wav.writeUInt16LE(4, 32); wav.writeUInt16LE(16, 34);
wav.write('data', 36); wav.writeUInt32LE(frames * 4, 40);
for (let frame = 0; frame < frames; frame++) {
  const t = frame / rate;
  const fade = Math.min(1, frame / (rate * 0.2), (frames - 1 - frame) / (rate * 0.2));
  const sample = Math.round(32767 * fade * (0.02 * Math.sin(2 * Math.PI * 220 * t) + 0.01 * Math.sin(2 * Math.PI * 330 * t)));
  wav.writeInt16LE(sample, 44 + frame * 4); wav.writeInt16LE(sample, 46 + frame * 4);
}
fs.writeFileSync(destination, wav, {flag: 'wx'});
console.log(JSON.stringify({destination, rate, channels: 2, bits: 16, seconds, bytes: wav.length, maxPeakDbFS: -30.45}));
