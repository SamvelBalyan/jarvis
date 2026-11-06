"""
Jarvis PC agent - receives commands from your phone and runs them on this PC.
Uses only the Python standard library (free, nothing to install).

Run:   python pc_agent.py
First run creates config.json with a secret token. Put the same token in the phone app.
"""
import difflib
import hmac
import json
import os
import secrets
import socket
import subprocess
import sys
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
IS_WINDOWS = sys.platform.startswith("win")


def load_config():
    if not os.path.exists(CONFIG_PATH):
        cfg = {
            "port": 8765,
            "token": secrets.token_urlsafe(24),
            # name you say -> program path. Add your own!
            "apps": {
                "notepad": "notepad.exe",
                "calculator": "calc.exe",
                "explorer": "explorer.exe",
            },
        }
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)
        print(f"Created {CONFIG_PATH}")
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


CONFIG = load_config()


def run(cmd):
    subprocess.Popen(cmd, shell=False)


def need_windows():
    if not IS_WINDOWS:
        raise RuntimeError("This action only works on Windows")


def act_ping(arg):
    return "pong"


def act_open_url(arg):
    url = (arg or "").strip()
    if not urlparse(url).scheme:
        url = "https://" + url
    if urlparse(url).scheme not in ("http", "https"):
        raise ValueError("Only http/https links are allowed")
    webbrowser.open(url)
    return f"opened {url}"


def act_search(arg):
    from urllib.parse import quote_plus
    return act_open_url("https://www.google.com/search?q=" + quote_plus(arg or ""))


_NUMS = {"zero": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5",
         "six": "6", "seven": "7", "eight": "8", "nine": "9", "ten": "10"}


def _squeeze(s):
    words = [_NUMS.get(w, w) for w in s.lower().split()]
    return "".join(ch for ch in "".join(words) if ch.isalnum())


# ---- installed games: scanned from folders such as Desktop/Games ----
_SKIP = ("unins", "setup", "crash", "redist", "vcredist", "dxsetup", "launcher_helper", "installer", "updater", "helper")


def _game_dirs():
    home = os.path.expanduser("~")
    dirs = list(CONFIG.get("game_dirs", []))
    for base in (os.path.join(home, "Desktop"), os.path.join(home, "OneDrive", "Desktop"),
                 os.path.join(os.environ.get("PUBLIC", r"C:\Users\Public"), "Desktop")):
        dirs.append(os.path.join(base, "Games"))
    return [d for d in dirs if os.path.isdir(d)]


def _best_exe(folder, game_name):
    """Pick the program inside a game folder: a shortcut first, else the exe that looks most like the game."""
    shortcuts, exes = [], []
    for root, _dirs, files in os.walk(folder):
        if root[len(folder):].count(os.sep) > 2:
            continue
        for f in files:
            low = f.lower()
            if any(x in low for x in _SKIP):
                continue
            full = os.path.join(root, f)
            if low.endswith((".lnk", ".url")):
                shortcuts.append(full)
            elif low.endswith(".exe"):
                exes.append(full)
    if shortcuts:
        return shortcuts[0]
    if exes:
        target = _squeeze(game_name)
        exes.sort(key=lambda p: (-difflib.SequenceMatcher(None, target, _squeeze(os.path.splitext(os.path.basename(p))[0])).ratio(),
                                 -os.path.getsize(p)))
        return exes[0]
    return None


def scan_games():
    """name -> path of the thing to launch."""
    games = {}
    for d in _game_dirs():
        for entry in sorted(os.listdir(d)):
            full = os.path.join(d, entry)
            low = entry.lower()
            if os.path.isdir(full):
                target = _best_exe(full, entry)
                if target:
                    games[entry] = target
            elif low.endswith((".lnk", ".url", ".exe")) and not any(x in low for x in _SKIP):
                games[os.path.splitext(entry)[0]] = full
    return games


def _launch(path):
    if path.lower().endswith(".exe"):
        subprocess.Popen([path], cwd=os.path.dirname(path))
    else:
        os.startfile(path)  # noqa: shortcut from your own games folder


def _find_app(name, apps):
    """Exact match, then ignoring spaces/punctuation, then a close spelling
    (speech recognition often hears 'mortal combat x l' for 'mortal kombat xl')."""
    if name in apps:
        return name
    squeezed = {_squeeze(k): k for k in apps}
    if _squeeze(name) in squeezed:
        return squeezed[_squeeze(name)]
    close = difflib.get_close_matches(_squeeze(name), list(squeezed), n=1, cutoff=0.75)
    return squeezed[close[0]] if close else None


def act_open_app(arg):
    name = (arg or "").strip().lower()
    apps = {k.lower(): v for k, v in CONFIG.get("apps", {}).items()}
    key = _find_app(name, apps)
    need_windows()
    if key is not None:
        os.startfile(apps[key])  # noqa: only apps you listed can run
        return f"opened {key}"
    games = scan_games()
    gkey = _find_app(name, {k.lower(): v for k, v in games.items()})
    if gkey is not None:
        path = {k.lower(): v for k, v in games.items()}[gkey]
        _launch(path)
        return f"started game {gkey}"
    raise ValueError(f"Unknown app or game '{name}'. Put its shortcut in Desktop/Games, or add it to config.json under 'apps'.")


def act_list_games(arg):
    names = sorted(scan_games())
    return ", ".join(names) if names else "no games found (looked in Desktop/Games)"


def act_sleep(arg):
    need_windows()
    # 0,1,0 = sleep (not hibernate) if hibernate is off: run `powercfg -h off` once
    run(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
    return "sleeping"


def act_lock(arg):
    need_windows()
    run(["rundll32.exe", "user32.dll,LockWorkStation"])
    return "locked"


def act_restart(arg):
    need_windows()
    run(["shutdown", "/r", "/t", "5"])
    return "restarting"


def act_shutdown(arg):
    need_windows()
    run(["shutdown", "/s", "/t", "5"])
    return "shutting down"


ACTIONS = {
    "ping": act_ping,
    "open_url": act_open_url,
    "search": act_search,
    "open_app": act_open_app,
    "list_games": act_list_games,
    "sleep": act_sleep,
    "lock": act_lock,
    "restart": act_restart,
    "shutdown": act_shutdown,
}


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/command":
            return self._reply(404, {"ok": False, "error": "not found"})
        try:
            length = int(self.headers.get("Content-Length", 0))
            if length > 10_000:
                return self._reply(413, {"ok": False, "error": "too large"})
            data = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return self._reply(400, {"ok": False, "error": "bad json"})

        if not hmac.compare_digest(str(data.get("token", "")), CONFIG["token"]):
            return self._reply(401, {"ok": False, "error": "bad token"})

        action = ACTIONS.get(data.get("action"))
        if not action:
            return self._reply(400, {"ok": False, "error": "unknown action"})
        try:
            result = action(data.get("arg"))
            self._reply(200, {"ok": True, "result": result})
        except Exception as e:
            self._reply(200, {"ok": False, "error": str(e)})

    def log_message(self, fmt, *args):
        print("[agent]", fmt % args)


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    port = CONFIG["port"]
    print(f"Jarvis PC agent running on {local_ip()}:{port}")
    print(f"Token: {CONFIG['token']}")
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
