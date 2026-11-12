"""A copy of OABL's music chain, for measuring, not listening.

decode (16-bit, like ExoPlayer's c2 decoders) -> Crystalizer (Crystalizer.java) -> 48 kHz mixer
-> DynamicsProcessing pre-EQ exactly as AOSP android13 DPFrequency.cpp does it:
   1024-point blocks (20 ms preferred frame at 48 kHz rounded up to a power of two), 512 hop,
   sqrt-Hann analysis and synthesis windows, one flat gain per FFT bin, bins assigned to bands by
   round(cutoff * N / fs) with cutoffs at the geometric mean of the band centres.
"""
import subprocess
import numpy as np
from scipy.signal import resample_poly

FREQ = np.array([40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000], float)
CUT = np.append(np.sqrt(FREQ[:-1] * FREQ[1:]), 22000.0)
KNEE = 0.9


def decode(path, rate=None):
    cmd = ["ffmpeg", "-v", "error", "-nostdin", "-i", path, "-f", "f32le", "-ac", "2"]
    if rate:
        cmd += ["-ar", str(rate)]
    cmd += ["-"]
    raw = subprocess.run(cmd, capture_output=True, check=True).stdout
    x = np.frombuffer(raw, np.float32).reshape(-1, 2).astype(np.float64)
    return x


def source_rate(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
                          "stream=sample_rate", "-of", "csv=p=0", path], capture_output=True, text=True).stdout
    return int(out.strip().splitlines()[0].strip(","))


def to_pcm16(x):
    return np.round(np.clip(x, -1, 1) * 32767) / 32768.0


def soft_limit(y):
    m = np.abs(y)
    room = 1 - KNEE
    bent = KNEE + room * np.tanh((m - KNEE) / room)
    return np.where(m <= KNEE, y, np.sign(y) * bent)


def crystalize(x, k, pre_gain=1.0, limit=True):
    """Crystalizer.next per channel; pre_gain is applied to the input first (the new headroom)."""
    if k == 0 and pre_gain == 1.0:
        return x
    x = x * pre_gain
    prev = np.vstack([np.zeros((1, x.shape[1])), x[:-1]])
    y = x + (x - prev) * k
    return soft_limit(y) if limit else y


def bin_gains(gains_db, fs=48000, n=1024, input_db=0.0):
    half = n // 2 + 1
    f = np.ones(half)
    start = 0
    for b in range(len(FREQ)):
        stop = int(0.5 + CUT[b] * n / fs)
        f[start:min(stop, half - 1) + 1] = 10 ** (gains_db[b] / 20)
        start = stop + 1
    return f * 10 ** (input_db / 20)


def dp_eq(x, gains_db, fs=48000, n=1024, input_db=0.0):
    """Overlap-add exactly like DPFrequency (no MBC, limiter off)."""
    hop = n // 2
    w = np.sqrt(0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n))  # periodic Hann, sqrt
    g = bin_gains(gains_db, fs, n, input_db)
    out = np.zeros((x.shape[0] + n, x.shape[1]))
    pad = np.vstack([np.zeros((hop, x.shape[1])), x, np.zeros((n, x.shape[1]))])
    frames = (pad.shape[0] - n) // hop
    for ch in range(x.shape[1]):
        idx = np.arange(n)[None, :] + hop * np.arange(frames)[:, None]
        blocks = pad[idx, ch] * w
        spec = np.fft.rfft(blocks, axis=1) * g
        y = np.fft.irfft(spec, n, axis=1) * w
        acc = np.zeros(pad.shape[0])
        np.add.at(acc, idx, y)
        out[:, ch] = acc[hop:hop + out.shape[0]]
    return out[:x.shape[0]]


def to_48k(x, fs):
    if fs == 48000:
        return x
    if fs == 44100:
        return resample_poly(x, 160, 147, axis=0)
    from math import gcd
    g = gcd(48000, fs)
    return resample_poly(x, 48000 // g, fs // g, axis=0)


def response(gains_db, freqs, fs=48000):
    """Steady sine gain in dB through the DP EQ at each frequency."""
    t = np.arange(int(fs * 0.4)) / fs
    out = []
    for f in freqs:
        s = 0.25 * np.sin(2 * np.pi * f * t)
        y = dp_eq(np.stack([s, s], 1), gains_db, fs)[:, 0]
        mid = slice(len(t) // 4, 3 * len(t) // 4)
        out.append(20 * np.log10(np.sqrt(np.mean(y[mid] ** 2)) / np.sqrt(np.mean(s[mid] ** 2))))
    return np.array(out)


def crystal_db(k, f, fs=44100):
    w = 2 * np.pi * f / fs
    return 20 * np.log10(np.abs(1 + k * (1 - np.exp(-1j * w))))
