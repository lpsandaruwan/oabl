"""Refit the presets from the 14-band curve (0.1.109) onto the 31-band curve (0.1.110).

DynamicsProcessing gives every FFT bin (46.875 Hz apart, 1024 points at 48 kHz) the gain of the band
whose cutoff range holds it, so with the 31 ISO bands the 31.5, 40, 50, 80 and 100 Hz bands own no bin.
EqualizerProfile.bandGains therefore gives each DP band the average of the 31-band curve over the
frequencies its bins cover. This script uses the same mapping, fits each 31-band preset so its
per-bin gains match what the 14-band preset plays today, rounds to 0.5 dB, and checks the steady-sine
response through the DP model (chain.py) against the old one.
"""
import os
import sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chain

N, FS = 1024, 48000
BIN = FS / N
HALF = N // 2 + 1
F14 = chain.FREQ
F31 = np.array([20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
                1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000], float)
SAMPLES = 16
TOP_BIN = int(20000 / BIN)  # fit up to 20 kHz

OLD = {
    "Car":      [1, 3.5, 1, .5, -1, -1.5, -2, -2, -.5, .5, 1.5, 1, 1.5, 1.5],
    "Nu Metal": [1.5, 4, .5, 0, -1.5, -2, -2.5, -2, -.5, .5, 1, 1, 1.5, 1.5],
    "Heavy":    [.5, 3.5, 0, -.5, -2, -2.5, -2.5, -2, -1, -.5, -1, -1, 0, .5],
    "Vocals":   [-.5, .5, 0, .5, -1, -1.5, -2, -1.5, 0, 1.5, 1.5, .5, 0, 0],
    "Smooth":   [1, 2.5, 1, .5, 0, -1, -1, -1, -.5, -1, -1.5, -2, -3, -3],
}


def cutoffs(freq):
    return np.append(np.sqrt(freq[:-1] * freq[1:]), 22000.0)


def owned(freq):
    """(first, last) bin each DP band owns, or None; as AOSP DPFrequency assigns them."""
    out, start = [], 0
    for cut in cutoffs(freq):
        stop = int(0.5 + cut * N / FS)
        last = min(stop, HALF - 1)
        out.append((start, last) if last >= start else None)
        start = stop + 1
    return out


def interp(freq, gains, hz):
    """EqualizerProfile.interpolate: linear in log frequency, held flat beyond the ends."""
    return np.interp(np.log(hz), np.log(freq), gains)


def band_gains(curve):
    """EqualizerProfile.bandGains: the curve averaged over the frequencies each DP band's bins cover."""
    gains = []
    for b, span in enumerate(owned(F31)):
        if span is None:
            gains.append(curve[b])
            continue
        lo = max(F31[0], (span[0] - .5) * BIN)
        hi = max(lo, (span[1] + .5) * BIN)
        pts = lo * (hi / lo) ** ((np.arange(SAMPLES) + .5) / SAMPLES)
        gains.append(float(np.mean(interp(F31, curve, pts))))
    return np.array(gains)


def per_bin(freq, dp_gains):
    """dB gain DynamicsProcessing puts on each bin for these band gains."""
    g = np.zeros(HALF)
    for b, span in enumerate(owned(freq)):
        if span is not None:
            g[span[0]:span[1] + 1] = dp_gains[b]
    return g


def new_bins(curve):
    return per_bin(F31, band_gains(curve))


def matrix():
    """new_bins is linear in the curve: one column per band."""
    base = new_bins(np.zeros(31))
    cols = []
    for b in range(31):
        e = np.zeros(31); e[b] = 1
        cols.append(new_bins(e) - base)
    return np.array(cols).T


A = matrix()
K = np.arange(1, TOP_BIN + 1)
W = np.sqrt(1.0 / K)  # equal weight per octave
D1 = np.diff(np.eye(31), 1, axis=0)  # flatness: a smooth, level line where bins can't decide
LAMBDA = 0.02


def cost(curve, goal):
    r = (A[K] @ curve - goal[K]) * W
    return float(r @ r + LAMBDA * np.sum((D1 @ curve) ** 2))


def fit(old):
    goal = per_bin(F14, np.array(old, float))
    M = np.vstack([A[K] * W[:, None], np.sqrt(LAMBDA) * D1])
    y = np.concatenate([goal[K] * W, np.zeros(D1.shape[0])])
    g = np.clip(np.linalg.lstsq(M, y, rcond=None)[0], -6, 6)
    g = np.round(g * 2) / 2
    best = cost(g, goal)
    improved = True
    while improved:
        improved = False
        for b in range(31):
            for d in (-.5, .5):
                t = g.copy(); t[b] = np.clip(t[b] + d, -6, 6)
                c = cost(t, goal)
                if c < best - 1e-12:
                    g, best, improved = t, c, True
    return g, goal


def response_bins(bin_db, freqs):
    """Steady sine gain through the DP overlap-add with these per-bin gains (chain.dp_eq's maths)."""
    keep = chain.bin_gains
    chain.bin_gains = lambda *_a, **_k: 10 ** (bin_db / 20)
    try:
        return chain.response(np.zeros(len(chain.FREQ)), freqs)
    finally:
        chain.bin_gains = keep


def main():
    grid = np.geomspace(32, 16000, 40)
    for name, old in OLD.items():
        g, goal = fit(old)
        was = response_bins(goal, grid)
        now = response_bins(new_bins(g), grid)
        dev = now - was
        print(f"{name:9s} {{{', '.join(f'{abs(v) if v == 0 else v:g}f' for v in g)}}}")
        print(f"          sine response vs 14 bands: max {np.max(np.abs(dev)):.2f} dB, rms {np.sqrt(np.mean(dev**2)):.2f} dB;"
              f" peak boost {max(old):g} -> {g.max():g} dB")
    dead = [F31[b] for b, s in enumerate(owned(F31)) if s is None]
    print("bands owning no bin:", dead)


if __name__ == "__main__":
    main()
