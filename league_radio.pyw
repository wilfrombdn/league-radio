"""League Radio - tiny audio-only player for Worlds (and other LoL Esports) broadcasts.

Gets Twitch's audio_only HLS rendition via streamlink (as a library) and plays it with
the Windows built-in media player - no mpv/VLC/ffmpeg needed, and no video is downloaded.
Includes a Worlds schedule shown in your local time zone.
"""
import asyncio
import json
import os
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


BG, PANEL, PANEL2 = "#000000", "#111111", "#262626"
GOLD, TEXT, DIM, TEAL, RED = "#FFFFFF", "#EDEDED", "#8C8C8C", "#FFFFFF", "#B3B3B3"
FONT = "Segoe UI"


class App:
    def __init__(self, root):
        self.root = root
        cfg = load_json(CONFIG, {})
        self.want_play = False
        self.gen = 0
        self.player = None
        self.events = []
        self.state = "stopped"  # stopped | busy | playing | wait

        root.title("League Radio")
        root.geometry("340x460")
        root.minsize(320, 380)
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", self.quit)
        self.style()

        nb = ttk.Notebook(root)
        nb.pack(fill="both", expand=True, padx=10, pady=(10, 10))
        radio = tk.Frame(nb, bg=BG)
        sched = tk.Frame(nb, bg=BG)
        nb.add(radio, text="  RADIO  ")
        nb.add(sched, text="  SCHEDULE  ")

        # ---- Radio tab ----
        tk.Label(radio, text="LEAGUE RADIO", bg=BG, fg=GOLD, font=(FONT, 17, "bold")).pack(pady=(16, 0))
        tk.Label(radio, text="Audio-only  •  low data", bg=BG, fg=DIM, font=(FONT, 9)).pack()

        self.cv = tk.Canvas(radio, width=104, height=104, bg=BG, highlightthickness=0, cursor="hand2")
        self.cv.pack(pady=(14, 6))
        self.cv.bind("<Button-1>", lambda e: self.toggle())
        self.cv.bind("<Enter>", lambda e: self.draw_btn(True))
        self.cv.bind("<Leave>", lambda e: self.draw_btn(False))

        srow = tk.Frame(radio, bg=BG)
        srow.pack()
        self.dot = tk.Canvas(srow, width=10, height=10, bg=BG, highlightthickness=0)
        self.dot.pack(side="left", padx=(0, 6))
        self.status = tk.StringVar(value="Stopped")
        tk.Label(srow, textvariable=self.status, bg=BG, fg=TEXT, font=(FONT, 9),
                 wraplength=250).pack(side="left")

        vrow = tk.Frame(radio, bg=BG)
        vrow.pack(fill="x", padx=34, pady=(10, 0))
        tk.Label(vrow, text="\U0001F508", bg=BG, fg=DIM, font=(FONT, 10)).pack(side="left")
        self.vol = tk.IntVar(value=cfg.get("volume", 60))
        tk.Scale(vrow, from_=0, to=100, orient="horizontal", variable=self.vol, command=self.on_vol,
                 showvalue=False, bg=GOLD, fg=GOLD, troughcolor=PANEL2, activebackground="#CFCFCF",
                 highlightthickness=0, bd=0, sliderrelief="flat", sliderlength=16, width=8,
                 ).pack(side="left", fill="x", expand=True, padx=8)
        tk.Label(vrow, text="\U0001F50A", bg=BG, fg=DIM, font=(FONT, 10)).pack(side="left")

        card = tk.Frame(radio, bg=PANEL, highlightbackground=PANEL2, highlightthickness=1)
        card.pack(fill="x", padx=18, pady=(16, 0))
        self.next_tag = tk.Label(card, text="NEXT MATCH", bg=PANEL, fg=GOLD, font=(FONT, 8, "bold"))
        self.next_tag.pack(pady=(10, 0))
        self.next_match = tk.Label(card, text="", bg=PANEL, fg=TEXT, font=(FONT, 15, "bold"))
        self.next_match.pack()
        self.next_time = tk.Label(card, text="", bg=PANEL, fg=DIM, font=(FONT, 9))
        self.next_time.pack(pady=(0, 10))

        foot = tk.Frame(radio, bg=BG)
        foot.pack(side="bottom", fill="x", padx=18, pady=(0, 12))
        tk.Label(foot, text="twitch.tv/", bg=BG, fg=DIM, font=(FONT, 9)).pack(side="left")
        self.channel = tk.StringVar(value=cfg.get("channel", "lolesports"))
        tk.Entry(foot, textvariable=self.channel, width=14, bg=PANEL, fg=TEXT, insertbackground=TEXT,
                 relief="flat", font=(FONT, 9), highlightthickness=1, highlightbackground=PANEL2,
                 highlightcolor=GOLD).pack(side="left", padx=4, ipady=3)
        tk.Button(foot, text="Test", command=self.test_stream, bg=PANEL2, fg=GOLD, relief="flat", bd=0,
                  activebackground=PANEL, activeforeground=GOLD, font=(FONT, 8, "bold"), padx=8,
                  cursor="hand2").pack(side="left", padx=4)
        self.top = tk.BooleanVar(value=cfg.get("ontop", True))
        tk.Checkbutton(foot, text="On top", variable=self.top, command=self.apply_top, bg=BG, fg=DIM,
                       selectcolor=PANEL, activebackground=BG, activeforeground=TEXT, bd=0,
                       font=(FONT, 9)).pack(side="right")

        # ---- Schedule tab ----
        head = tk.Frame(sched, bg=BG)
        head.pack(fill="x", padx=12, pady=(10, 4))
        self.tzlabel = tk.StringVar()
        tk.Label(head, textvariable=self.tzlabel, bg=BG, fg=DIM, font=(FONT, 8)).pack(side="left")
        self.view = tk.StringVar(value="Upcoming")
        self.year = tk.StringVar(value=str(datetime.now().year))
        self.year_cb = ttk.Combobox(head, textvariable=self.year, width=5, state="readonly", values=[self.year.get()])
        self.year_cb.pack(side="right", padx=(4, 0))
        ttk.Combobox(head, textvariable=self.view, width=9, state="readonly",
                     values=["Upcoming", "Results", "All"]).pack(side="right")
        for v in (self.year, self.view):
            v.trace_add("write", lambda *a: self.render())
        box = tk.Frame(sched, bg=BG)
        box.pack(fill="both", expand=True, padx=(12, 4), pady=(0, 10))
        self.txt = tk.Text(box, bg=BG, fg=TEXT, relief="flat", wrap="none", cursor="arrow", width=10,
                           height=10, font=(FONT, 9), highlightthickness=0, padx=2, state="disabled",
                           spacing1=2, spacing3=2)
        sb = ttk.Scrollbar(box, command=self.txt.yview, style="Dark.Vertical.TScrollbar")
        self.txt.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.txt.pack(fill="both", expand=True)
        self.txt.tag_configure("day", foreground=GOLD, font=(FONT, 10, "bold"), spacing1=10, spacing3=3)
        self.txt.tag_configure("row", lmargin1=6, lmargin2=6)
        self.txt.tag_configure("win", foreground="#FFFFFF", font=(FONT, 9, "bold"))
        self.txt.tag_configure("lose", foreground=DIM)
        self.txt.tag_configure("live", foreground=TEAL, font=(FONT, 9, "bold"))
        self.txt.tag_configure("next", background=PANEL2, foreground=TEXT, font=(FONT, 9, "bold"))
        self.txt.tag_configure("stage", foreground=DIM, font=(FONT, 8))

        self.apply_top()
        self.draw_btn()
        self.set_state("stopped", "Stopped")
        self.events = stored_matches()
        self.render()
        threading.Thread(target=self.refresh_schedule, daemon=True).start()
        self.tick()

    # ---- look ----
    def style(self):
        s = ttk.Style()
        s.theme_use("clam")
        s.configure("TNotebook", background=BG, borderwidth=0, tabmargins=0, bordercolor=BG,
                    lightcolor=BG, darkcolor=BG)
        s.configure("TNotebook.Tab", background=BG, foreground=DIM, borderwidth=0, padding=(10, 7),
                    font=(FONT, 9, "bold"), bordercolor=BG, lightcolor=BG, darkcolor=BG, focuscolor=BG)
        s.map("TNotebook.Tab", foreground=[("selected", GOLD)], background=[("selected", BG)],
              lightcolor=[("selected", BG)], bordercolor=[("selected", BG)])
        s.configure("TCombobox", fieldbackground=PANEL2, background=PANEL2, foreground=TEXT, arrowcolor=TEXT,
                    bordercolor=PANEL2, lightcolor=PANEL2, darkcolor=PANEL2, selectbackground=PANEL2,
                    selectforeground=TEXT)
        s.map("TCombobox", fieldbackground=[("readonly", PANEL2)], foreground=[("readonly", TEXT)])
        self.root.option_add("*TCombobox*Listbox.background", PANEL2)
        self.root.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.root.option_add("*TCombobox*Listbox.selectBackground", "#444444")
        s.configure("Gold.Horizontal.TScale", background=BG, troughcolor=PANEL2, bordercolor=BG,
                    lightcolor=GOLD, darkcolor=GOLD)
        s.map("Gold.Horizontal.TScale", background=[("active", GOLD)])
        s.configure("Dark.Vertical.TScrollbar", background=PANEL2, troughcolor=BG, bordercolor=BG,
                    arrowcolor=DIM, lightcolor=PANEL2, darkcolor=PANEL2)
        s.layout("Dark.Vertical.TScrollbar", [("Vertical.Scrollbar.trough", {"sticky": "ns", "children": [
            ("Vertical.Scrollbar.thumb", {"expand": "1", "sticky": "nswe"})]})])

    def draw_btn(self, hover=False):
        c = self.cv
        c.delete("all")
        col = "#CFCFCF" if hover else GOLD
        c.create_oval(4, 4, 100, 100, outline=col, width=3, fill=PANEL)
        c.create_oval(10, 10, 94, 94, outline=PANEL2, width=1)
        if self.want_play:
            c.create_rectangle(36, 36, 68, 68, fill=col, outline=col)
        else:
            c.create_polygon(42, 32, 42, 72, 74, 52, fill=col, outline=col)

    def set_state(self, state, text):
        self.state = state
        color = {"stopped": DIM, "busy": GOLD, "wait": RED, "playing": TEAL}[state]
        self.dot.delete("all")
        self.dot.create_oval(1, 1, 9, 9, fill=color, outline=color)
        self.status.set(text)

    # ---- schedule ----
    def refresh_schedule(self):
        ev, live = fetch_schedule()
        self.events = ev
        self.root.after(0, self.render)

    @staticmethod
    def local(t):
        return datetime.fromisoformat(t.replace("Z", "+00:00")).astimezone()  # system time zone

    def render(self):
        now = time.time()
        z = datetime.now().astimezone().strftime("%z")
        self.tzlabel.set(f"Local time (UTC{z[:3]}:{z[3:]})")
        years = sorted({e["t"][:4] for e in self.events} | {str(datetime.now().year)})
        self.year_cb.configure(values=years)
        year, view = self.year.get(), self.view.get()
        evs = [e for e in self.events if self.local(e["t"]).strftime("%Y") == year]
        done = [e for e in evs if e.get("state") == "completed"]
        t = self.txt
        t.configure(state="normal")
        t.delete("1.0", "end")
        t.insert("end", f"{len(done)} of {len(evs)} matches played\n", "stage")
        rows = [e for e in evs if view == "All" or (view == "Results") == (e.get("state") == "completed")]
        if view == "Results":
            rows.reverse()
        last_day, nxt_mark = None, None
        for e in rows:
            dt = self.local(e["t"])
            ts = dt.timestamp()
            finished = e.get("state") == "completed"
            live = not finished and ts <= now and ts > now - 3 * 3600
            day = dt.strftime("%A, %b ") + str(dt.day)
            if day != last_day:
                last_day = day
                t.insert("end", day + "\n", "day")
            is_next = nxt_mark is None and not finished and ts > now - 3 * 3600
            base = ("row", "next") if is_next else ("row",)
            if is_next:
                nxt_mark = t.index("end-1c")
            clock = dt.strftime("%I:%M %p").lstrip("0")
            t.insert("end", f"{clock:>8}   ", base + ("stage",))
            aw, bw = e.get("aw", -1), e.get("bw", -1)
            if finished and aw >= 0:
                ta, tb = ("win", "lose") if aw > bw else ("lose", "win")
                t.insert("end", e["a"], base + (ta,))
                t.insert("end", f"  {aw} - {bw}  ", base + ("win",))
                t.insert("end", e["b"], base + (tb,))
            else:
                t.insert("end", f"{e['a']} vs {e['b']}", base + (("live",) if live else ()))
            tail = f"   Bo{e['bo']} \u00b7 {e['stage']}" + ("   \u25cf LIVE" if live else "")
            t.insert("end", tail + "\n", base + (("live",) if live else ("stage",)))
        if not rows:
            t.insert("end", "\nNothing to show yet.\n", "stage")
        t.configure(state="disabled")
        if nxt_mark and view != "Results":
            t.yview_moveto(max(0, float(t.index(nxt_mark).split(".")[0]) - 3) / max(1, int(t.index("end").split(".")[0])))
        self.update_next()

    def update_next(self):
        now = time.time()
        for e in self.events:
            dt = self.local(e["t"])
            if e.get("state") != "completed" and dt.timestamp() > now - 3 * 3600:
                live = dt.timestamp() <= now
                self.next_tag.config(text="● LIVE NOW" if live else "NEXT MATCH", fg=TEAL if live else GOLD)
                self.next_match.config(text=f"{e['a']}  vs  {e['b']}")
                when = dt.strftime("%a %b ") + str(dt.day) + dt.strftime(", %I:%M %p").replace(" 0", " ")
                if not live:
                    mins = int((dt.timestamp() - now) // 60)
                    when += "  ·  in " + (f"{mins // 1440}d {mins % 1440 // 60}h" if mins >= 1440 else
                                               f"{mins // 60}h {mins % 60}m" if mins >= 60 else f"{mins}m")
                self.next_time.config(text=when)
                return
        self.next_tag.config(text="NEXT MATCH", fg=GOLD)
        self.next_match.config(text="—")
        self.next_time.config(text="No upcoming matches listed")

    def tick(self):  # keep countdown fresh and follow time-zone changes
        self.render() if int(time.time()) % 600 < 30 else self.update_next()
        self.root.after(30000, self.tick)

    # ---- UI helpers ----
    def set_status(self, text, state=None):
        st = state or self.state
        self.root.after(0, self.set_state, st, text)

    def apply_top(self):
        self.root.attributes("-topmost", self.top.get())

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
        self.draw_btn(True)
        threading.Thread(target=self.worker, args=(self.gen,), daemon=True).start()

    def stop(self):
        self.want_play = False
        self.gen += 1
        if self.player:
            self.player.stop()
        self.draw_btn(True)
        self.set_state("stopped", "Stopped")

    def worker(self, gen):
        channel = self.channel.get().strip().lower().replace("twitch.tv/", "").strip("/") or "lolesports"
        ok = lambda: self.want_play and gen == self.gen
        while ok():
            self.set_status("Connecting...", "busy")
            try:
                url = stream_url(channel)
                if not self.player:
                    self.player = Player()
                self.player.set_volume(self.vol.get())
                self.player.play(url)
                self.set_status(f"Playing  •  {channel}", "playing")
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

    def on_vol(self, _=None):
        if self.player:
            self.player.set_volume(int(float(self.vol.get())))

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
