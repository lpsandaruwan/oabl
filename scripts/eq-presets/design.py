"""Fit the 14 band gains so the response DynamicsProcessing really produces matches each target,
then run every preset through the whole chain on every track and report peaks."""
import sys, os, json
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chain

GRID = np.geomspace(32, 16000, 34)
CRYS = {"off": 0.0, "light": 0.25, "medium": 0.5, "strong": 1.0}

# Target response in true frequency (Hz: dB), including the Crystalizer's own treble lift.
# 0.1.109: the owner heard vocals and guitars "tubed", like cheap boxed midrange speakers, on the
# 0.1.108 curves (which cut 2.5–4 kHz and left 630 Hz–1.6 kHz flat, a hump in between). Now the boxy
# 400 Hz–1.3 kHz zone comes down and the 2.5–6.3 kHz presence zone comes up relative to it.
TARGETS = {
    "Car":      ("off", {32: 0.5, 45: 1.5, 60: 2.5, 80: 3, 110: 2.5, 150: 1, 200: 0, 280: -1, 400: -1.5, 630: -2,
                           1000: -2, 1300: -1.5, 1600: -0.5, 2000: 0, 2500: 0.5, 3200: 1, 4000: 1.5, 5000: 1.5,
                           6300: 1, 8000: 1, 10000: 1.5, 16000: 1.5}),
    "Nu Metal": ("off", {32: 1, 45: 2, 60: 3, 80: 3.5, 110: 2.5, 150: 0.5, 200: -0.5, 280: -1.5, 400: -2, 630: -2.5,
                           850: -2.5, 1000: -2, 1300: -1.5, 1600: -0.5, 2000: 0, 2500: 0.5, 3200: 1, 4000: 1,
                           5000: 1, 6300: 1, 8000: 1, 10000: 1.5, 16000: 1.5}),
    "Heavy":    ("off",   {32: 0, 45: 1, 60: 2.5, 80: 3, 110: 2, 150: 0, 200: -1, 280: -2, 400: -2.5, 630: -2.5,
                           1000: -2, 1300: -1.5, 1600: -1, 2500: -0.5, 3200: -0.5, 4000: -1, 5000: -1, 6300: -1,
                           8000: -0.5, 10000: 0, 16000: 0.5}),
    "Vocals":   ("off",   {32: -1, 60: 0, 100: 0.5, 200: 0, 280: -1, 400: -1.5, 630: -2, 1000: -1.5, 1300: -1,
                           1600: 0, 2000: 1, 2500: 1.5, 3200: 1.5, 4000: 1.5, 5000: 1, 6300: 0.5, 10000: 0, 16000: 0}),
    "Smooth":   ("off",   {32: 0.5, 60: 2, 100: 2, 150: 1, 250: 0, 400: -1, 630: -1, 1000: -1, 1600: -0.5,
                           2000: -0.5, 3000: -1, 5000: -1.5, 8000: -2.5, 12000: -3, 16000: -3}),
}


def headroom(g):
    """EqualizerProfile.headroom: largest boost, bands from 4 kHz counted 2 dB higher, plus 0.5 dB."""
    return max(0.0, max(v + (2.0 if f >= 4000 else 0.0) for v, f in zip(g, chain.FREQ))) + 0.5


def target_curve(points):
    f = np.array(sorted(points)); v = np.array([points[k] for k in sorted(points)])
    return np.interp(np.log(GRID), np.log(f), v)


def fit(goal):
    g = np.interp(np.log(chain.FREQ), np.log(GRID), goal)
    for _ in range(6):
        r = chain.response(g, GRID)
        J = np.zeros((len(GRID), 14))
        for b in range(14):
            d = g.copy(); d[b] += 1
            J[:, b] = chain.response(d, GRID) - r
        lam = 0.05
        step = np.linalg.solve(J.T @ J + lam * np.eye(14), J.T @ (goal - r))
        g = np.clip(g + step, -6, 6)
    return np.round(g * 2) / 2


def main(folder, out):
    result = {}
    files = sorted(os.listdir(folder))
    tracks = []
    for f in files:
        fs = chain.source_rate(os.path.join(folder, f))
        tracks.append((f, fs, chain.to_pcm16(chain.decode(os.path.join(folder, f)))))
    for name, (level, pts) in TARGETS.items():
        goal = target_curve(pts) - chain.crystal_db(CRYS[level], GRID)
        g = fit(goal)
        eff = chain.response(g, GRID) + chain.crystal_db(CRYS[level], GRID)
        err = eff - target_curve(pts)
        boost = headroom(g)
        k = CRYS[level]
        pre = 1 / (1 + 2 * k)
        peaks = []
        for f, fs, x in tracks:
            y = chain.to_pcm16(chain.crystalize(x, k, pre)) if k else x
            y = chain.to_48k(y, fs)
            z = chain.dp_eq(y, g, input_db=-boost)
            peaks.append(20 * np.log10(np.abs(z).max()))
        peaks = np.array(peaks)
        result[name] = {"crystalizer": level, "gains": g.tolist(), "headroom": boost,
                        "fit_err_max": float(np.abs(err).max()), "peaks": peaks.tolist()}
        print(f"{name:9} crys {level:5} gains {' '.join(f'{v:+.1f}' for v in g)}  headroom {boost:.1f}  "
              f"fit err max {np.abs(err).max():.2f} dB  output peak max {peaks.max():+.2f} dBFS "
              f"(median {np.median(peaks):+.2f}); tracks over 0: {int((peaks > 0).sum())}", flush=True)
    json.dump(result, open(out, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
