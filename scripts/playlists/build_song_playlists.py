#!/usr/bin/env python3
"""Mood playlists chosen song by song (song_moods.txt), for OABL Music's playlist import.

Usage: build_song_playlists.py <music.db> <song_moods.txt> <out.json>

Every track in music.db must have a line in song_moods.txt; the build fails and lists any that
don't, so newly added music is never silently left out. Blank "Silent" tracks are skipped without
a line. The same song on two albums appears once per playlist; different versions (acoustic,
remix, live, demo) are different songs. Within a playlist, the same artist doesn't play twice in
a row where it can be avoided.
"""
import json, re, sqlite3, sys
from collections import OrderedDict, defaultdict

DB, MOODS, OUT = sys.argv[1:4]
NAMES = OrderedDict([("R", "Rush"), ("A", "Adrenaline"), ("E", "Energy"), ("H", "Happy"), ("M", "Good Morning"),
                     ("C", "Calm"), ("N", "Night Drive"), ("L", "Love Songs"), ("P", "Hip-Hop"), ("K", "Rock Anthems"),
                     ("D", "Club Mixes")])

moods = {}
for line in open(MOODS, encoding="utf-8"):
    line = line.split("#", 1)[0].strip()
    if not line: continue
    tid, codes = line.split()
    bad = set(codes) - set(NAMES) - {"X"}
    if bad: sys.exit(f"unknown mood code {bad} for track {tid}")
    moods[int(tid)] = codes

rows = sqlite3.connect(DB).execute("select _id, coalesce(artist,''), coalesce(title,''), uri from tracks").fetchall()
missing = [r for r in rows if r[0] not in moods and not re.fullmatch(r"silent( \d+)?", r[2].strip().lower())]
if missing:
    for r in missing: print(f"no mood line: {r[0]} {r[1]} - {r[2]}", file=sys.stderr)
    sys.exit(f"{len(missing)} tracks have no mood line; add them to {MOODS}")

def norm(s): return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()
EDITION = re.compile(r"[\(\[][^\)\]]*(remaster|radio edit|radio version|album version|single (mix|version)|lp edit|original radio|"
                     r"bonus|explicit|new edit|extended version|main edit)[^\)\]]*[\)\]]", re.I)
def song_key(artist, title):
    return norm(re.split(r"[/;&,]", artist)[0]) + "|" + norm(EDITION.sub("", title))
def lead(artist): return norm(re.split(r"[/;&,]| feat| ft\.", artist)[0])

lists = OrderedDict((name, []) for name in NAMES.values())
for tid, artist, title, uri in rows:
    for code in moods.get(tid, "X"):
        if code != "X": lists[NAMES[code]].append((tid, artist, title, uri))

def spread(tracks):
    groups, order = defaultdict(list), []
    for t in tracks:
        a = lead(t[1])
        if a not in groups: order.append(a)
        groups[a].append(t)
    order.sort(key=lambda a: -len(groups[a]))
    result = []
    while any(groups[a] for a in order):
        for a in order:
            if groups[a]: result.append(groups[a].pop(0))
    return result

playlists = []
for name, tracks in lists.items():
    seen, unique = set(), []
    for t in tracks:
        k = song_key(t[1], t[2])
        if k not in seen: seen.add(k); unique.append(t)
    ordered = unique if name == "Club Mixes" else spread(unique)
    playlists.append({"name": name, "tracks": [{"uri": t[3], "artist": t[1], "title": t[2]} for t in ordered]})

songs = {song_key(r[1], r[2]) for r in rows if moods.get(r[0], "X") != "X"}
placed = {song_key(t["artist"], t["title"]) for p in playlists for t in p["tracks"]}
json.dump({"version": 2, "source": "build_song_playlists.py", "replace": True, "playlists": playlists},
          open(OUT, "w"), ensure_ascii=False, indent=1)
print(f"tracks {len(rows)}, distinct songs {len(songs)}, songs in a playlist {len(songs & placed)}, "
      f"excluded {sum(1 for r in rows if moods.get(r[0], 'X') == 'X')}")
for p in playlists: print(f"  {p['name']:13s} {len(p['tracks']):4d}")
