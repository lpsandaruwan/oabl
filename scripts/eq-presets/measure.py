"""Loudness, peaks, clipping and tonal balance of each track; and what the Crystalizer does to it."""
import sys, os, json, subprocess, re
import numpy as np
from scipy.signal import welch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chain

THIRD = np.array([25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600,
                  2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000])


def lufs(path):
    r = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-i", path, "-af", "ebur128=peak=true", "-f", "null", "-"],
                       capture_output=True, text=True).stderr
    tail = r[r.rfind("Summary:"):]
    i = float(re.search(r"I:\s+(-?[\d.]+) LUFS", tail).group(1))
    lra = float(re.search(r"LRA:\s+(-?[\d.]+) LU", tail).group(1))
    tp = float(re.search(r"Peak:\s+(-?[\d.]+|-inf) dBFS", tail).group(1))
    return i, lra, tp


def group(name):
    if name.startswith("Linkin"): return "LP"
    if name.startswith("Slipknot"): return "SK"
    return "other"


def main(folder, out):
    rows = []
    for name in sorted(os.listdir(folder)):
        path = os.path.join(folder, name)
        fs = chain.source_rate(path)
        x = chain.to_pcm16(chain.decode(path))
        a = np.abs(x)
        peak = a.max()
        rms = np.sqrt(np.mean(x ** 2))
        # flat tops: runs of 3+ equal samples within 0.05 dB of the track's own peak (clipping or brickwall)
        near = a >= peak * 10 ** (-0.05 / 20)
        runs = 0
        for ch in range(2):
            v = near[:, ch].astype(np.int8)
            d = np.diff(np.concatenate([[0], v, [0]]))
            starts, ends = np.where(d == 1)[0], np.where(d == -1)[0]
            runs += int(np.sum((ends - starts) >= 3))
        i, lra, tp = lufs(path)
        mono = x.mean(1)
        f, p = welch(mono, fs, nperseg=8192)
        bands = []
        for c in THIRD:
            m = (f >= c / 2 ** (1 / 6)) & (f < c * 2 ** (1 / 6))
            bands.append(10 * np.log10(p[m].sum() + 1e-20))
        bands = np.array(bands)
        bands -= bands[list(THIRD).index(1000)]
        crys = {}
        for k in (0.25, 0.5, 1.0):
            y = chain.crystalize(x, k, limit=False)
            ya = np.abs(y)
            crys[str(k)] = {"peak_db": float(20 * np.log10(ya.max())),
                            "over_knee_pct": float(100 * np.mean(ya > chain.KNEE)),
                            "over_1_pct": float(100 * np.mean(ya > 1.0)),
                            "p9999_db": float(20 * np.log10(np.quantile(ya, 0.99999)))}
        rows.append({"name": name, "group": group(name), "fs": fs, "seconds": round(len(x) / fs),
                     "peak_db": float(20 * np.log10(peak)), "rms_db": float(20 * np.log10(rms)),
                     "crest_db": float(20 * np.log10(peak / rms)), "lufs": i, "lra": lra, "true_peak": tp,
                     "flat_top_runs_per_min": runs / (len(x) / fs / 60),
                     "over_knee_pct_in": float(100 * np.mean(a > chain.KNEE)),
                     "bands": bands.tolist(), "crystal": crys})
        r = rows[-1]
        print(f"{r['group']:5} {name[:58]:58} {r['lufs']:6.1f} LUFS  TP {r['true_peak']:5.1f}  peak {r['peak_db']:5.2f}  "
              f"crest {r['crest_db']:4.1f}  flat {r['flat_top_runs_per_min']:7.1f}/min  >0.9 {r['over_knee_pct_in']:5.2f}%  "
              f"crys.25 {crys['0.25']['peak_db']:+5.2f} crys1 {crys['1.0']['peak_db']:+5.2f} ({crys['1.0']['over_knee_pct']:.1f}% >knee)", flush=True)
    json.dump({"third": THIRD.tolist(), "rows": rows}, open(out, "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
