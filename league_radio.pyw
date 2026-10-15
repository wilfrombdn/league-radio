"""League Radio - tiny audio-only player for Worlds (and other LoL Esports) broadcasts.

Gets Twitch's audio_only HLS rendition via streamlink (as a library) and plays it with
the Windows built-in media player - no mpv/VLC/ffmpeg needed, and no video is downloaded.
Includes a Worlds schedule shown in your local time zone.
"""
import asyncio
import json
import os
import re
import sys
import threading
import time
import tkinter as tk
import urllib.request
from datetime import datetime, timezone
from tkinter import ttk

HERE = os.path.dirname(os.path.abspath(getattr(sys, "frozen", False) and sys.executable or __file__))
RES = getattr(sys, "_MEIPASS", HERE)
CONFIG = os.path.join(HERE, "settings.json")
SNAPSHOT = os.path.join(RES, "schedule_snapshot.json")
API = ("https://esports-api.lolesports.com/persisted/gw/getSchedule?hl=en-US"
       "&leagueId=98767975604431411")
API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"  # public key used by lolesports.com
RETRY_SECS = 45


def load_json(path, default):
    try:
        with open(path, encoding="utf-8-sig") as f:
            return json.load(f)
    except Exception:
        return default


def get(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


HISTORY = os.path.join(os.environ.get("APPDATA", HERE), "LeagueRadio", "history.json")
RANK = {"unstarted": 0, "inProgress": 1, "completed": 2}


def merge(old, new):
    """Merge match lists by id. Results are never lost: a stored result is only replaced by an
    equal or more-final one, and matches are never deleted."""
    by_id = {m["id"]: m for m in old}
    for m in new:
        o = by_id.get(m["id"])
        if o is None or RANK.get(m["state"], 0) >= RANK.get(o["state"], 0):
            by_id[m["id"]] = m
        else:
            o["t"] = m["t"]
    return sorted(by_id.values(), key=lambda m: m["t"])


def save_history(matches):
    try:
        os.makedirs(os.path.dirname(HISTORY), exist_ok=True)
        with open(HISTORY, "w", encoding="utf-8") as f:
            json.dump(matches, f, separators=(",", ":"))
    except OSError:
        pass


def stored_matches():
    """Bundled snapshot merged with everything saved from earlier runs (works offline)."""
    return merge(load_json(SNAPSHOT, []), load_json(HISTORY, []))


def fetch_schedule():
    """Live schedule + results from lolesports, merged into the permanent local history."""
    old = stored_matches()
    try:
        h = {"x-api-key": API_KEY}
        first = get(API, h)["data"]["schedule"]
        pages = [first]
        for key in ("newer", "older"):
            tok = first["pages"].get(key)
            for _ in range(12):
                if not tok:
                    break
                d = get(f"{API}&pageToken={tok}", h)["data"]["schedule"]
                pages.append(d)
                tok = d["pages"].get(key)
        new = []
        for pg in pages:
            for e in pg["events"]:
                m = e.get("match")
                if not m:
                    continue
                t = m["teams"]
                res = lambda x: (x.get("result") or {}).get("gameWins", -1)
                new.append({"id": m["id"], "t": e["startTime"], "stage": e["blockName"],
                            "bo": m["strategy"]["count"], "state": e["state"],
                            "a": t[0]["code"], "b": t[1]["code"], "an": t[0]["name"], "bn": t[1]["name"],
                            "aw": res(t[0]), "bw": res(t[1])})
        if new:
            merged = merge(old, new)
            save_history(merged)
            return merged, True
    except Exception:
        pass
    return old, False


def stream_url(channel):
    from streamlink import Streamlink
    s = Streamlink()
    s.set_option("twitch-disable-ads", True)
    streams = s.streams(f"https://twitch.tv/{channel}")
    st = streams.get("audio_only") or streams.get("worst")
    if not st:
        raise RuntimeError("offline")
    return st.url


TEST_CHANNELS = ["lolesports", "riotgames", "lck", "lec", "caedrel", "tarik", "ibai", "gaules",
                 "xqc", "kaicenat", "jynxzi", "monstercat", "lofi_girl", "shroud"]


def find_live_channel():
    """First channel from the list that is live right now, to test the same audio_only pipeline."""
    from concurrent.futures import ThreadPoolExecutor

    def live(ch):
        try:
            stream_url(ch)
            return True
        except Exception:
            return False
    with ThreadPoolExecutor(len(TEST_CHANNELS)) as ex:
        flags = list(ex.map(live, TEST_CHANNELS))
    return next((c for c, f in zip(TEST_CHANNELS, flags) if f), None)


OFFICIAL = [
    ("English \u00b7 Riot Games", "riotgames"), ("English \u00b7 LoL Esports", "lolesports"),
    ("Korean / English \u00b7 LCK", "lck"), ("English \u00b7 LEC", "lec"), ("English \u00b7 LCS", "lcs"),
    ("English \u00b7 LPL English", "lplenglish"), ("Chinese \u00b7 LPL", "lpl"),
    ("Chinese (Taiwan) \u00b7 LoL Esports TW", "lolesportstw"), ("Portuguese \u00b7 CBLOL", "cblol"),
    ("Portuguese \u00b7 Riot Games Brasil", "riotgamesbrasil"), ("Spanish \u00b7 LVP", "lvpes"),
    ("Spanish \u00b7 Riot Games LATAM", "riotgameslatam"), ("Spanish \u00b7 LoL Esports LA", "lolesportsla"),
    ("French \u00b7 OTP", "otplol_"), ("Russian \u00b7 Riot Games RU", "riotgamesru"),
    ("Vietnamese \u00b7 Riot Games VN", "riotgamesvn"), ("Arabic \u00b7 LoL Esports AR", "lolesports_ar"),
]
AUTO_LABEL = "Auto \u00b7 first live official stream"
LIVE_API = "https://esports-api.lolesports.com/persisted/gw/getLive?hl=en-US"
CHANNELS = os.path.join(os.path.dirname(HISTORY), "channels.json")


def discover_official():
    """Ask Riot which Twitch streams the live Worlds broadcast uses right now and remember them."""
    try:
        data = get(LIVE_API, {"x-api-key": API_KEY})["data"]["schedule"]["events"]
        found = []
        for e in data:
            if (e.get("league") or {}).get("slug") != "worlds":
                continue
            for s in e.get("streams") or []:
                if s.get("provider") == "twitch" and s.get("parameter"):
                    lang = (s.get("mediaLocale") or {}).get("englishName") or s.get("locale", "")
                    found.append([f"{lang} \u00b7 official live", s["parameter"].lower()])
        if found:
            os.makedirs(os.path.dirname(CHANNELS), exist_ok=True)
            with open(CHANNELS, "w", encoding="utf-8") as f:
                json.dump(found, f)
    except Exception:
        pass


def all_channels():
    """Discovered official streams first, then the built-in list (label, login)."""
    seen, out = set(), []
    for label, login in [tuple(x) for x in load_json(CHANNELS, [])] + OFFICIAL:
        if login not in seen:
            seen.add(login)
            out.append((label, login))
    return out


def first_live():
    """Probe every official channel in parallel; return (login, url) of the first live one."""
    from concurrent.futures import ThreadPoolExecutor
    discover_official()
    found = [l for _, l in [tuple(x) for x in load_json(CHANNELS, [])]]  # streams Riot lists for live Worlds
    logins = found or [l for _, l in all_channels()]

    def probe(ch):
        try:
            return ch, stream_url(ch)
        except Exception:
            return None
    for group in ([logins, [l for _, l in all_channels()]] if found else [logins]):
        with ThreadPoolExecutor(len(group)) as ex:
            hit = next((r for r in ex.map(probe, group) if r), None)
        if hit:
            return hit
    return None


GREEN, RED = "#22C55E", "#FF4D4D"
GQL = "https://gql.twitch.tv/gql"
TWITCH_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"  # Twitch web player public id


def live_channels(logins):
    """Which of these Twitch channels are streaming right now (one small request, no video)."""
    try:
        q = "query{users(logins:[%s]){login stream{id}}}" % ",".join(json.dumps(l) for l in logins)
        req = urllib.request.Request(GQL, json.dumps({"query": q}).encode(), {"Client-ID": TWITCH_ID})
        with urllib.request.urlopen(req, timeout=8) as r:
            users = json.load(r)["data"]["users"]
        return {u["login"].lower() for u in users if u and u.get("stream")}
    except Exception:
        return None


def match_status(e, now):
    """None (not started / finished), 'live' (API says in progress) or 'guess' (start time has
    passed but the API has not flipped yet; assumes a typical running time for the series length)."""
    if e.get("state") == "completed":
        return None
    age = now - datetime.fromisoformat(e["t"].replace("Z", "+00:00")).timestamp()
    if age < 0:
        return None
    if e.get("state") == "inProgress" and age < 12 * 3600:
        return "live"
    return "guess" if age < (e["bo"] * 50 + 40) * 60 else None


def channel_login(text):
    """Dropdown label or typed name -> twitch login ('auto' means pick the first live official one)."""
    text = text.strip()
    if text.startswith("Auto"):
        return "auto"
    m = re.search(r"\(([^()]+)\)\s*$", text)
    login = (m.group(1) if m else text).lower().replace("twitch.tv/", "").strip("/ ")
    return login or "auto"


class Player:
    """Thin wrapper over the Windows.Media.Playback.MediaPlayer (handles HLS natively)."""

    def __init__(self):
        from winsdk.windows.media.playback import MediaPlayer
        self.mp = MediaPlayer()
        self.mp.auto_play = True
        self.failed = False
        self.mp.add_media_failed(lambda *_: setattr(self, "failed", True))

    def play(self, url):
        from winsdk.windows.foundation import Uri
        from winsdk.windows.media.core import MediaSource
        self.failed = False
        self.mp.source = MediaSource.create_from_uri(Uri(url))
        self.mp.play()

    def stop(self):
        try:
            self.mp.pause()
            self.mp.source = None
        except Exception:
            pass

    def set_volume(self, v):
        self.mp.volume = max(0.0, min(1.0, v / 100))

    def alive(self):
        return not self.failed


BG, PANEL, PANEL2, LINE = "#000000", "#101010", "#202020", "#2E2E2E"
WHITE, TEXT, DIM, FAINT = "#FFFFFF", "#EDEDED", "#8C8C8C", "#5C5C5C"
FONT = "Segoe UI"
SS = 4  # supersampling factor for anti-aliased images


# ---------- anti-aliased image helpers (drawn large, shrunk smooth) ----------
def _finish(im, size):
    from PIL import Image, ImageTk
    return ImageTk.PhotoImage(im.resize(size, Image.LANCZOS))


def play_button_img(size, playing, hover, pressed=False):
    from PIL import Image, ImageDraw
    big = size * SS
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    fill = "#2E2E2E" if pressed else (PANEL2 if hover else PANEL)
    pad = SS * 3
    d.ellipse((pad, pad, big - pad, big - pad), fill=fill, outline=WHITE, width=SS * 3)
    c = big / 2
    if playing:
        r = big * 0.15
        d.rounded_rectangle((c - r, c - r, c + r, c + r), radius=SS * 4, fill=WHITE)
    else:
        w, h = big * 0.17, big * 0.20
        x0 = c - w * 0.55
        d.polygon([(x0, c - h), (x0, c + h), (x0 + w * 1.9, c)], fill=WHITE)
        d.line([(x0, c - h), (x0, c + h), (x0 + w * 1.9, c), (x0, c - h)], fill=WHITE, width=SS * 2, joint="curve")
    return _finish(im, (size, size))


def glow_img(size):
    """Green dot with a soft halo."""
    from PIL import Image, ImageDraw, ImageFilter
    big = size * SS
    halo = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    c, r = big / 2, big * 0.3
    ImageDraw.Draw(halo).ellipse((c - r, c - r, c + r, c + r), fill=GREEN)
    halo = halo.filter(ImageFilter.GaussianBlur(big * 0.1))
    ImageDraw.Draw(halo).ellipse((c - r * 0.6, c - r * 0.6, c + r * 0.6, c + r * 0.6), fill="#4ADE80")
    return _finish(halo, (size, size))


def rounded_img(w, h, fill, outline, radius=14):
    from PIL import Image, ImageDraw
    im = Image.new("RGBA", (w * SS, h * SS), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((SS, SS, w * SS - SS, h * SS - SS), radius=radius * SS,
                                         fill=fill, outline=outline, width=SS)
    return _finish(im, (w, h))


def knob_img(size, hot=False):
    from PIL import Image, ImageDraw
    big = size * SS
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    ImageDraw.Draw(im).ellipse((SS, SS, big - SS, big - SS), fill=WHITE if not hot else "#CFCFCF")
    return _finish(im, (size, size))


# ---------- small custom widgets ----------
class Seg(tk.Frame):
    """Segmented pill control. Fixed-size cells: selecting only changes colours, nothing moves."""

    def __init__(self, parent, var, options, **kw):
        super().__init__(parent, bg=LINE, padx=1, pady=1, **kw)
        self.var = var
        self.labels = {}
        self.set_options(options)
        var.trace_add("write", lambda *a: self.refresh())

    def set_options(self, options):
        for w in self.winfo_children():
            w.destroy()
        self.labels = {}
        for o in options:
            lb = tk.Label(self, text=o, font=(FONT, 8, "bold"), padx=11, pady=4, cursor="hand2", bd=0)
            lb.pack(side="left")
            lb.bind("<Button-1>", lambda e, o=o: self.var.set(o))
            self.labels[o] = lb
        self.refresh()

    def refresh(self):
        for o, lb in self.labels.items():
            on = self.var.get() == o
            lb.configure(bg=WHITE if on else PANEL, fg=BG if on else DIM)


class Slider(tk.Canvas):
    """Thin volume slider with a smooth round knob."""

    def __init__(self, parent, var, command, width=230):
        super().__init__(parent, width=width, height=26, bg=BG, highlightthickness=0, cursor="hand2")
        self.var, self.command, self.w = var, command, width
        self.knob = knob_img(16)
        self.knob_hot = knob_img(16, True)
        self.bind("<Button-1>", self.move)
        self.bind("<B1-Motion>", self.move)
        self.draw()

    def move(self, e):
        v = int(max(0, min(100, (e.x - 8) / (self.w - 16) * 100)))
        self.var.set(v)
        self.draw()
        self.command(v)

    def draw(self):
        self.delete("all")
        x = 8 + (self.w - 16) * self.var.get() / 100
        self.create_line(8, 13, self.w - 8, 13, fill=LINE, width=4, capstyle="round")
        if x > 8:
            self.create_line(8, 13, x, 13, fill=WHITE, width=4, capstyle="round")
        self.create_image(x, 13, image=self.knob)


class App:
    W = 400

    def __init__(self, root):
        self.root = root
        cfg = load_json(CONFIG, {})
        self.want_play = False
        self.gen = 0
        self.player = None
        self.events = []
        self.state = "stopped"  # stopped | busy | playing | wait
        self.hover = False
        self.live, self.last_live = set(), 0

        root.title("League Radio")
        root.geometry(f"{self.W}x580")
        root.resizable(False, True)
        root.minsize(self.W, 520)
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", self.quit)

        # images are built once so hover / state changes are instant (no redraw flicker)
        s = ttk.Style()
        s.theme_use("clam")
        s.configure("Dark.TCombobox", fieldbackground=PANEL, background=LINE, foreground=TEXT, arrowcolor=TEXT,
                    bordercolor=LINE, lightcolor=PANEL, darkcolor=PANEL, insertcolor=TEXT,
                    selectbackground=PANEL, selectforeground=TEXT)
        s.map("Dark.TCombobox", fieldbackground=[("readonly", PANEL)], bordercolor=[("focus", WHITE)])
        root.option_add("*TCombobox*Listbox.background", PANEL)
        root.option_add("*TCombobox*Listbox.foreground", TEXT)
        root.option_add("*TCombobox*Listbox.selectBackground", "#3A3A3A")
        root.option_add("*TCombobox*Listbox.selectForeground", WHITE)
        root.option_add("*TCombobox*Listbox.font", (FONT, 9))
        self.imgs = {(p, h): play_button_img(112, p, h) for p in (False, True) for h in (False, True)}
        self.glow = glow_img(22)
        self.imgs_pressed = {p: play_button_img(112, p, True, True) for p in (False, True)}

        # ---- tab bar: two equal cells side by side, fixed size, underline marks the active one ----
        bar = tk.Frame(root, bg=BG)
        bar.pack(fill="x", padx=24, pady=(14, 0))
        bar.grid_columnconfigure((0, 1), weight=1, uniform="tabs")
        self.tab_lbl, self.tab_line = {}, {}
        for i, name in enumerate(("RADIO", "SCHEDULE")):
            lb = tk.Label(bar, text=name, bg=BG, fg=DIM, font=(FONT, 9, "bold"), cursor="hand2", pady=8)
            lb.grid(row=0, column=i, sticky="ew")
            ln = tk.Frame(bar, bg=LINE, height=2)
            ln.grid(row=1, column=i, sticky="ew")
            lb.bind("<Button-1>", lambda e, n=name: self.show_tab(n))
            self.tab_lbl[name], self.tab_line[name] = lb, ln

        body = tk.Frame(root, bg=BG)
        body.pack(fill="both", expand=True, padx=24)
        self.pages = {"RADIO": tk.Frame(body, bg=BG), "SCHEDULE": tk.Frame(body, bg=BG)}
        self.build_radio(self.pages["RADIO"], cfg)
        self.build_schedule(self.pages["SCHEDULE"])

        self.apply_top()
        self.draw_btn()
        self.set_state("stopped", "Stopped")
        self.show_tab("RADIO")
        self.events = stored_matches()
        self.refresh_filters()
        self.render()
        threading.Thread(target=self.refresh_schedule, daemon=True).start()
        self.tick()

    def show_tab(self, name):
        for n, page in self.pages.items():
            if n == name:
                page.pack(fill="both", expand=True)
            else:
                page.pack_forget()
            on = n == name
            self.tab_lbl[n].configure(fg=WHITE if on else DIM)
            self.tab_line[n].configure(bg=WHITE if on else LINE)

    # ---- Radio tab ----
    def build_radio(self, f, cfg):
        tk.Label(f, text="LEAGUE RADIO", bg=BG, fg=WHITE, font=(FONT, 18, "bold")).pack(pady=(26, 0))
        tk.Label(f, text="Audio-only  ·  low data", bg=BG, fg=DIM, font=(FONT, 9)).pack(pady=(2, 0))

        self.cv = tk.Canvas(f, width=112, height=112, bg=BG, highlightthickness=0, cursor="hand2")
        self.cv.pack(pady=(22, 0))
        self.btn_item = self.cv.create_image(56, 56, image=self.imgs[(False, False)])
        self.cv.bind("<Button-1>", lambda e: self.cv.itemconfigure(self.btn_item, image=self.imgs_pressed[self.want_play]))
        self.cv.bind("<ButtonRelease-1>", lambda e: self.toggle())
        self.cv.bind("<Enter>", lambda e: self.set_hover(True))
        self.cv.bind("<Leave>", lambda e: self.set_hover(False))

        srow = tk.Frame(f, bg=BG)
        srow.pack(pady=(14, 0))
        self.dot = tk.Canvas(srow, width=10, height=10, bg=BG, highlightthickness=0)
        self.dot.pack(side="left", padx=(0, 8))
        self.status = tk.StringVar(value="Stopped")
        tk.Label(srow, textvariable=self.status, bg=BG, fg=TEXT, font=(FONT, 10), width=24,
                 anchor="w").pack(side="left")

        self.vol = tk.IntVar(value=cfg.get("volume", 60))
        vrow = tk.Frame(f, bg=BG)
        vrow.pack(pady=(14, 0))
        tk.Label(vrow, text="VOL", bg=BG, fg=DIM, font=(FONT, 8, "bold")).pack(side="left", padx=(0, 10))
        Slider(vrow, self.vol, self.on_vol).pack(side="left")
        self.vol_lbl = tk.Label(vrow, text=f"{self.vol.get()}", bg=BG, fg=DIM, font=(FONT, 8, "bold"), width=4)
        self.vol_lbl.pack(side="left")

        card_w, card_h = self.W - 48, 100
        self.card_img = rounded_img(card_w, card_h, PANEL, LINE)
        self.card = tk.Canvas(f, width=card_w, height=card_h, bg=BG, highlightthickness=0)
        self.card.pack(pady=(22, 0))
        self.card.create_image(0, 0, image=self.card_img, anchor="nw")
        cx = card_w / 2
        self.next_tag = self.card.create_text(cx, 22, text="NEXT MATCH", fill=DIM, font=(FONT, 8, "bold"))
        self.next_match = self.card.create_text(cx, 52, text="", fill=WHITE, font=(FONT, 17, "bold"))
        self.next_time = self.card.create_text(cx, 80, text="", fill=DIM, font=(FONT, 9))

        foot = tk.Frame(f, bg=BG)
        foot.pack(side="bottom", fill="x", pady=(0, 18))
        saved = cfg.get("channel", AUTO_LABEL)
        self.channel = tk.StringVar(value=AUTO_LABEL if saved in ("lolesports", "") else saved)
        r1 = tk.Frame(foot, bg=BG)
        r1.pack(fill="x", pady=(0, 10))
        tk.Label(r1, text="STREAM", bg=BG, fg=DIM, font=(FONT, 8, "bold")).pack(side="left", padx=(0, 6))
        self.live_cv = tk.Canvas(r1, width=22, height=22, bg=BG, highlightthickness=0)
        self.live_cv.pack(side="left", padx=(0, 4))
        self.live_item = self.live_cv.create_image(11, 11)
        self.combo = ttk.Combobox(r1, textvariable=self.channel, values=self.channel_labels(),
                                  style="Dark.TCombobox", font=(FONT, 9))
        self.combo.pack(side="left", fill="x", expand=True, ipady=3)
        self.combo.configure(postcommand=self.open_dropdown)
        self.channel.trace_add("write", lambda *a: self.show_live_dot())
        r2 = tk.Frame(foot, bg=BG)
        r2.pack(fill="x")
        foot = r2
        test = tk.Label(foot, text="TEST", bg=BG, fg=WHITE, font=(FONT, 8, "bold"), padx=12, pady=4,
                        cursor="hand2", highlightthickness=1, highlightbackground=WHITE)
        test.pack(side="left", padx=2)
        test.bind("<Button-1>", lambda e: self.test_stream())
        test.bind("<Enter>", lambda e: test.configure(bg=WHITE, fg=BG))
        test.bind("<Leave>", lambda e: test.configure(bg=BG, fg=WHITE))
        self.top = tk.BooleanVar(value=cfg.get("ontop", True))
        tk.Checkbutton(foot, text="On top", variable=self.top, command=self.apply_top, bg=BG, fg=DIM,
                       selectcolor=BG, activebackground=BG, activeforeground=TEXT, bd=0, highlightthickness=0,
                       font=(FONT, 9)).pack(side="right")

    # ---- Schedule tab ----
    def build_schedule(self, f):
        top = tk.Frame(f, bg=BG)
        top.pack(fill="x", pady=(14, 0))
        self.view = tk.StringVar(value="Upcoming")
        Seg(top, self.view, ["Upcoming", "Results", "All"]).pack(side="left")
        self.count = tk.StringVar()
        tk.Label(top, textvariable=self.count, bg=BG, fg=DIM, font=(FONT, 8)).pack(side="right")

        row2 = tk.Frame(f, bg=BG)
        row2.pack(fill="x", pady=(8, 0))
        self.year = tk.StringVar(value=str(datetime.now().year))
        tk.Label(row2, text=f"{self.year.get()} Schedule", bg=BG, fg=WHITE, font=(FONT, 11, "bold")).pack(side="left")
        self.tzlabel = tk.StringVar()
        tk.Label(row2, textvariable=self.tzlabel, bg=BG, fg=DIM, font=(FONT, 8)).pack(side="right")
        for v in (self.year, self.view):
            v.trace_add("write", lambda *a: self.render())

        box = tk.Frame(f, bg=BG)
        box.pack(fill="both", expand=True, pady=(8, 14))
        self.txt = tk.Text(box, bg=BG, fg=TEXT, relief="flat", wrap="none", cursor="arrow", width=10, height=10,
                           font=(FONT, 9), highlightthickness=0, padx=0, state="disabled", spacing1=3, spacing3=3,
                           tabs=(150, "right", 190, "center", 222, "left", 282, "left"), insertwidth=0,
                           selectbackground=BG, inactiveselectbackground=BG)
        self.txt.pack(fill="both", expand=True)
        t = self.txt
        t.tag_configure("day", foreground=WHITE, font=(FONT, 10, "bold"), spacing1=14, spacing3=4)
        t.tag_configure("daystage", foreground=DIM, font=(FONT, 8))
        t.tag_configure("row", lmargin1=8, lmargin2=8)
        t.tag_configure("time", foreground=DIM, font=(FONT, 9))
        t.tag_configure("win", foreground=WHITE, font=(FONT, 9, "bold"))
        t.tag_configure("lose", foreground=DIM)
        t.tag_configure("vs", foreground=FAINT)
        t.tag_configure("name", foreground=TEXT, font=(FONT, 9, "bold"))
        t.tag_configure("score", foreground=WHITE, font=(FONT, 9, "bold"))
        t.tag_configure("meta", foreground=FAINT, font=(FONT, 8))
        t.tag_configure("live", foreground=RED, font=(FONT, 9, "bold"))
        t.tag_configure("guess", foreground="#B05050", font=(FONT, 9, "bold"))
        t.tag_configure("next", background=PANEL2)

    def refresh_filters(self):
        pass  # the year is a fixed title, not an option

    # ---- look ----
    def set_hover(self, h):
        self.hover = h
        self.draw_btn()

    def draw_btn(self):
        self.cv.itemconfigure(self.btn_item, image=self.imgs[(self.want_play, self.hover)])

    def set_state(self, state, text):
        self.state = state
        color = {"stopped": FAINT, "busy": DIM, "wait": DIM, "playing": WHITE}[state]
        self.dot.delete("all")
        if state == "wait":
            self.dot.create_oval(1, 1, 9, 9, outline=color, width=1.5)
        else:
            self.dot.create_oval(1, 1, 9, 9, fill=color, outline=color)
        self.status.set(text)

    # ---- schedule ----
    def refresh_schedule(self):
        ev, live = fetch_schedule()
        self.events = ev
        self.root.after(0, lambda: (self.refresh_filters(), self.render()))

    @staticmethod
    def local(t):
        return datetime.fromisoformat(t.replace("Z", "+00:00")).astimezone()  # system time zone

    def render(self):
        now = time.time()
        z = datetime.now().astimezone().strftime("%z")
        self.tzlabel.set(f"Local time  UTC{z[:3]}:{z[3:]}")
        year, view = self.year.get(), self.view.get()
        evs = [e for e in self.events if self.local(e["t"]).strftime("%Y") == year]
        done = [e for e in evs if e.get("state") == "completed"]
        self.count.set(f"{len(done)} of {len(evs)} played")
        rows = [e for e in evs if view == "All" or (view == "Results") == (e.get("state") == "completed")]
        if view == "Results":
            rows.reverse()

        stages = {}
        for e in rows:
            d = self.local(e["t"]).date()
            stages.setdefault(d, [])
            if e["stage"] not in stages[d]:
                stages[d].append(e["stage"])

        t = self.txt
        y0 = t.yview()[0]
        t.configure(state="normal")
        t.delete("1.0", "end")
        last_day, nxt_mark = None, None
        for e in rows:
            dt = self.local(e["t"])
            ts = dt.timestamp()
            finished = e.get("state") == "completed"
            status = match_status(e, now)
            live = status is not None
            if dt.date() != last_day:
                last_day = dt.date()
                t.insert("end", dt.strftime("%A, %b ") + str(dt.day), "day")
                t.insert("end", "   " + " / ".join(stages[last_day]) + "\n", ("day", "daystage"))
            is_next = nxt_mark is None and not finished and (ts > now or live)
            base = ("row", "next") if is_next else ("row",)
            if is_next:
                nxt_mark = t.index("end-1c")
            clock = dt.strftime("%I:%M %p").lstrip("0")
            t.insert("end", clock, base + ("time",))
            aw, bw = e.get("aw", -1), e.get("bw", -1)
            if finished and aw >= 0:
                ta, tb = ("win", "lose") if aw > bw else ("lose", "win")
                t.insert("end", "\t" + e["a"], base + (ta,))
                t.insert("end", f"\t{aw} – {bw}\t", base + ("score",))
                t.insert("end", e["b"], base + (tb,))
            else:
                nm = "vs" if e["a"] == "TBD" and e["b"] == "TBD" else "name"  # undecided teams are muted
                t.insert("end", "\t" + e["a"], base + (nm,))
                t.insert("end", "\tvs\t", base + ("vs",))
                t.insert("end", e["b"], base + (nm,))
            t.insert("end", f"\tBo{e['bo']}", base + ("meta",))
            t.insert("end", ("   ● LIVE" if status == "live" else "   ● LIVE?" if live else "") + "\n",
                     base + ((("live",) if status == "live" else ("guess",)) if live else ("meta",)))
        if not rows:
            t.insert("end", "\nNothing to show yet.\n", "daystage")
        t.configure(state="disabled")
        if nxt_mark and view != "Results":
            total = max(1, int(t.index("end").split(".")[0]))
            t.yview_moveto(max(0, (float(t.index(nxt_mark).split(".")[0]) - 3) / total))
        else:
            t.yview_moveto(0)
        self.update_next()

    def update_next(self):
        now = time.time()
        c = self.card
        for e in self.events:
            dt = self.local(e["t"])
            status = match_status(e, now)
            if e.get("state") != "completed" and (dt.timestamp() > now or status):
                live = status is not None
                c.itemconfigure(self.next_tag, text=("● LIVE NOW" if status == "live" else "● LIVE NOW?") if live
                                else "NEXT MATCH", fill=(RED if status == "live" else "#B05050") if live else DIM)
                c.itemconfigure(self.next_match, text=f"{e['a']}   vs   {e['b']}")
                when = dt.strftime("%a %b ") + str(dt.day) + dt.strftime(", %I:%M %p").replace(" 0", " ")
                if not live:
                    mins = int((dt.timestamp() - now) // 60)
                    when += "   ·   in " + (f"{mins // 1440}d {mins % 1440 // 60}h" if mins >= 1440 else
                                                 f"{mins // 60}h {mins % 60}m" if mins >= 60 else f"{mins}m")
                c.itemconfigure(self.next_time, text=when)
                return
        c.itemconfigure(self.next_tag, text="NEXT MATCH", fill=DIM)
        c.itemconfigure(self.next_match, text="—")
        c.itemconfigure(self.next_time, text="No upcoming matches listed")

    def tick(self):  # keep countdown fresh and follow time-zone changes
        self.render() if int(time.time()) % 600 < 30 else self.update_next()
        if time.time() - getattr(self, "last_fetch", 0) > 600:  # new pairings/results while the app stays open
            self.last_fetch = time.time()
            threading.Thread(target=self.refresh_schedule, daemon=True).start()
        if time.time() - self.last_live > 60:
            threading.Thread(target=self.refresh_live, daemon=True).start()
        self.root.after(30000, self.tick)

    # ---- UI helpers ----
    def set_status(self, text, state=None):
        st = state or self.state
        self.root.after(0, self.set_state, st, text)

    def apply_top(self):
        self.root.attributes("-topmost", self.top.get())

    def channel_labels(self):
        return [AUTO_LABEL] + [("\u25CF " if login in self.live else "    ") + f"{label}  ({login})"
                              for label, login in all_channels()]

    def refresh_live(self, wait=False):
        got = live_channels([l for _, l in all_channels()])
        if got is not None:
            self.live = got
            self.last_live = time.time()
        if not wait:
            self.root.after(0, self.show_live_dot)

    def show_live_dot(self):
        """Glowing green circle beside STREAM when the chosen channel (or, for Auto, any official one) is live."""
        login = channel_login(self.channel.get())
        on = bool(self.live) if login == "auto" else login in self.live
        self.live_cv.itemconfigure(self.live_item, image=self.glow if on else "")

    def open_dropdown(self):
        self.refresh_live(wait=True)
        labels = self.channel_labels()
        self.combo.configure(values=labels)
        try:  # colour the live rows green in the popup list
            lb = str(self.combo.tk.call("ttk::combobox::PopdownWindow", self.combo)) + ".f.l"
            for i, text in enumerate(labels):
                self.combo.tk.call(lb, "itemconfigure", i, "-foreground", GREEN if text.startswith("\u25CF") else TEXT)
        except tk.TclError:
            pass
        self.show_live_dot()

    def test_stream(self):
        """Play whichever similar channel is live right now (same Twitch audio_only path)."""
        if self.want_play:
            self.stop()
        self.set_state("busy", "Looking for a live test stream...")

        def go():
            ch = find_live_channel()
            if not ch:
                self.set_status("No test channel is live right now", "wait")
                return
            self.root.after(0, lambda: (self.channel.set(ch), self.start()))
        threading.Thread(target=go, daemon=True).start()

    def toggle(self):
        self.stop() if self.want_play else self.start()

    # ---- playback ----
    def start(self):
        self.want_play = True
        self.gen += 1
        self.draw_btn()
        self.set_state("busy", "Connecting...")
        threading.Thread(target=self.worker, args=(self.gen,), daemon=True).start()

    def stop(self):
        self.want_play = False
        self.gen += 1
        if self.player:
            self.player.stop()
        self.draw_btn()
        self.set_state("stopped", "Stopped")

    def worker(self, gen):
        channel = channel_login(self.channel.get())
        ok = lambda: self.want_play and gen == self.gen
        while ok():
            self.set_status("Connecting...", "busy")
            try:
                if channel == "auto":
                    hit = first_live()
                    if not hit:
                        raise RuntimeError("offline")
                    channel_now, url = hit
                else:
                    channel_now, url = channel, stream_url(channel)
                if not self.player:
                    self.player = Player()
                self.player.set_volume(self.vol.get())
                self.player.play(url)
                self.set_status(f"Playing  ·  {channel_now}", "playing")
                time.sleep(5)
                while ok() and self.player.alive():
                    time.sleep(2)
                msg = "Stream interrupted"
            except Exception as ex:
                msg = "Offline" if "offline" in str(ex).lower() else "Error: " + str(ex)[:60]
            if not ok():
                return
            self.player and self.player.stop()
            for s in range(RETRY_SECS, 0, -1):
                if not ok():
                    return
                self.set_status(f"{msg} — retrying in {s}s", "wait")
                time.sleep(1)

    def on_vol(self, v=None):
        v = int(float(self.vol.get()))
        self.vol_lbl.config(text=str(v))
        if self.player:
            self.player.set_volume(v)

    def quit(self):
        try:
            with open(CONFIG, "w") as f:
                json.dump({"channel": self.channel.get(), "volume": int(float(self.vol.get())),
                           "ontop": self.top.get()}, f)
        except OSError:
            pass
        self.want_play = False
        if self.player:
            self.player.stop()
        self.root.destroy()


if __name__ == "__main__":
    r = tk.Tk()
    App(r)
    r.mainloop()
