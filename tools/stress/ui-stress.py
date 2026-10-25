#!/usr/bin/env python3
"""Aggressive, seeded UI stress for Sortie on a dual-screen (AYN Thor) or single-screen handheld.

Drives the same paths a player uses — Home key (switcher overlay), X screen swap, launching and moving apps between
displays, Recents, force-stops, sleep/wake, controller bursts — while continuously watching for crashes, ANRs, stuck
focus, leaked tasks/windows/activities, memory growth and network-callback leaks. Every anomaly is captured with both
screens, dumps and the preceding actions so it can be replayed from the seed.

User data guard: the card's Sortie JSON files are snapshotted first and compared (and restored) at the end. The harness
never presses A inside Sortie except in the switcher overlay, never launches games through Sortie (no play-log writes)
and never uninstalls anything.

Displays, the card's Sortie folder and a Game Boy ROM for the RetroArch scenario are discovered on the device.

Usage (adb inside distrobox `ubuntu` by default; set SORTIE_ADB_WRAPPER="" to call adb directly):
  tools/stress/ui-stress.py --serial SERIAL --minutes 180 [--seed N] [--out DIR]
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import queue
import random
import re
import shlex
import subprocess
import sys
import threading
import time

PKG = "io.github.esko997.sortie"
ASSISTANT = "com.odin.dualscreen.assistant"

GUARDED = ["user.json", "systems.json", "launchers.json", "folders.json", "device.json"]
CANDIDATE_APPS = [
    "com.android.deskclock", "com.android.settings", "com.peacocktv.peacockandroid", "com.amazon.avod.thirdpartyclient",
    "com.retroarch", "com.github.stenzek.duckstation", "com.sbro.emucorev", "com.droiddeck.launcher", "com.android.chrome",
    "com.google.android.youtube", "org.ppsspp.ppsspp", "me.magnum.melonds",
]
RA_GAME = (
    "com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture",
    {
        "LIBRETRO": "/data/user/0/com.retroarch/cores/gambatte_libretro_android.so",
        "CONFIGFILE": "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
        "IME": "", "DATADIR": "/data/user/0/com.retroarch", "SDCARD": "/storage/emulated/0",
        "EXTERNAL": "/storage/emulated/0/Android/data/com.retroarch/files",
    },
)
LOG_PATTERNS = [
    ("sortie-crash", re.compile(r"FATAL EXCEPTION|Fatal signal", re.I)),
    ("anr", re.compile(r"\bam_anr\b|ANR in ")),
    ("focus-gap", re.compile(r"does not have a focused window")),
    ("proc-died", re.compile(r"am_proc_died: \[\d+,\d+,io\.github\.esko997\.sortie")),
    ("win-death", re.compile(r"WIN DEATH: Window\{\S+ u0 io\.github\.esko997\.sortie")),
    ("too-many-requests", re.compile(r"TooManyRequestsException")),
    ("sortie-exception", re.compile(r"\b(E|W) (Switcher|ScreenMover|TaskSwitcher|SwitcherHost|SortieLaunch|Mecha\w*)\s*:")),
    ("activity-destroy-timeout", re.compile(r"Activity destroy timeout|Activity top resumed state loss timeout")),
]


def now() -> str:
    return dt.datetime.now().strftime("%H:%M:%S")


class Shell:
    """One persistent `adb shell` so actions cost a round trip, not a process spawn.

    Output is read by a thread into a queue so every command really times out: a blocking readline would wait forever if
    a command printed no final newline (the end marker then shares the last line, e.g. after `cat` of a JSON file).
    """

    def __init__(self, base: list[str]):
        self.base = base
        self.proc: subprocess.Popen | None = None
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.n = 0
        self.lock = threading.Lock()

    def _start(self):
        self.proc = subprocess.Popen(self.base + ["shell"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1, errors="replace")
        self.lines = queue.Queue()
        threading.Thread(target=self._pump, args=(self.proc, self.lines), daemon=True).start()

    @staticmethod
    def _pump(proc: subprocess.Popen, lines: "queue.Queue[str | None]"):
        for line in proc.stdout:
            lines.put(line)
        lines.put(None)  # the shell exited

    def run(self, cmd: str, timeout: float = 90) -> str:
        with self.lock:
            for attempt in range(3):
                if self.proc is None or self.proc.poll() is not None:
                    self._start()
                self.n += 1
                marker = f"__SORTIE_END_{self.n}__"
                try:
                    self.proc.stdin.write(f"{cmd}\necho {marker}\n")
                    self.proc.stdin.flush()
                    out, deadline = [], time.time() + timeout
                    while True:
                        line = self.lines.get(timeout=max(0.1, deadline - time.time()))
                        if line is None:
                            raise BrokenPipeError("adb shell exited")
                        text = line.rstrip("\r\n")
                        if text.endswith(marker):
                            out.append(text[: -len(marker)])
                            return "".join(out)
                        out.append(line)
                except (BrokenPipeError, OSError, queue.Empty):
                    self.kill()
                    time.sleep(2 + attempt * 3)
            raise RuntimeError(f"adb shell unavailable for: {cmd}")

    def kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
        self.proc = None


class Harness:
    def __init__(self, args):
        self.args = args
        self.profile: dict = {}  # displays, physical ids, card and ROM: filled by discover()
        wrapper = shlex.split(os.environ.get("SORTIE_ADB_WRAPPER", "distrobox enter ubuntu --"))
        self.adb = wrapper + ["adb", "-s", args.serial]
        self.serial = args.serial
        self.sh = Shell(self.adb)
        self.rng = random.Random(args.seed)
        self.out = pathlib.Path(args.out)
        self.out.mkdir(parents=True, exist_ok=True)
        self.events = open(self.out / "events.jsonl", "a", buffering=1)
        self.recent: list[str] = []
        self.counts: dict[str, int] = {}
        self.anomalies = 0
        self.stop = threading.Event()
        self.apps: list[str] = []
        self.focus_gap_since: float | None = None
        self.last_log_hits: list[tuple[str, str]] = []
        self.ui_unavailable = False  # `uiautomator dump` cannot work on this device: UI-located scenarios are skipped
        self.ui_failures = 0  # consecutive dumps that produced no hierarchy

    # ---------- plumbing ----------
    def log(self, kind: str, **data):
        rec = {"t": time.time(), "at": now(), "kind": kind, **data}
        self.events.write(json.dumps(rec) + "\n")
        if kind not in ("action",):
            print(f"[{rec['at']}] {kind} {json.dumps(data)[:300]}", flush=True)

    def host_adb(self, *args, timeout=120) -> subprocess.CompletedProcess:
        return subprocess.run(self.adb + list(args), capture_output=True, timeout=timeout)

    def ensure_connected(self):
        if ":" in self.serial:
            wrapper = self.adb[: self.adb.index("adb")]
            subprocess.run(wrapper + ["adb", "connect", self.serial], capture_output=True, timeout=30)

    def act(self, name: str, cmd: str, settle: float = 0.0):
        self.recent.append(f"{now()} {name}: {cmd}")
        self.recent = self.recent[-40:]
        self.counts[name] = self.counts.get(name, 0) + 1
        self.log("action", name=name, cmd=cmd)
        self.sh.run(cmd)
        if settle:
            time.sleep(settle)

    def key(self, *keys: str) -> str:
        return "input keyevent " + " ".join(keys)

    # ---------- logcat watcher ----------
    def watch_logcat(self):
        path = self.out / "logcat.txt"
        while not self.stop.is_set():
            self.ensure_connected()
            with open(path, "a", buffering=1, errors="replace") as fh:
                proc = subprocess.Popen(self.adb + ["logcat", "-T", "1", "-v", "threadtime", "-b", "main,system,crash,events"],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
                self.logcat_proc = proc
                for line in proc.stdout:
                    fh.write(line)
                    if self.stop.is_set():
                        break
                    for kind, pat in LOG_PATTERNS:
                        if pat.search(line) and ("sortie" in line or kind in ("too-many-requests", "sortie-crash", "anr")):
                            self.last_log_hits.append((kind, line.strip()[:400]))
                            self.log("logcat", hit=kind, line=line.strip()[:400])
                proc.kill()
            time.sleep(3)

    # ---------- state snapshot ----------
    SNAPSHOT = r"""
echo ==STACK; am stack list 2>/dev/null | grep -E 'RootTask id=|taskId=' ;
echo ==WM; dumpsys window displays 2>/dev/null | grep -E 'Display: mDisplayId=|mCurrentFocus=|mFocusedApp=' ;
echo ==INPUT; dumpsys input 2>/dev/null | grep -A8 -m1 'FocusedDisplayId' ;
echo ==OVERLAY; dumpsys window windows 2>/dev/null | grep -cE 'Window #[0-9]+ Window\{[0-9a-f]+ u0 Sortie switcher' ;
echo ==ANRWIN; dumpsys window windows 2>/dev/null | grep -cE 'Window #[0-9]+ Window\{[0-9a-f]+ u0 Application Not Responding' ;
echo ==PIDS; echo sortie=$(pidof io.github.esko997.sortie) assistant=$(pidof com.odin.dualscreen.assistant) ;
"""

    def snapshot(self, deep: bool = False) -> dict:
        cmd = self.SNAPSHOT
        if deep:
            cmd += (r"echo ==MEM; dumpsys meminfo io.github.esko997.sortie 2>/dev/null | grep -E 'TOTAL PSS|Activities:|ViewRootImpl:|AppContexts:|Views:|Java Heap:|Native Heap:|Graphics:' ;"
                    r"echo ==NET; dumpsys connectivity 2>/dev/null | grep -oE 'uid/pid:[0-9]+/[0-9]+' | sort | uniq -c ;"
                    r"echo ==GFX; dumpsys gfxinfo io.github.esko997.sortie 2>/dev/null | grep -m1 'Total frames rendered' ;")
        raw = self.sh.run(cmd, timeout=120)
        sec, cur = {}, None
        for line in raw.splitlines():
            if line.startswith("=="):
                cur = line[2:].strip()
                sec[cur] = []
            elif cur:
                sec[cur].append(line.rstrip("\r"))
        s: dict = {"raw": sec}
        pids = " ".join(sec.get("PIDS", []))
        m = re.search(r"sortie=(\S*)", pids)
        s["sortie_pid"] = m.group(1) if m and m.group(1) else None
        m = re.search(r"assistant=(\S*)", pids)
        s["assistant_pid"] = m.group(1) if m and m.group(1) else None
        s["overlays"] = int((sec.get("OVERLAY") or ["0"])[0] or 0)
        s["anr_windows"] = int((sec.get("ANRWIN") or ["0"])[0] or 0)
        # tasks
        tasks, disp = [], None
        for line in sec.get("STACK", []):
            m = re.search(r"RootTask id=(\d+).*displayId=(\d+)", line)
            if m:
                root, disp = int(m.group(1)), int(m.group(2))
                continue
            m = re.search(r"taskId=(\d+): (\S+) .*visible=(\w+)", line)
            if m:
                tasks.append({"root": root, "task": int(m.group(1)), "comp": m.group(2), "display": disp, "visible": m.group(3) == "true"})
        s["tasks"] = tasks
        # focus per display
        focus, disp = {}, None
        for line in sec.get("WM", []):
            m = re.search(r"mDisplayId=(\d+)", line)
            if m:
                disp = int(m.group(1))
                focus[disp] = {}
            elif disp is not None:
                m = re.search(r"(mCurrentFocus|mFocusedApp)=(.*)", line.strip())
                if m:
                    focus[disp][m.group(1)] = m.group(2)
        s["focus"] = focus
        inp = "\n".join(sec.get("INPUT", []))
        m = re.search(r"FocusedDisplayId: (\d+)", inp)
        s["input_display"] = int(m.group(1)) if m else None
        s["input_focus_windows"] = re.findall(r"displayId=(\d+), name='([^']*)'(?: result='(\w+)')?", inp.split("FocusedWindows:")[-1]) if "FocusedWindows:" in inp else []
        if deep:
            mem = "\n".join(sec.get("MEM", []))
            def num(label):
                mm = re.search(label + r":?\s+(\d+)", mem)
                return int(mm.group(1)) if mm else None
            s["pss_kb"] = num("TOTAL PSS")
            s["activities"] = num("Activities")
            s["viewroots"] = num("ViewRootImpl")
            s["appcontexts"] = num("AppContexts")
            s["views"] = num("Views")
            s["java_kb"] = num("Java Heap")
            s["native_kb"] = num("Native Heap")
            s["graphics_kb"] = num("Graphics")
            net = {}
            for line in sec.get("NET", []):
                mm = re.match(r"\s*(\d+) uid/pid:(\d+)/(\d+)", line)
                if mm:
                    net[(mm.group(2), mm.group(3))] = int(mm.group(1))
            s["net_assistant"] = sum(v for (u, p), v in net.items() if p == s.get("assistant_pid"))
            s["net_system_uid"] = sum(v for (u, p), v in net.items() if u == "1000")
            sortie_uid_pids = {s.get("sortie_pid")}
            s["net_sortie"] = sum(v for (u, p), v in net.items() if p in sortie_uid_pids)
            gfx = " ".join(sec.get("GFX", []))
            mm = re.search(r"Total frames rendered: (\d+)", gfx)
            s["frames"] = int(mm.group(1)) if mm else None
        return s

    # ---------- invariants ----------
    def check(self, s: dict, settled: bool) -> list[str]:
        problems = []
        if s["anr_windows"]:
            problems.append(f"ANR dialog visible ({s['anr_windows']})")
        if s["overlays"] > 2:
            problems.append(f"{s['overlays']} switcher overlay windows (leak?)")
        for d, f in s["focus"].items():
            cur = f.get("mCurrentFocus", "")
            if ASSISTANT in cur:
                # AYN's panel normally holds focus only while open; it is a zombie if it never drew
                problems.append(f"display {d} focus held by {ASSISTANT} window")
        # input focus gap on focused display while a Sortie activity is the focused app
        idisp = s.get("input_display")
        if idisp is not None:
            gap = [w for w in s["input_focus_windows"] if int(w[0]) == idisp and w[2] in ("NO_WINDOW", "NOT_VISIBLE")]
            focused_app = s["focus"].get(idisp, {}).get("mFocusedApp", "")
            if gap and PKG in focused_app:
                self.focus_gap_since = self.focus_gap_since or time.time()
                if time.time() - self.focus_gap_since > 8:
                    problems.append(f"display {idisp}: Sortie is focused app but has no focused window for "
                                    f"{time.time() - self.focus_gap_since:.0f}s")
            else:
                self.focus_gap_since = None
        sortie = [t for t in s["tasks"] if t["comp"].startswith(PKG + "/")]
        anchors = [t for t in sortie if t["comp"].endswith("AnchorActivity")]
        mains = [t for t in sortie if t["comp"].endswith(".MainActivity")]
        if settled:
            if len(mains) > 1:
                problems.append(f"{len(mains)} MainActivity tasks: {[ (t['task'], t['display']) for t in mains]}")
            if anchors:
                problems.append(f"lingering AnchorActivity tasks {[t['task'] for t in anchors]}")
            if s["sortie_pid"] is None:
                problems.append("Sortie process not running after settle")
            if len(self.profile["displays"]) > 1 and s["sortie_pid"]:
                shown = {t["display"] for t in sortie if t["visible"]}
                for d in self.profile["displays"]:
                    vis = [t for t in s["tasks"] if t["display"] == d and t["visible"]]
                    if not vis:
                        problems.append(f"display {d} shows no visible task")
                if not shown and not s["overlays"]:
                    problems.append("Home showed neither Sortie nor the switcher overlay")
        return problems

    def capture(self, reason: str, s: dict | None = None):
        self.anomalies += 1
        tag = f"{self.anomalies:03d}-{dt.datetime.now():%H%M%S}"
        d = self.out / "anomalies" / tag
        d.mkdir(parents=True, exist_ok=True)
        for disp, phys in self.profile["physical"].items():
            arg = f"-d {phys} " if phys else ""
            self.sh.run(f"screencap {arg}-p /data/local/tmp/stress-{disp}.png")
            self.host_adb("pull", f"/data/local/tmp/stress-{disp}.png", str(d / f"display{disp}.png"))
        dumps = self.sh.run("am stack list; echo; dumpsys window displays | grep -E 'mDisplayId|mCurrentFocus|mFocusedApp'; "
                            "echo; dumpsys input | grep -A12 -m1 FocusedDisplayId", timeout=120)
        (d / "state.txt").write_text(dumps)
        (d / "actions.txt").write_text("\n".join(self.recent))
        (d / "logcat-hits.txt").write_text("\n".join(f"{k}: {l}" for k, l in self.last_log_hits[-60:]))
        if s:
            (d / "snapshot.json").write_text(json.dumps({k: v for k, v in s.items() if k != "raw"}, indent=1, default=str))
        self.log("anomaly", id=tag, reason=reason)

    def recover(self, s: dict):
        """Bring the device back to a usable state so the run can continue; every step is recorded."""
        if any(ASSISTANT in f.get("mCurrentFocus", "") for f in s["focus"].values()):
            if not self.args.no_assistant_restart:
                self.act("recover-assistant", f"am crash {ASSISTANT}", 3)
        if s["anr_windows"]:
            self.act("recover-anr", f"am force-stop {PKG}", 1)
        self.act("recover-home", self.key("KEYCODE_WAKEUP") + "; " + self.home_cmd(), 3)

    def home_cmd(self) -> str:
        return "am start --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1"

    # ---------- scenarios ----------
    def other_tasks(self, s: dict) -> list[dict]:
        skip = (PKG, "com.android.launcher3", "com.android.systemui")
        return [t for t in s["tasks"] if t["comp"] != "unknown" and not t["comp"].startswith(skip) and t["display"] is not None]

    def sc_home_overlay(self, s):
        self.act("home", self.key("KEYCODE_HOME"), self.rng.uniform(0.3, 1.5))
        keys = [self.rng.choice(["KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN",
                                 "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT", "KEYCODE_BUTTON_A", "KEYCODE_BUTTON_B",
                                 "KEYCODE_BUTTON_Y", "KEYCODE_BUTTON_Y", "KEYCODE_BUTTON_SELECT"])
                for _ in range(self.rng.randint(1, 6))]
        # A (to front), Y (other screen) and Select (close app) only act while the switcher overlay is up: they move or
        # close running apps, never Sortie data
        guarded = ("KEYCODE_BUTTON_A", "KEYCODE_BUTTON_Y", "KEYCODE_BUTTON_SELECT")
        script = "; ".join(
            (f"if dumpsys window windows | grep -q 'u0 Sortie switcher'; then input keyevent {k}; fi" if k in guarded
             else f"input keyevent {k}") + f"; sleep {self.rng.choice(['0.05', '0.15', '0.4'])}" for k in keys)
        self.act("overlay-nav", script, self.rng.uniform(0.2, 1.0))

    def sc_rapid_home(self, s):
        n = self.rng.randint(3, 8)
        gap = self.rng.choice(["0.05", "0.12", "0.3"])
        self.act("rapid-home", "; ".join([f"input keyevent KEYCODE_HOME; sleep {gap}"] * n), 1.0)

    def sc_swap(self, s):
        # land on Sortie navigation first (Home then B dismisses the overlay), then X
        n = self.rng.choice([1, 1, 1, 2, 3])
        gap = self.rng.choice(["0.1", "0.4", "1.5"])
        self.act("swap", f"input keyevent KEYCODE_HOME; sleep 0.8; input keyevent KEYCODE_BUTTON_B; sleep 0.5; "
                 + "; ".join([f"input keyevent KEYCODE_BUTTON_X; sleep {gap}"] * n), 2.0)

    def sc_launch_app(self, s):
        if not self.apps:
            return
        comp = self.rng.choice(self.apps)
        d = self.rng.choice(self.profile["displays"])
        self.act("launch", f"am start --display {d} -n {shlex.quote(comp)} >/dev/null 2>&1", self.rng.uniform(1.0, 3.0))

    def sc_game(self, s):
        rom = self.profile.get("rom")
        if not rom or not any(a.startswith("com.retroarch/") for a in self.apps):
            return self.sc_launch_app(s)
        comp, extras = RA_GAME
        d = self.rng.choice(self.profile["displays"])
        ex = " ".join(f"--es {k} {shlex.quote(v) if v else chr(39) * 2}" for k, v in {"ROM": rom, **extras}.items())
        # NEW_TASK|CLEAR_TOP: Sortie's default RetroArch flags (CLEAR_TASK can hang RetroActivityFuture teardown)
        self.act("game", f"am start --display {d} -f 0x14000000 -n {comp} {ex} >/dev/null 2>&1", self.rng.uniform(3, 8))

    def sc_move_task(self, s):
        cands = self.other_tasks(s)
        if not cands or len(self.profile["displays"]) < 2:
            return self.sc_launch_app(s)
        t = self.rng.choice(cands)
        target = [d for d in self.profile["displays"] if d != t["display"]][0]
        self.act("move-task", f"cmd activity display move-stack {t['root']} {target}", self.rng.uniform(0.5, 2.0))

    def sc_recents(self, s):
        self.act("recents", f"input keyevent KEYCODE_APP_SWITCH; sleep {self.rng.uniform(0.5, 2):.1f}; input keyevent "
                 + self.rng.choice(["KEYCODE_BACK", "KEYCODE_HOME", "KEYCODE_HOME"]), 1.5)

    def sc_back_spam(self, s):
        n = self.rng.randint(2, 6)
        self.act("back-spam", "; ".join(["input keyevent KEYCODE_BACK; sleep 0.15"] * n), 1.0)

    def sc_sortie_keys(self, s):
        keys = [self.rng.choice(["KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT",
                                 "KEYCODE_BUTTON_L1", "KEYCODE_BUTTON_R1", "KEYCODE_BUTTON_B", "KEYCODE_BUTTON_Y"])
                for _ in range(self.rng.randint(3, 12))]
        self.act("sortie-keys", "input keyevent KEYCODE_HOME; sleep 0.8; input keyevent KEYCODE_BUTTON_B; sleep 0.4; "
                 + "; ".join(f"input keyevent {k}; sleep 0.08" for k in keys) + "; input keyevent KEYCODE_BUTTON_B", 0.8)

    def sc_force_stop_sortie(self, s):
        self.act("force-stop-sortie", f"am force-stop {PKG}; sleep {self.rng.choice(['0', '0.3', '1'])}; "
                 + self.key("KEYCODE_HOME"), 3.0)

    def sc_clear_all(self, s):
        pkgs = sorted({a.split("/")[0] for a in self.apps})
        self.act("clear-all", "; ".join(f"am force-stop {p}" for p in pkgs) + f"; am force-stop {PKG}; " + self.key("KEYCODE_HOME"), 3.0)

    def sc_sleep_wake(self, s):
        self.act("sleep-wake", f"input keyevent KEYCODE_SLEEP; sleep {self.rng.uniform(1, 4):.1f}; input keyevent KEYCODE_WAKEUP; "
                 "sleep 0.5; wm dismiss-keyguard", 2.0)

    def sc_launch_during_transition(self, s):
        # start an app, immediately press Home and swap: the racy sequence the switcher must survive
        if not self.apps:
            return
        comp = self.rng.choice(self.apps)
        d = self.rng.choice(self.profile["displays"])
        self.act("launch-race", f"am start --display {d} -n {shlex.quote(comp)} >/dev/null 2>&1; sleep {self.rng.choice(['0', '0.1', '0.3'])}; "
                 "input keyevent KEYCODE_HOME; sleep 0.2; input keyevent KEYCODE_BUTTON_X", 2.0)

    def sc_trim_memory(self, s):
        level = self.rng.choice(["RUNNING_LOW", "RUNNING_CRITICAL", "MODERATE", "COMPLETE"])
        self.act("trim-memory", f"am send-trim-memory {PKG} {level} 2>/dev/null", 0.5)

    # ---- Sortie surfaces, found with `uiautomator dump` ----
    # uiautomator dumps the *active* accessibility window, which on two screens can be the companion's carousel below even
    # while the top screen has focus. sortie_home_on_top() taps the top-left corner of display 0 on Home (the title, never
    # a control) so the top window is active; later taps on display 0 keep it so. Never tap blind elsewhere: outside a
    # dialog it would close it.
    # On some devices it never works: the Retroid Pocket Nova's bound "Game Assistant" accessibility service makes every
    # dump fail with "null root node returned by UiTestAutomationBridge". That (or three empty dumps in a row) marks
    # uiautomator unavailable for the run: one `skip` event says so and the UI-located scenarios stop trying.
    def ui_texts(self) -> list[tuple[str, int, int]] | None:
        """Text nodes of the active window with their centres; None when the dump failed."""
        if self.ui_unavailable:
            return None
        dump = "/data/local/tmp/stress-ui.xml"
        raw = self.sh.run(f"rm -f {dump}; uiautomator dump {dump} 2>&1; echo __UI_XML__; cat {dump} 2>/dev/null", timeout=60)
        said, _, xml = raw.partition("__UI_XML__")
        if "<hierarchy" not in xml:
            self.ui_failures += 1
            if "null root node" in said or self.ui_failures >= 3:
                self.ui_unavailable = True
                self.log("skip", scenario="uiautomator", reason="uiautomator unavailable", said=said.strip()[-200:])
            return None
        self.ui_failures = 0
        return [(t, (int(a) + int(c)) // 2, (int(b) + int(d)) // 2)
                for t, a, b, c, d in re.findall(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)]

    def tap_text(self, pattern: str, name: str, settle: float = 1.5) -> bool:
        texts = self.ui_texts()
        if texts is None:
            if not self.ui_unavailable:  # a one-off failure; a device where it never works was reported once already
                self.log("skip", scenario=name, reason="uiautomator dump failed")
            return False
        for text, x, y in texts:
            if re.fullmatch(pattern, text):
                self.act(name, f"input -d 0 tap {x} {y}", settle)
                return True
        self.log("skip", scenario=name, reason=f"no '{pattern}' on the top display", seen=[t for t, _, _ in texts if t][:12])
        return False

    def sortie_home_on_top(self) -> bool:
        """Sortie navigation on the Home route, on display 0 (X swaps it up when it is below). False if it is not there."""
        # Home summons Sortie (or its overlay, which B closes); B then walks back to the Home route, where B does nothing.
        # Presses arriving while the overlay window is still closing are lost, so this one is paced like a person.
        self.act("to-sortie-home", "input keyevent KEYCODE_HOME; sleep 1.2; input keyevent KEYCODE_BUTTON_B; sleep 1.2; "
                 + "; ".join(["input keyevent KEYCODE_BUTTON_B; sleep 0.7"] * 4), 0.5)
        for attempt in range(2):
            focus = self.snapshot()["focus"].get(0, {}).get("mCurrentFocus", "")
            if f"{PKG}/{PKG}.MainActivity" in focus:
                self.act("activate-top", "input -d 0 tap 2 2", 0.4)
                return True
            if attempt or len(self.profile["displays"]) < 2:
                break
            self.act("swap-navigation-up", self.key("KEYCODE_BUTTON_X"), 2.5)
        self.log("skip", scenario="sortie-home", reason="Sortie navigation not on the top display")
        return False

    def showing(self, pattern: str) -> bool:
        return any(re.fullmatch(pattern, t) for t, _, _ in self.ui_texts() or [])

    def sc_reader(self, s):
        """Shelf → latest issue → an article, then read, swap screens and turn articles mid-read; never edits clippings."""
        # the shelf's hint bar says "Select · scrapbook" at every width (the wide shelf has no scrapbook header)
        if self.ui_unavailable or not self.sortie_home_on_top() or not self.tap_text("Magazines", "tap-magazines", 2.0) or not self.showing("scrapbook"):
            return
        self.act("open-issue", self.key("KEYCODE_BUTTON_A"), 2.0)  # the shelf restores its last focused issue
        # contents restore their scroll position, so their header may be off screen; only contents say "B · shelf"
        if not self.showing("shelf"):
            return self.act("reader-exit", "; ".join(["input keyevent KEYCODE_BUTTON_B; sleep 0.3"] * 3), 1.0)
        self.act("open-article", self.key("KEYCODE_BUTTON_A"), 2.0)
        steps = [self.rng.choice(["KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_UP",
                                  "KEYCODE_BUTTON_R1", "KEYCODE_BUTTON_L1", "KEYCODE_BUTTON_X", "KEYCODE_HOME_THEN_B"])
                 for _ in range(self.rng.randint(4, 12))]
        script = "; ".join(("input keyevent KEYCODE_HOME; sleep 1; input keyevent KEYCODE_BUTTON_B" if k == "KEYCODE_HOME_THEN_B"
                            else f"input keyevent {k}") + f"; sleep {self.rng.choice(['0.1', '0.4', '1.2'])}" for k in steps)
        self.act("read", script, 1.0)
        # Select opens the reader menu; B leaves it untouched
        self.act("reader-menu", "input keyevent KEYCODE_BUTTON_SELECT; sleep 0.8; input keyevent KEYCODE_BUTTON_B", 0.8)
        self.act("reader-exit", "; ".join(["input keyevent KEYCODE_BUTTON_B; sleep 0.4"] * 3), 1.0)

    def sc_hangar(self, s):
        """Open the Hangar, flip to Gallery and back, close: no care, rename, salvage or equip."""
        if self.ui_unavailable or not self.sortie_home_on_top() or not self.tap_text("Hangar", "tap-hangar", 1.5) or not self.showing("Workshop"):
            return
        if self.rng.random() < 0.6:
            self.tap_text("Gallery", "tap-gallery", 1.0)
            self.act("gallery-keys", "; ".join(f"input keyevent {self.rng.choice(['KEYCODE_DPAD_LEFT', 'KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_DOWN'])}; sleep 0.15"
                                               for _ in range(self.rng.randint(2, 6))), 0.5)
        if self.rng.random() < 0.3:
            self.act("hangar-swap", self.key("KEYCODE_BUTTON_X"), 2.0)  # X while the Hangar dialog is up
        self.act("hangar-close", "; ".join(["input keyevent KEYCODE_BUTTON_B; sleep 0.4"] * 2), 1.0)

    SCENARIOS = [
        ("sc_home_overlay", 30), ("sc_rapid_home", 6), ("sc_swap", 10), ("sc_launch_app", 16), ("sc_game", 5),
        ("sc_move_task", 9), ("sc_recents", 4), ("sc_back_spam", 5), ("sc_sortie_keys", 8), ("sc_force_stop_sortie", 2),
        ("sc_clear_all", 1), ("sc_sleep_wake", 2), ("sc_launch_during_transition", 6), ("sc_trim_memory", 2),
        ("sc_reader", 7), ("sc_hangar", 5),
    ]

    # ---------- run ----------
    def discover(self):
        out = self.sh.run("dumpsys display | grep -oE 'mDisplayId=[0-9]+|uniqueId \"local:[0-9]+\"'")
        physical, current = {}, None
        for token in out.splitlines():
            m = re.match(r"mDisplayId=(\d+)", token.strip())
            if m:
                current = int(m.group(1))
                continue
            m = re.search(r"local:(\d+)", token)
            if m and current is not None:
                physical.setdefault(current, m.group(1))
        displays = sorted(physical) or [0]
        card = self.sh.run("for d in /storage/*/sortie /storage/emulated/0/sortie; do [ -f $d/device.json ] && echo $d && break; done").strip()
        if not card:
            raise SystemExit("no Sortie card folder (sortie/device.json) found on the device")
        root = json.loads(self.sh.run(f"cat {card}/device.json")).get("root", "")
        rom = self.sh.run(f"ls {shlex.quote(root)}/roms/gb/*.gb 2>/dev/null | head -1").strip() if root else ""
        # one display: plain `screencap` (some single-screen devices reject -d)
        self.profile = {"displays": displays, "physical": physical if len(displays) > 1 else {0: None}, "card": card, "rom": rom or None}
        self.log("discover", **self.profile)

    def setup(self):
        self.ensure_connected()
        self.discover()
        orig = self.sh.run("cmd media_session volume --stream 3 --get 2>&1 | tail -1; settings get global stay_on_while_plugged_in")
        self.log("setup", original=orig.strip())
        (self.out / "restore.txt").write_text(orig)
        self.sh.run("cmd media_session volume --stream 3 --set 0 >/dev/null 2>&1; svc power stayon true; input keyevent KEYCODE_WAKEUP")
        for p in CANDIDATE_APPS:
            r = self.sh.run(f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {p} | tail -1").strip()
            if "/" in r and "No activity" not in r:
                self.apps.append(r)
        self.log("apps", apps=self.apps)
        guard = self.out / "guard"
        guard.mkdir(exist_ok=True)
        for f in GUARDED:
            self.host_adb("pull", f"{self.profile['card']}/{f}", str(guard / f))
        self.log("guard", files=[f for f in GUARDED if (guard / f).exists()])

    def teardown(self):
        """Restores volume, screen-on and the guarded card files. If the device is gone (a system crash can drop wireless
        debugging), says so and leaves the copies in guard/ for `--restore-only` later."""
        orig = (self.out / "restore.txt").read_text().split("\n")
        m = re.search(r"volume is (\d+)", orig[0] if orig else "")
        stay = orig[1].strip() if len(orig) > 1 else "0"
        try:
            self.sh.run(f"cmd media_session volume --stream 3 --set {m.group(1) if m else 1} >/dev/null 2>&1; "
                        f"settings put global stay_on_while_plugged_in {stay or 0}", timeout=30)
        except RuntimeError:
            self.log("teardown", device="unreachable", restore=f"{sys.argv[0]} --serial {self.serial} --restore-only --out {self.out}")
            return
        changed = []
        guard = self.out / "guard"
        after = self.out / "guard-after"
        after.mkdir(exist_ok=True)
        for f in GUARDED:
            if not (guard / f).exists():
                continue
            pulled = self.host_adb("pull", f"{self.profile['card']}/{f}", str(after / f))
            if pulled.returncode != 0 or (after / f).read_bytes() != (guard / f).read_bytes():
                changed.append(f)
        if changed:
            self.sh.run(f"am force-stop {PKG}")
            for f in changed:
                self.host_adb("push", str(guard / f), f"{self.profile['card']}/{f}")
            self.sh.run(self.home_cmd())
        self.log("teardown", restored=changed, counts=self.counts, anomalies=self.anomalies)

    def run(self):
        self.setup()
        watcher = threading.Thread(target=self.watch_logcat, daemon=True)
        watcher.start()
        killers = {"sc_force_stop_sortie", "sc_clear_all"} if self.args.no_kill else set()
        names, weights = zip(*[(n, w) for n, w in self.SCENARIOS if n not in killers])
        end = time.time() + self.args.minutes * 60
        last_deep = last_settle = 0.0
        baseline = None
        n = 0
        try:
            while time.time() < end:
                n += 1
                name = self.rng.choices(names, weights)[0]
                try:
                    s = self.snapshot() if name in ("sc_move_task",) else {"tasks": []}
                    getattr(self, name)(s)
                    s = self.snapshot()
                    problems = self.check(s, settled=False)
                    dismiss = False
                    if time.time() - last_settle > self.args.settle_every:
                        last_settle = time.time()
                        self.act("settle", self.key("KEYCODE_HOME"), 3.0)
                        s = self.snapshot()
                        problems += self.check(s, settled=True)
                        # Home either shows the overlay or Sortie itself; B (after any capture) returns to what was there
                        dismiss = True
                    if time.time() - last_deep > self.args.deep_every:
                        last_deep = time.time()
                        deep = self.snapshot(deep=True)
                        metrics = {k: deep.get(k) for k in ("pss_kb", "java_kb", "native_kb", "graphics_kb", "activities", "viewroots", "appcontexts", "views",
                                                             "net_assistant", "net_system_uid", "net_sortie", "frames", "sortie_pid", "assistant_pid")}
                        self.log("metrics", **metrics)
                        baseline = baseline or metrics
                        # heap counts include not-yet-collected instances; only a sustained rise is a leak
                        high = getattr(self, "high_activity_samples", 0)
                        self.high_activity_samples = high + 1 if (deep.get("activities") or 0) > 12 else 0
                        if self.high_activity_samples >= 3:
                            problems.append(f"{deep['activities']} live Sortie activities for 3 samples (leak?)")
                        if deep.get("net_system_uid") and deep["net_system_uid"] > 200:
                            problems.append(f"system uid network requests {deep['net_system_uid']} (cap 250)")
                    hits = [k for k, _ in self.last_log_hits]
                    self.last_log_hits_seen = getattr(self, "last_log_hits_seen", 0)
                    new_hits = hits[self.last_log_hits_seen:]
                    self.last_log_hits_seen = len(hits)
                    for h in sorted(set(new_hits) & {"sortie-crash", "anr", "too-many-requests", "activity-destroy-timeout"}):
                        problems.append(f"logcat: {h}")
                    if problems:
                        self.capture("; ".join(problems), s)
                        self.recover(s)
                    if dismiss:
                        self.act("settle-dismiss", self.key("KEYCODE_BUTTON_B"), 1.0)
                except Exception as e:  # keep the overnight run alive; record and reconnect
                    self.log("harness-error", scenario=name, error=repr(e)[:300])
                    self.sh.kill()
                    self.ensure_connected()
                    time.sleep(5)
                if n % 50 == 0:
                    self.log("progress", iterations=n, anomalies=self.anomalies, remaining_min=round((end - time.time()) / 60))
        finally:
            self.stop.set()
            self.teardown()
            self.sh.kill()
            if getattr(self, "logcat_proc", None) and self.logcat_proc.poll() is None:
                self.logcat_proc.kill()  # the watcher thread is blocked reading it
            # Killing the distrobox wrapper leaves the containerized adb client running (host pid namespace): stop the
            # streams this run started by their command line.
            for pattern in (f"adb -s {self.serial} logcat -T 1", f"adb -s {self.serial} shell$"):
                subprocess.run(["pkill", "-f", pattern], capture_output=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--minutes", type=float, default=60)
    ap.add_argument("--seed", type=int, default=int(time.time()))
    ap.add_argument("--settle-every", type=float, default=90, help="seconds between Home-settle invariant checks")
    ap.add_argument("--deep-every", type=float, default=120, help="seconds between memory/network metric samples")
    ap.add_argument("--no-assistant-restart", action="store_true", help="do not `am crash` a wedged AYN panel during recovery")
    ap.add_argument("--no-kill", action="store_true", help="skip scenarios that force-stop Sortie (long single-process runs for leak checks)")
    ap.add_argument("--restore-only", action="store_true", help="only restore an earlier run's settings and card files (--out of that run)")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    if args.restore_only:
        if not args.out:
            ap.error("--restore-only needs --out of the run to restore")
        h = Harness(args)
        h.ensure_connected()
        h.discover()
        h.teardown()
        h.sh.kill()
        return
    args.out = args.out or str(pathlib.Path.home() / ".cache/sortie-hardening" / f"{args.serial.replace(':', '_')}-{dt.datetime.now():%Y%m%d-%H%M%S}-s{args.seed}")
    print(f"seed={args.seed} out={args.out}", flush=True)
    Harness(args).run()


if __name__ == "__main__":
    sys.exit(main())
