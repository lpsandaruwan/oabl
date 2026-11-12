#!/usr/bin/env python3
"""Mood playlists for the AI box's music library.

Reads a copy of OABL's music.db (tracks table), sorts tracks into moods by artist and song,
and writes the playlist import file that OABL Music applies on start (oabl-playlists.json).
Usage: build_mood_playlists.py <music.db> <out.json>

Rules, so the lists stay listenable:
- blank and filler tracks (Crazy Town's "Silent" tracks, intros/outros, interludes, under 60 s)
  are never included; hour-long DJ mixes only go in "Club Mixes";
- the same song on several albums is listed once per playlist (first copy found);
- within a playlist the same artist never plays twice in a row where it can be avoided.
"""
import json, re, sqlite3, sys
from collections import OrderedDict, defaultdict

DB, OUT = sys.argv[1], sys.argv[2]
rows = sqlite3.connect(DB).execute(
    "select _id, coalesce(artist,''), coalesce(title,''), coalesce(album,''), folder, duration, uri from tracks").fetchall()

def norm(s): return re.sub(r"[^a-z0-9]+", " ", s.lower()).strip()
EDITION = re.compile(r"[\(\[][^\)\]]*(remaster|radio edit|radio version|album version|single (mix|version)|lp edit|original radio|"
                     r"bonus|explicit|new edit|extended version|main edit|edit)[^\)\]]*[\)\]]", re.I)
def song_key(artist, title):
    # Edition labels don't make a different song; acoustic, remix and live versions do.
    t = norm(EDITION.sub("", title))
    return norm(artist.split("/")[0].split(";")[0].split("&")[0]) + "|" + t

COMPILATIONS = ("Dance Workout", "Привет", "Cardio 4")
def is_filler(artist, title, seconds):
    t = title.lower()
    return (seconds < 60 or re.fullmatch(r"silent( \d+)?", t) or t in ("intro", "outro www.crazytown.com", "hidden song", "foreword")
            or "interlude" in t or "piano solo" in t or "(intro)" in t or "acapella" in t or "overture" in t)
def is_mix(seconds): return seconds > 15 * 60

def has(title, *words):
    # Whole words only: the song "O" must not match every title containing an "o".
    t = f" {norm(title)} "
    return any(f" {norm(w)} " in t for w in words)

# --- song-level picks (titles are matched loosely, case- and punctuation-insensitive) ---
LP_FAST = ["one step closer", "faint", "bleed it out", "given up", "papercut", "points of authority", "lying from you",
           "burn it down", "a place for my head", "by myself", "forgotten", "runaway", "no more sorrow", "hit the floor",
           "don t stay", "victimized", "war", "guilty all the same", "rebellion", "wretches and kings", "blackout",
           "when they come for me", "lies greed misery", "until it breaks", "the catalyst", "one more light remix"]
LP_SOFT = ["one more light", "leave out all the rest", "shadow of the day", "iridescent", "valentine s day", "roads untraveled",
           "the little things give you away", "in between", "hands held high", "my december", "the messenger", "powerless",
           "sharp edges", "halfway right", "nobody can save me", "invisible", "sorry for now"]
LP_NIGHT = ["castle of glass", "burning in the skies", "lost in the echo", "iridescent", "waiting for the end", "numb",
            "in the end", "crawling", "breaking the habit", "what i ve done", "somewhere i belong", "new divide",
            "heavy", "talking to myself", "battle symphony", "until it s gone", "final masquerade"]
EV_SOFT = ["my immortal", "hello", "lithium", "lacrymosa", "swimming home", "your star", "anywhere", "away from me", "eternal",
           "field of innocence", "like you", "good enough", "my heart is broken", "oceans", "lost in paradise", "secret door",
           "the other side", "whisper"]
EV_HEAVY = ["bring me to life", "going under", "sweet sacrifice", "what you want", "everybody s fool", "tourniquet", "haunted",
            "taking over me", "imaginary", "call me when you re sober", "weight of the world", "the only one", "made of stone",
            "erase this", "sick", "end of the dream", "the change", "snow white queen", "lose control", "cloud nine"]
ID_ROCK = ["believer", "radioactive", "thunder", "whatever it takes", "natural", "enemy", "warriors", "battle cry", "monster",
           "bad liar", "machine", "bones", "shots", "sharks", "friction", "boomerang", "zero", "digital", "thief", "follow you",
           "it s time", "on top of the world", "rise up", "walking the wire", "start over", "real life"]
ID_CALM = ["birds", "dream", "next to me", "amsterdam", "selene", "hopeless opus", "i don t know why", "love", "only",
           "i ll make it up to you", "yesterday", "it comes back to you", "second chances", "smoke and mirrors", "release",
           "polaroid", "born to be yours", "skipping stones", "i m so sorry", "cool out"]
ID_MORNING = ["on top of the world", "it s time", "rise up", "start over", "walking the wire", "i bet my life", "next to me",
              "hear me", "gold", "west coast", "who we are", "round and round", "tiptoe", "natural", "born to be yours"]
CP_UP = ["a sky full of stars", "adventure of a lifetime", "hymn for the weekend", "viva la vida", "paradise", "fun",
         "a head full of dreams", "charlie brown", "every teardrop is a waterfall", "speed of sound", "clocks", "magic",
         "princess of china", "we pray"]
CP_CALM = ["the scientist", "fix you", "yellow", "everglow", "o", "ink", "true love", "midnight", "trouble", "in my place",
           "talk", "always in my head", "don t panic", "magic"]
WEEKND_RUSH = ["blinding lights", "take my breath", "can t feel my face", "starboy", "i feel it coming", "heartless",
               "one right now", "timeless", "save your tears"]
WEEKND_CALM = ["earned it", "call out my name", "die for you", "the hills", "love me harder", "pray for me", "smile"]
TDG_SOFT = ["never too late", "the good life", "the high road", "lost in you", "world so cold", "the mountain"]
SLIPKNOT_SOFT = ["vermilion pt 2", "snuff", "dead memories"]
CREED_SOFT = ["with arms wide open", "one last breath", "my sacrifice", "higher", "what s this life for", "rain", "torn"]
AVRIL_UP = ["girlfriend", "sk8er boi", "complicated", "what the hell", "here s to never growing up", "smile", "rock n roll",
            "my happy ending", "bite me", "love it when you hate me", "head above water", "i m a mess"]
AVRIL_CALM = ["i m with you", "when you re gone", "keep holding on", "nobody s home", "losing grip"]
BSB_UP = ["everybody", "larger than life", "i want it that way", "as long as you love me", "the call", "get down",
          "we ve got it goin on", "quit playing games", "bigger", "one in a million", "straight through my heart"]
NSYNC_UP = ["bye bye bye", "it s gonna be me", "pop", "tearin up my heart", "i want you back", "girlfriend", "i ll never stop"]
JT_UP = ["can t stop the feeling", "rock your body", "sexyback", "summer love", "suit tie", "filthy", "my love",
         "4 minutes", "give it to me", "love never felt so good", "ayo technology", "love sex magic", "holy grail", "not a bad thing"]
JT_CALM = ["cry me a river", "mirrors", "what goes around", "say something", "dead and gone"]
RIH_UP = ["pon de replay", "umbrella", "don t stop the music", "dont stop the music", "shut up drive", "s o s", "disturbia", "rehab", "live your life",
          "break it of", "good girl gone bad", "whipping my hair", "let me", "we ride", "hotness", "emergency", "princess of china"]
RIH_CALM = ["take a bow", "unfaithful", "hate that i love you", "if i never see your face again", "final goodbye", "cry",
            "is this love", "if its lovin that you want"]
NELLY_UP = ["promiscuous", "maneater", "say it right", "i m like a bird", "forca", "turn off the light", "powerless", "night is young",
            "manos al aire", "give it to me", "girlfriend in this city", "shit on the radio", "faded", "who wants to be alone"]
NELLY_CALM = ["all good things", "try", "in god s hands", "broken strings", "fotografia", "stars", "te busque", "quando",
              "island of wonder", "sacrifice"]
WESTLIFE_UP = ["uptown girl", "i have a dream", "world of our own", "flying without wings", "if i let you go", "swear it again",
               "queen of my heart", "my love", "beautiful world", "what about now", "wide open", "home"]
SHAYNE_UP = ["summer of 69", "you make me feel brand new", "i believe in a thing called love", "hit the ground running",
             "gonna be alright", "no u hang up", "u got me so", "that s my goal", "take your mama", "just be good to me"]
SWITCHFOOT_ROCK = ["meant to live", "dare you to move", "this is your life", "oh gravity", "we are one tonight", "awakening",
                   "american dream", "more than fire", "revenge", "gone", "head over heels"]
THIRTY_ROCK = ["this is war", "closer to the edge", "attack", "night of the hunter", "kings and queens", "search and destroy",
               "the kill", "from yesterday", "conquistador", "do or die", "up in the air", "end of all days", "hurricane",
               "vox populi", "a beautiful lie", "hunter", "r evolve", "battle of one", "edge of the earth", "capricorn", "the race"]
THIRTY_NIGHT = ["night of the hunter", "bright lights", "northern lights", "city of angels", "hurricane", "alibi", "100 suns",
                "the kill rebirth", "a beautiful lie acoustic", "the story", "was it a dream", "end of the beginning", "93 million miles",
                "l490", "escape", "pyres of varanasi", "convergence", "birth", "oblivion"]
EMINEM_HYPE = ["lose yourself", "without me", "the real slim shady", "till i collapse", "business", "just lose it", "my name is",
               "white america", "soldier", "kill you", "rabbit run", "8 mile", "the way i am", "like toy soldiers",
               "cleanin out my closet", "never enough", "shake that", "smack that", "superman", "encore", "the re up", "no apologies"]
CRAZY_TOWN_HEAVY = ["toxic", "revolving door", "think fast", "darkside", "b boy 2000", "black cloud", "battle cry", "hurt you so bad",
                    "decorated", "change", "megatron", "born to raise hell", "skulls and stars", "candy coated", "take it to the bridge"]
CRAZY_TOWN_GROOVE = ["butterfly", "players", "lollipop porn", "only when i m drunk", "beautiful", "you re the one", "come inside",
                     "west coast", "the keys", "backpack", "lemonface", "my place", "light the way", "a little more time", "ashes", "baby you don t know"]
TATU = ["all about us", "not gonna get us", "all the things she said", "gomenasai", "friend or foe", "cosmos", "loves me not",
        "dangerous and moving", "perfect enemy", "sacrifice", "we shout", "craving", "vsya moya lubov", "objzienka nol"]

# Compilation tracks: dance/EDM is the default (Energy); these lean other ways.
COMP_HAPPY = ["shut up and dance", "calm down", "stumblin in", "wake me up", "good feeling", "i m good", "happy now", "forever young",
              "bad habits", "shivers", "8 days", "sun comes up", "lobster telephone", "nanana", "fever dream", "stay", "universe",
              "somewhere close to heaven", "be someone", "jolene", "texas hold em", "hold on to now", "forever", "it boy",
              "never be lonely", "just the way you are", "there she goes", "murder on the dancefloor", "abracadabra", "honey",
              "i like it", "in your eyes", "whatever", "miles away", "overdrive", "dive into me", "hotter now", "jet plane",
              "rasputin", "i run", "i took a pill in ibiza", "clap your hands", "galaxy", "prada", "where is my husband",
              "i don t wanna wait", "lean on me", "push up", "don t you worry child"]
COMP_NIGHT = ["world is mine", "comes and goes", "vision blurred", "shades of blue", "insomnia", "tears", "weak", "home",
              "sweater weather", "the days", "hollow", "ride", "assumptions", "somewhere else", "feel again", "go back", "always knew",
              "close friends", "think about us", "somedays", "my place", "zone", "million miles away", "freaky 1", "you me",
              "greece 2000", "invisible", "dreamin", "neurose", "elastic heart", "the sound of silence", "stargazing", "graceland",
              "let me be", "die for me", "the fate of ophelia", "lose control", "liar", "all that really matters", "illusion", "be mine"]
COMP_RUSH = ["sandstorm", "turn down for what", "satisfaction", "kernkraft 400", "baddadan", "boost up", "take it off", "neck",
             "goosebumps", "seven nation army", "bella ciao", "thunder", "smells like teen spirit", "gangster", "tsunami",
             "harder better faster stronger", "go", "the business", "bring me to life", "rock that body", "push", "move it",
             "release the pressure", "let s get fkd up", "paid to exist", "big boom dance", "drop it like it s hot", "rockstar",
             "threnody", "l amour toujours", "in my mind", "hall of fame", "dance for me", "ferrari", "boyz in paris", "alors on danse",
             "favour", "woof", "insane", "crank that", "stompin", "free your mind", "marianela", "it s not right but it s okay"]

lists = OrderedDict((name, []) for name in [
    "Rush", "Adrenaline", "Energy", "Happy", "Good Morning", "Calm", "Night Drive", "Love Songs", "Hip-Hop", "Rock Anthems", "Club Mixes"])
placed = set()
def add(name, track): lists[name].append(track); placed.add(track[0])
# Where an artist's song goes when no rule above names it.
ARTIST_DEFAULT = [("linkin park", "Rock Anthems"), ("30 seconds to mars", "Rock Anthems"), ("crazy town", "Rock Anthems"),
                  ("switchfoot", "Rock Anthems"), ("creed", "Rock Anthems"), ("papa roach", "Adrenaline"), ("slipknot", "Adrenaline"),
                  ("three days grace", "Adrenaline"), ("limp bizkit", "Adrenaline"), ("evanescence", "Night Drive"),
                  ("imagine dragons", "Rock Anthems"), ("the weeknd", "Night Drive"), ("rihanna", "Energy"), ("nelly furtado", "Happy"),
                  ("justin timberlake", "Happy"), ("coldplay", "Calm"), ("avril", "Happy"), ("backstreet", "Love Songs"),
                  ("nsync", "Love Songs"), ("westlife", "Love Songs"), ("shayne ward", "Love Songs"), ("celine", "Love Songs"),
                  ("eminem", "Hip-Hop"), ("50 cent", "Hip-Hop"), ("t.a.t.u", "Night Drive")]

skipped = 0
for tid, artist, title, album, folder, duration, uri in rows:
    seconds = (duration or 0) / 1000
    a = artist.lower()
    track = (tid, artist, title, uri)
    if is_mix(seconds):
        add("Club Mixes", track); continue
    if is_filler(artist, title, seconds) or a == "unknown artist":
        skipped += 1; continue
    compilation = any(c in folder for c in COMPILATIONS)
    if compilation:
        if has(title, *COMP_RUSH): add("Rush", track); add("Energy", track)
        elif has(title, *COMP_NIGHT): add("Night Drive", track)
        elif has(title, *COMP_HAPPY): add("Happy", track); add("Energy", track)
        else: add("Energy", track)
        if has(title, *["wake me up", "sun comes up", "stumblin in", "calm down", "universe", "somewhere close to heaven",
                        "bad habits", "fever dream", "8 days", "lobster telephone", "be someone", "hold on to now"]):
            add("Good Morning", track)
        continue
    if "linkin park" in a:
        if has(title, *LP_FAST): add("Rush", track); add("Adrenaline", track)
        if has(title, *LP_SOFT): add("Calm", track)
        if has(title, *LP_NIGHT): add("Night Drive", track)
        add("Rock Anthems", track) if has(title, *(LP_FAST + LP_NIGHT)) else None
    elif "slipknot" in a:
        add("Calm" if has(title, *SLIPKNOT_SOFT) else "Adrenaline", track)
        if not has(title, *SLIPKNOT_SOFT) and has(title, "duality", "psychosocial", "before i forget", "wait and bleed",
                                                   "the devil in i", "spit it out", "pulse of the maggots"):
            add("Rush", track)
    elif "papa roach" in a:
        if has(title, "cheez", "cofee", "dendrilopis", "i love babies", "lenny s", "lulu", "mama s dress"): skipped += 1; continue
        if has(title, "scars", "broken home", "not that beautiful", "where did the angels go", "periscope", "gravity",
               "leader of the broken hearts", "falling apart", "help"):
            add("Night Drive", track)
        else:
            add("Adrenaline", track)
            if has(title, "last resort", "born for greatness", "elevate", "face everything and rise", "kick in the teeth",
                   "getting away with murder", "to be loved", "burn", "who do you trust", "top of the world", "infest",
                   "between angels and insects", "time and time again", "she loves me not", "m 80", "life is a bullet",
                   "american dreams", "still swingin", "won t let up", "give me back my life"):
                add("Rush", track); add("Rock Anthems", track)
    elif "three days grace" in a:
        if has(title, *TDG_SOFT): add("Night Drive", track)
        else: add("Adrenaline", track); add("Rock Anthems", track)
        if has(title, "animal i have become", "riot", "painkiller", "i am machine", "pain", "break", "chalk outline"): add("Rush", track)
    elif "limp bizkit" in a:
        if has(title, "behind blue eyes", "build a bridge", "lean on me", "home sweet home"): add("Calm", track)
        else:
            add("Adrenaline", track)
            if has(title, "rollin", "break stuff", "my way", "my generation", "nookie", "take a look around", "boiler", "faith"):
                add("Rush", track); add("Rock Anthems", track)
    elif "evanescence" in a:
        if has(title, *EV_SOFT): add("Calm", track); add("Night Drive", track)
        if has(title, *EV_HEAVY): add("Adrenaline", track); add("Night Drive", track)
        if has(title, "bring me to life", "going under", "call me when you re sober", "sweet sacrifice", "everybody s fool", "what you want"):
            add("Rock Anthems", track)
    elif "creed" in a:
        if has(title, *CREED_SOFT): add("Calm", track)
        else: add("Rock Anthems", track)
        add("Rock Anthems", track) if has(title, "higher", "my sacrifice", "what s this life for", "one last breath", "with arms wide open") else None
        if has(title, "overcome", "hide", "bullets", "a thousand faces"): add("Adrenaline", track)
    elif "crazy town" in a:
        if has(title, *CRAZY_TOWN_HEAVY): add("Adrenaline", track)
        if has(title, *CRAZY_TOWN_GROOVE): add("Night Drive", track)
        if has(title, "butterfly", "revolving door", "toxic", "drowning", "hollywood babylon", "players"): add("Rock Anthems", track)
    elif "30 seconds to mars" in a:
        if has(title, *THIRTY_ROCK): add("Rock Anthems", track)
        if has(title, *THIRTY_NIGHT): add("Night Drive", track)
        if has(title, "attack", "this is war", "closer to the edge", "search and destroy", "do or die", "conquistador",
               "the race", "vox populi", "night of the hunter", "kings and queens"): add("Rush", track)
        if has(title, "attack", "search and destroy", "a modern myth", "battle of one", "r evolve", "fallen"): add("Adrenaline", track)
        if has(title, "a beautiful lie acoustic", "the story", "was it a dream", "100 suns", "a modern myth"): add("Calm", track)
    elif "imagine dragons" in a or "dan reynolds" in a or "kygo" in a:
        if has(title, *ID_ROCK): add("Rock Anthems", track)
        if has(title, "believer", "thunder", "whatever it takes", "natural", "radioactive", "warriors", "battle cry", "enemy", "monster", "bones"):
            add("Rush", track)
        if has(title, *ID_CALM): add("Calm", track)
        if has(title, *ID_MORNING): add("Good Morning", track)
        if has(title, "demons", "bad liar", "monster", "thunder", "bleeding out", "machine", "wrecked", "selene", "digital"): add("Night Drive", track)
    elif "coldplay" in a:
        if has(title, *CP_UP): add("Happy", track); add("Good Morning", track)
        if has(title, *CP_CALM): add("Calm", track)
        if has(title, "midnight", "clocks", "magic", "o", "ink", "talk"): add("Night Drive", track)
        if has(title, "viva la vida", "fix you", "the scientist", "yellow", "clocks", "paradise", "in my place"): add("Love Songs", track) if has(title, "yellow", "the scientist", "fix you", "in my place", "true love") else None
    elif "the weeknd" in a:
        if has(title, *WEEKND_RUSH): add("Rush", track); add("Happy", track) if has(title, "blinding lights", "i feel it coming", "can t feel my face") else None
        if has(title, *WEEKND_CALM): add("Love Songs", track)
        add("Night Drive", track)
    elif "eminem" in a or "50 cent" in a or a in ("jay-z", "t.i."):
        add("Hip-Hop", track)
        if "eminem" in a and has(title, *EMINEM_HYPE): add("Rush", track); add("Adrenaline", track) if has(title, "kill you", "soldier", "white america", "business", "till i collapse", "never enough", "the re up") else None
        if "50 cent" in a and has(title, "in da club", "p i m p", "candy shop", "disco inferno", "just a lil bit", "i get money", "straight to the bank", "hustler"):
            add("Energy", track)
        if has(title, "mockingbird", "when i m gone", "stan", "sing for the moment", "21 questions", "dead and gone", "baby by me"):
            add("Night Drive", track)
    elif "avril" in a:
        if has(title, *AVRIL_UP): add("Happy", track)
        if has(title, *AVRIL_CALM): add("Calm", track)
        if has(title, "here s to never growing up", "smile", "complicated", "girlfriend", "what the hell", "rock n roll"): add("Good Morning", track)
        if has(title, "sk8er boi", "girlfriend", "what the hell", "bite me", "love it when you hate me", "rock n roll"): add("Rock Anthems", track)
    elif "backstreet" in a:
        if has(title, *BSB_UP): add("Happy", track)
        else: add("Love Songs", track); add("Calm", track)
        if has(title, "i want it that way", "as long as you love me", "shape of my heart", "all i have to give", "i ll never break your heart",
               "more than that", "show me the meaning", "incomplete", "drowning", "inconsolable", "the one"): add("Love Songs", track)
    elif "nsync" in a:
        if has(title, *NSYNC_UP): add("Happy", track); add("Energy", track) if has(title, "bye bye bye", "pop", "it s gonna be me") else None
        else: add("Love Songs", track); add("Calm", track)
    elif "justin timberlake" in a or "timbaland" in a or "madonna" in a or "michael jackson" in a or "ciara" in a:
        if has(title, *JT_UP): add("Happy", track); add("Energy", track) if has(title, "sexyback", "rock your body", "4 minutes", "give it to me", "ayo technology", "filthy") else None
        if has(title, *JT_CALM): add("Night Drive", track)
        if has(title, "mirrors", "not a bad thing", "say something", "love never felt so good", "my love"): add("Love Songs", track)
        if has(title, "can t stop the feeling", "summer love", "not a bad thing", "love never felt so good"): add("Good Morning", track)
    elif "rihanna" in a:
        if has(title, "acoustic"): add("Calm", track)
        elif has(title, *RIH_CALM): add("Love Songs", track); add("Calm", track) if has(title, "take a bow", "unfaithful", "cry", "final goodbye") else None
        if has(title, *RIH_UP) and not has(title, "acoustic"):
            add("Energy", track) if has(title, "remix", "rmx", "disturbia", "don t stop the music", "dont stop the music", "rehab", "shut up drive", "pon de replay", "s o s", "hotness") else None
            add("Happy", track) if has(title, "pon de replay", "umbrella", "shut up drive", "don t stop the music", "dont stop the music", "s o s", "live your life", "let me", "we ride", "break it of") and not has(title, "remix", "rmx") else None
        if has(title, "disturbia", "umbrella", "unfaithful", "rehab"): add("Night Drive", track) if not has(title, "remix", "rmx") else None
    elif "nelly furtado" in a:
        if has(title, *NELLY_UP): add("Happy", track); add("Energy", track) if has(title, "promiscuous", "maneater", "give it to me", "who wants to be alone", "night is young") else None
        if has(title, *NELLY_CALM): add("Calm", track)
        if has(title, "i m like a bird", "say it right", "try", "all good things", "forca"): add("Good Morning", track) if has(title, "i m like a bird", "forca", "try") else add("Night Drive", track)
    elif "westlife" in a:
        add("Love Songs", track)
        if has(title, *WESTLIFE_UP): add("Happy", track) if has(title, "uptown girl", "i have a dream", "world of our own", "queen of my heart", "swear it again") else None
        if has(title, "flying without wings", "uptown girl", "world of our own", "i have a dream", "beautiful world", "home"): add("Good Morning", track)
        if has(title, "you raise me up", "mandy", "against all odds", "my love", "if i let you go", "safe", "lighthouse", "what about now", "last mile of the way", "home"):
            add("Calm", track)
    elif "shayne ward" in a:
        if has(title, *SHAYNE_UP): add("Happy", track); add("Good Morning", track) if has(title, "summer of 69", "you make me feel brand new", "gonna be alright", "hit the ground running") else None
        else: add("Love Songs", track); add("Calm", track) if has(title, "unchained melody", "right here waiting", "if tomorrow never comes", "some tears never dry", "over the rainbow", "you re not alone", "when a child is born", "breathless", "stand by me", "no promises", "until you", "i cry") else None
    elif "celine" in a or "barbra" in a:
        add("Love Songs", track)
        if has(title, "i m alive", "i drove all night", "river deep", "a new day has come", "love can move mountains", "taking chances", "call the man"):
            add("Happy", track) if has(title, "i m alive", "i drove all night", "river deep", "a new day has come", "love can move mountains") else None
        else: add("Calm", track)
        if has(title, "a new day has come", "i m alive", "that s the way it is"): add("Good Morning", track)
    elif "switchfoot" in a:
        if has(title, *SWITCHFOOT_ROCK): add("Rock Anthems", track)
        if has(title, "this is your life", "dare you to move", "awakening", "we are one tonight", "stars", "meant to live"): add("Good Morning", track)
        if has(title, "stars acoustic", "gone", "dirty second hands", "oh gravity"): add("Calm", track) if has(title, "stars acoustic") else None
    elif "t.a.t.u" in a:
        add("Night Drive", track)
        if has(title, "all about us", "friend or foe", "we shout", "dangerous and moving", "loves me not", "craving"): add("Energy", track)
        if has(title, "gomenasai", "sacrifice", "perfect enemy", "cosmos"): add("Calm", track) if has(title, "gomenasai") else None
    elif "iridium beats" in a:  # covers of the KPop Demon Hunters soundtrack: bright, upbeat
        add("Happy", track); add("Energy", track)
        if has(title, "golden", "free"): add("Good Morning", track)
    elif "dj simple simon" in a:
        add("Club Mixes", track)
    else:
        add("Energy", track)

    if tid not in placed:
        # Short album interludes stay out; anything else goes to the artist's usual list.
        if seconds < 130 and "linkin park" in a: skipped += 1; continue
        for name_part, target in ARTIST_DEFAULT:
            if name_part in a: add(target, track); break
        else: add("Energy", track)

# --- dedupe by song within each list, then spread artists ---
def spread(tracks):
    by_artist = defaultdict(list)
    order = []
    for t in tracks:
        lead = norm(t[1].split("/")[0].split(";")[0].split("&")[0].split(",")[0])
        if lead not in by_artist: order.append(lead)
        by_artist[lead].append(t)
    # Largest groups first so they get spread evenly; round-robin across artists.
    order.sort(key=lambda a: -len(by_artist[a]))
    result, last = [], None
    while any(by_artist[a] for a in order):
        for a in order:
            if by_artist[a]:
                result.append(by_artist[a].pop(0))
    return result

playlists = []
for name, tracks in lists.items():
    seen, unique = set(), []
    for t in tracks:
        k = song_key(t[1], t[2])
        if k in seen: continue
        seen.add(k); unique.append(t)
    ordered = spread(unique) if name != "Club Mixes" else unique
    playlists.append({"name": name, "tracks": [{"uri": t[3], "artist": t[1], "title": t[2]} for t in ordered]})

covered = {t["uri"] for p in playlists for t in p["tracks"]}
json.dump({"version": 1, "source": "build_mood_playlists.py", "replace": True, "playlists": playlists},
          open(OUT, "w"), ensure_ascii=False, indent=1)
print(f"tracks {len(rows)}, skipped filler {skipped}, in at least one playlist {len(covered)}")
for p in playlists: print(f"  {p['name']:13s} {len(p['tracks']):4d}")
