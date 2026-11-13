"""
Сервер «Пульт для ПК» (Windows).
Принимает команды с телефона по домашней сети и управляет мышью, клавиатурой, медиа,
показывает экран, отправляет ссылки и буфер обмена. Живёт в трее (значок возле часов).
Настройки лежат рядом с программой: settings.json
"""
import asyncio
import base64
import collections
import ctypes
import hmac
import io
import ipaddress
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pystray
from PIL import Image, ImageDraw, ImageGrab
from pynput.keyboard import Controller as Keyboard, Key
from pynput.mouse import Button, Controller as Mouse

try:
    import mss          # быстрый захват экрана; без него работает запасной (медленный) вариант
except Exception:
    mss = None

try:
    import dxcam        # захват через DXGI: видит полноэкранные игры и быстрее; без него берётся mss
except Exception:
    dxcam = None

# Чтобы координаты мыши и снимок экрана совпадали на мониторах с масштабом 125–150%
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception:
    pass

VERSION = 2
APP_NAME = "PultDlyaPK"
NO_WINDOW = 0x08000000
BASE = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
SETTINGS_FILE = BASE / "settings.json"
LOG_FILE = BASE / "server.log"

keyboard = Keyboard()
mouse = Mouse()


# ---------- настройки ----------

def new_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


DEFAULT_BUTTONS = [
    {"name": "Блокнот", "run": "notepad"},
    {"name": "Калькулятор", "run": "calc"},
    {"name": "Проводник", "run": "explorer"},
    {"name": "Сайт", "open": "https://www.google.com"},
]


def load_settings(prev=None) -> dict:
    s = {}
    broken = False
    if SETTINGS_FILE.exists():
        try:
            s = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
        except Exception:
            broken = True      # файл сломан (например, правишь его в блокноте): ничего не затираем
            if prev is not None:
                return prev
    changed = False
    if "port" not in s:
        s["port"] = 8765
        changed = True
    if "code" not in s:
        s["code"] = new_code()
        changed = True
    if "buttons" not in s:
        s["buttons"] = DEFAULT_BUTTONS
        changed = True
    if changed and not broken:
        SETTINGS_FILE.write_text(json.dumps(s, indent=2, ensure_ascii=False), encoding="utf-8")
    return s


settings = load_settings()


def save_settings() -> None:
    SETTINGS_FILE.write_text(json.dumps(settings, indent=2, ensure_ascii=False), encoding="utf-8")


def reload_settings() -> None:
    """Подхватывает правки settings.json (кроме порта) без перезапуска."""
    fresh = load_settings(prev=dict(settings))
    port = settings.get("port", 8765)
    settings.clear()
    settings.update(fresh)
    settings["port"] = port


def log(text: str) -> None:
    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")
    except OSError:
        pass


# ---------- разрешённые команды (только из этого списка) ----------

NAMED = {
    "right": Key.right, "left": Key.left, "up": Key.up, "down": Key.down,
    "enter": Key.enter, "esc": Key.esc, "tab": Key.tab, "space": Key.space,
    "backspace": Key.backspace, "delete": Key.delete,
    "home": Key.home, "end": Key.end, "pgup": Key.page_up, "pgdn": Key.page_down,
}
for _i in range(1, 13):
    NAMED[f"f{_i}"] = getattr(Key, f"f{_i}")

MODS = {"ctrl": Key.ctrl, "shift": Key.shift, "alt": Key.alt, "win": Key.cmd}

MEDIA = {
    "playpause": Key.media_play_pause, "next": Key.media_next, "prev": Key.media_previous,
    "volup": Key.media_volume_up, "voldown": Key.media_volume_down, "mute": Key.media_volume_mute,
}


def _popen(args):
    subprocess.Popen(args, creationflags=NO_WINDOW)


SYSTEM = {
    "lock": lambda: ctypes.windll.user32.LockWorkStation(),
    "sleep": lambda: _popen("rundll32.exe powrprof.dll,SetSuspendState 0,1,0"),
    "shutdown": lambda: _popen(["shutdown", "/s", "/t", "5"]),
    "restart": lambda: _popen(["shutdown", "/r", "/t", "5"]),
    "cancel": lambda: _popen(["shutdown", "/a"]),
}

URL_RE = re.compile(r"https?://\S+")

# Tailscale: адреса 100.64.0.0/10 (и fd7a:115c:a1e0::/48) Python не считает частными, разрешаем их отдельно
TAILSCALE_NETS = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))


def is_tailscale(ip) -> bool:
    return any(ip.version == n.version and ip in n for n in TAILSCALE_NETS)


def peer_allowed(ip) -> bool:
    """Свои сети: домашняя, локальная петля, Tailscale. Всё остальное отклоняется."""
    return ip.is_private or ip.is_loopback or ip.is_link_local or is_tailscale(ip)


# ---------- буфер обмена (напрямую через Windows, без сторонних библиотек) ----------

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002


def _clip_open() -> None:
    u = ctypes.windll.user32
    u.OpenClipboard.argtypes = [ctypes.c_void_p]
    u.OpenClipboard.restype = ctypes.c_int
    for _ in range(20):          # буфер может быть занят другой программой
        if u.OpenClipboard(None):
            return
        time.sleep(0.05)
    raise OSError("clipboard busy")


def clip_copy(text: str) -> None:
    u, k = ctypes.windll.user32, ctypes.windll.kernel32
    k.GlobalAlloc.argtypes = [ctypes.c_uint, ctypes.c_size_t]
    k.GlobalAlloc.restype = ctypes.c_void_p
    k.GlobalLock.argtypes = [ctypes.c_void_p]
    k.GlobalLock.restype = ctypes.c_void_p
    k.GlobalUnlock.argtypes = [ctypes.c_void_p]
    k.GlobalFree.argtypes = [ctypes.c_void_p]
    u.SetClipboardData.argtypes = [ctypes.c_uint, ctypes.c_void_p]
    u.SetClipboardData.restype = ctypes.c_void_p
    data = text.encode("utf-16-le") + b"\x00\x00"
    h = k.GlobalAlloc(GMEM_MOVEABLE, len(data))
    if not h:
        raise OSError("GlobalAlloc failed")
    p = k.GlobalLock(h)
    ctypes.memmove(p, data, len(data))
    k.GlobalUnlock(h)
    _clip_open()
    try:
        u.EmptyClipboard()
        if not u.SetClipboardData(CF_UNICODETEXT, h):
            k.GlobalFree(h)
            raise OSError("SetClipboardData failed")
    finally:
        u.CloseClipboard()


def clip_paste() -> str:
    u, k = ctypes.windll.user32, ctypes.windll.kernel32
    u.GetClipboardData.argtypes = [ctypes.c_uint]
    u.GetClipboardData.restype = ctypes.c_void_p
    k.GlobalLock.argtypes = [ctypes.c_void_p]
    k.GlobalLock.restype = ctypes.c_void_p
    k.GlobalUnlock.argtypes = [ctypes.c_void_p]
    _clip_open()
    try:
        h = u.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return ""
        p = k.GlobalLock(h)
        if not p:
            return ""
        try:
            return ctypes.wstring_at(p)
        finally:
            k.GlobalUnlock(h)
    finally:
        u.CloseClipboard()


def clamp(v: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, v))


def clamp01(v) -> float:
    return max(0.0, min(1.0, float(v)))


def press_combo(spec: str) -> None:
    parts = spec.lower().split("+")
    # одна клавиша-модификатор (например, просто Win) — нажать и отпустить
    if len(parts) == 1 and parts[0] in MODS:
        keyboard.press(MODS[parts[0]])
        keyboard.release(MODS[parts[0]])
        return
    mods = []
    for p in parts[:-1]:
        if p not in MODS:
            raise ValueError("bad modifier")
        mods.append(MODS[p])
    last = parts[-1]
    if last in NAMED:
        key = NAMED[last]
    elif len(last) == 1 and last.isalnum():
        key = last
    else:
        raise ValueError("bad key")
    for m in mods:
        keyboard.press(m)
    try:
        keyboard.press(key)
        keyboard.release(key)
    finally:
        for m in reversed(mods):
            keyboard.release(m)


def screen_size():
    try:
        u = ctypes.windll.user32
        return u.GetSystemMetrics(0), u.GetSystemMetrics(1)
    except Exception:
        return 1920, 1080


def run_custom(idx: int) -> None:
    """Кнопки только из твоего settings.json: {"name": "...", "open": "путь или сайт"} или {"run": "команда"}."""
    btn = settings.get("buttons", [])[idx]
    if "open" in btn:
        os.startfile(btn["open"])
    elif "run" in btn:
        subprocess.Popen(btn["run"], shell=True, creationflags=NO_WINDOW)


def share(text: str) -> dict:
    m = URL_RE.search(text)
    if m:
        webbrowser.open(m.group(0))
        return {"t": "shared", "s": "Ссылка открыта на компьютере"}
    clip_copy(text[:100000])
    return {"t": "shared", "s": "Текст скопирован в буфер компьютера"}


# ---------- игровой ввод: SendInput со сканкодами (как настоящая клавиатура и мышь) ----------
# Многие игры игнорируют обычные «виртуальные» нажатия, но видят сканкоды и относительную мышь.

ULONG_PTR = ctypes.c_size_t


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", ctypes.c_int32), ("dy", ctypes.c_int32), ("mouseData", ctypes.c_uint32),
                ("dwFlags", ctypes.c_uint32), ("time", ctypes.c_uint32), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_uint16), ("wScan", ctypes.c_uint16), ("dwFlags", ctypes.c_uint32),
                ("time", ctypes.c_uint32), ("dwExtraInfo", ULONG_PTR)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", ctypes.c_uint32), ("wParamL", ctypes.c_uint16), ("wParamH", ctypes.c_uint16)]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", ctypes.c_uint32), ("u", _INPUT_UNION)]


KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008
MOUSEEVENTF_MOVE = 0x0001
MOUSE_BUTTON_FLAGS = {1: (0x0002, 0x0004), 2: (0x0008, 0x0010)}   # (down, up)

GAME_VK = {"space": 0x20, "shift": 0x10, "ctrl": 0x11, "alt": 0x12, "tab": 0x09, "esc": 0x1B,
           "enter": 0x0D, "backspace": 0x08,
           "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28}
for _c in "abcdefghijklmnopqrstuvwxyz":
    GAME_VK[_c] = ord(_c.upper())
for _c in "0123456789":
    GAME_VK[_c] = ord(_c)
for _i in range(1, 13):
    GAME_VK[f"f{_i}"] = 0x6F + _i
GAME_EXTENDED = {"left", "up", "right", "down"}


def _send_input(inp: INPUT) -> None:
    ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


def game_key(name: str, down: bool) -> None:
    vk = GAME_VK[name]
    scan = ctypes.windll.user32.MapVirtualKeyW(vk, 0)     # виртуальный код -> сканкод
    flags = KEYEVENTF_SCANCODE | (0 if down else KEYEVENTF_KEYUP)
    if name in GAME_EXTENDED:
        flags |= KEYEVENTF_EXTENDEDKEY
    inp = INPUT(type=1)
    inp.ki = KEYBDINPUT(0, scan, flags, 0, 0)
    _send_input(inp)


def game_mouse_move(dx: int, dy: int) -> None:
    inp = INPUT(type=0)
    inp.mi = MOUSEINPUT(dx, dy, 0, MOUSEEVENTF_MOVE, 0, 0)
    _send_input(inp)


def game_mouse_button(b: int, down: bool) -> None:
    flags = MOUSE_BUTTON_FLAGS[b][0 if down else 1]
    inp = INPUT(type=0)
    inp.mi = MOUSEINPUT(0, 0, 0, flags, 0, 0)
    _send_input(inp)


_last_wake = 0.0


def wake_display(force: bool = False) -> None:
    """Будит погасший экран и сбрасывает таймер простоя Windows.
    pynput двигает курсор через SetCursorPos, а это не настоящий ввод и экран не будит. Поэтому раз в пару секунд
    делаем настоящий толчок мыши (SendInput) и возвращаем курсор на место. Клики и клавиши будят экран сами."""
    global _last_wake
    now = time.monotonic()
    if not force and now - _last_wake < 2.0:
        return
    _last_wake = now
    try:
        x, y = mouse.position
        game_mouse_move(1, 0)
        mouse.position = (x, y)
    except Exception as e:
        log(f"пробуждение экрана: {e!r}")


def release_held(held: set) -> None:
    """Отпускает всё зажатое (при обрыве связи, чтобы клавиши не «залипали»)."""
    for h in list(held):
        try:
            kind, val = h.split(":", 1)
            if kind == "k":
                game_key(val, False)
            else:
                game_mouse_button(int(val), False)
        except Exception as e:
            log(f"отпускание {h}: {e!r}")
    held.clear()


def dispatch(m: dict, held: set):
    """Выполняет команду. Может вернуть ответ телефону (словарь) или None."""
    t = m.get("t")
    if t == "move":
        wake_display()
        mouse.move(clamp(int(m["dx"]), -400, 400), clamp(int(m["dy"]), -400, 400))
    elif t == "wake":
        wake_display(True)
    elif t == "abs":
        wake_display()
        sw, sh = screen_size()
        mouse.position = (int(clamp01(m["x"]) * (sw - 1)), int(clamp01(m["y"]) * (sh - 1)))
    elif t == "btn":
        b = Button.right if int(m.get("b", 1)) == 2 else Button.left
        if m.get("down"):
            mouse.press(b)
        else:
            mouse.release(b)
    elif t == "click":
        b = Button.right if int(m.get("b", 1)) == 2 else Button.left
        mouse.click(b, clamp(int(m.get("n", 1)), 1, 3))
    elif t == "scroll":
        mouse.scroll(0, clamp(int(m["n"]), -20, 20))
    elif t == "key":
        press_combo(str(m["k"]))
    elif t == "text":
        keyboard.type(str(m["s"])[:500])
    elif t == "media":
        k = MEDIA[str(m["k"])]
        keyboard.press(k)
        keyboard.release(k)
    elif t == "system":
        SYSTEM[str(m["a"])]()
    elif t == "custom":
        run_custom(int(m["id"]))
    elif t == "share":
        return share(str(m["s"]))
    elif t == "clip_set":
        clip_copy(str(m["s"])[:100000])
    elif t == "clip_get":
        return {"t": "clip", "s": clip_paste()[:100000]}
    elif t == "keyd":
        name = str(m["k"]).lower()
        if name not in GAME_VK:
            raise ValueError("bad key")
        down = bool(m.get("down"))
        game_key(name, down)
        if down:
            held.add(f"k:{name}")
        else:
            held.discard(f"k:{name}")
    elif t == "gmove":
        game_mouse_move(clamp(int(m["dx"]), -600, 600), clamp(int(m["dy"]), -600, 600))
    elif t == "gbtn":
        b = int(m["b"])
        if b not in MOUSE_BUTTON_FLAGS:
            raise ValueError("bad button")
        down = bool(m.get("down"))
        game_mouse_button(b, down)
        if down:
            held.add(f"b:{b}")
        else:
            held.discard(f"b:{b}")
    elif t == "ping":
        pass
    else:
        raise ValueError("unknown type")
    return None


# ---------- картинка экрана ----------
# Пока на экране что-то меняется, шлём быстрые кадры (уже и легче, до 60 к/с).
# Как только картинка замерла на долю секунды, шлём один чёткий кадр (полный размер, высокое качество,
# цвет без потерь). Если экран не меняется, трафика почти нет.
# Кадр идёт бинарно: строка-заголовок JSON, затем ровно "n" байт JPEG.

# Ступени качества быстрого кадра: (ширина, JPEG). Сервер сам переключается между ними: пока картинка
# движется, главное плавность, поэтому при нехватке канала или процессора кадр становится меньше.
RUNGS = [(480, 30), (640, 35), (800, 40), (960, 45), (1280, 55), (1600, 62)]
# уровень: (самая высокая ступень, кадров/с, макс. ширина чёткого кадра, JPEG чёткого)
QUALITY = {
    1: (3, 30, 1280, 80),
    2: (4, 60, 1920, 85),
    3: (5, 45, 2560, 90),
    4: (2, 60, 1280, 75),     # «Макс. fps»: кадры поменьше, зато чаще
    5: (5, 60, 2560, 90),     # «Оригинал» (запасной JPEG)
}
SHARP_DELAY = 0.2       # через сколько секунд покоя слать чёткий кадр
IDLE_FPS = 15           # как часто смотреть на экран, когда ничего не происходит
MAX_BUFFER = 1_000_000  # лимит очереди на отправку
MAX_ENCODERS = 2        # сколько кадров кодируется одновременно (так fps выше, чем у одного потока)
STATS_EVERY = 3.0       # как часто писать в server.log, как идёт передача экрана

_tls = threading.local()
_grab_pool = ThreadPoolExecutor(1)   # mss привязан к потоку, поэтому снимаем всегда из одного
_enc_pool = ThreadPoolExecutor(2)


_dx_retry_at = 0.0     # после сбоя DXGI пробуем снова не раньше этого времени


def grab_dxgi():
    """Снимок через DXGI. Возвращает (байты, размер, формат) или None, если ещё нет ни одного кадра.
    Если экран не менялся, dxcam кадр не отдаёт, тогда возвращаем прошлый (тот же объект: сравнение мгновенное)."""
    cam = getattr(_tls, "dxcam", None)
    if cam is None:
        try:
            cam = dxcam.create(output_color="BGRA", processor_backend="numpy")
        except TypeError:
            cam = dxcam.create(output_color="BGRA")      # старая версия dxcam
        _tls.dxcam = cam
        _tls.dx_last = None
    frame = cam.grab()
    if frame is None:
        return _tls.dx_last
    h, w = frame.shape[0], frame.shape[1]
    _tls.dx_last = (frame.tobytes(), (w, h), "BGRX")
    return _tls.dx_last


def grab_raw():
    """Снимок основного монитора: (сырые байты, (ширина, высота), формат)."""
    global _dx_retry_at
    if dxcam is not None and time.monotonic() >= _dx_retry_at:
        try:
            r = grab_dxgi()
            if r is not None:
                return r
        except Exception as e:
            _dx_retry_at = time.monotonic() + 15      # например, сменился режим экрана или появилось окно UAC
            _tls.dxcam = None
            log(f"захват через DXGI не получился, 15 секунд беру обычный: {e!r}")
    w, h = screen_size()
    if mss is not None:
        sct = getattr(_tls, "sct", None)
        if sct is None:
            sct = _tls.sct = mss.mss()
        shot = sct.grab({"left": 0, "top": 0, "width": w, "height": h})
        return shot.raw, (shot.size[0], shot.size[1]), "BGRX"
    img = ImageGrab.grab().convert("RGB")
    return img.tobytes(), img.size, "RGB"


def encode_frame(raw, size, mode, width: int, quality: int, sharp: bool, sub=None) -> bytes:
    if mode == "BGRX":
        img = Image.frombuffer("RGB", size, raw, "raw", "BGRX", 0, 1)
    else:
        img = Image.frombytes("RGB", size, raw)
    sw, sh = size
    if width and sw > width:
        if sharp:
            img = img.resize((width, max(1, int(sh * width / sw))), Image.Resampling.LANCZOS)
        else:
            img = img.resize((width, max(1, int(sh * width / sw))), Image.Resampling.BILINEAR,
                             reducing_gap=2.0)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality, subsampling=(0 if sharp else 2) if sub is None else sub)
    return buf.getvalue()


def frame_packet(jpeg: bytes, cx: float, cy: float, sharp: bool, seq: int = 0, rung: int = 0) -> bytes:
    head = json.dumps({"t": "frame", "n": len(jpeg), "k": int(sharp), "s": seq, "r": rung,
                       "cx": round(cx, 4), "cy": round(cy, 4)}, separators=(",", ":"))
    return head.encode("ascii") + b"\n" + jpeg


def cursor_norm(size):
    x, y = mouse.position
    return x / size[0], y / size[1]


def sharp_params(rung: int, cap_w: int, cap_q: int):
    """Чёткий кадр: на медленном канале он меньше, чтобы не забивать очередь."""
    if rung <= 1:
        return min(cap_w, 1024), 70, 2
    if rung <= 3:
        return min(cap_w, 1280), 75, 2
    return cap_w, cap_q, 0


async def stream_screen(writer: asyncio.StreamWriter, st: dict) -> None:
    loop = asyncio.get_running_loop()
    prev_raw = None        # предыдущий снимок (чтобы понять, двигается ли картинка)
    last_raw = None        # то, что сейчас на телефоне
    last_change = time.monotonic()
    last_activity = last_change
    need_sharp = False
    last_cur = (-1.0, -1.0)
    enc_tasks = set()               # кадры, которые сейчас кодируются (до MAX_ENCODERS одновременно)
    ticket = 0                      # порядковый номер задачи кодирования
    written_ticket = 0              # последний записанный в сеть: более старый, обогнанный кадр выбрасываем
    seq = 0
    sent = {}                       # номер кадра -> (время отправки, байт, чёткий ли); ждёт подтверждения
    sent_log = collections.deque()  # (время, байт) быстрых кадров: сколько мы предлагали каналу
    acked_log = collections.deque() # (время, байт): сколько канал реально доставил
    srtt = None
    rung = None
    last_rung_change = 0.0
    calm_since = None
    ack_mode = True                 # старое приложение подтверждений не шлёт: тогда работаем без них
    ever_acked = False
    enc_ms = 0.0                    # скользящее среднее: кодирование кадра
    loop_ms = 0.0                   # скользящее среднее: захват и разбор снимка
    stat_t = time.monotonic()
    stat_frames = 0
    stat_bytes = 0
    why = ""

    async def send_frame(tk, raw, size, mode, width, quality, sharp, sub, cx, cy, r):
        nonlocal seq, ack_mode, enc_ms, stat_frames, stat_bytes, written_ticket
        try:
            t_enc = time.monotonic()
            jpeg = await loop.run_in_executor(_enc_pool, encode_frame, raw, size, mode, width, quality, sharp, sub)
            if not sharp:
                enc_ms = enc_ms * 0.8 + (time.monotonic() - t_enc) * 1000 * 0.2
            if st["on"] and tk > written_ticket:
                written_ticket = tk
                seq += 1
                pkt = frame_packet(jpeg, cx, cy, sharp, seq, r)
                writer.write(pkt)
                t_send = time.monotonic()
                sent[seq] = (t_send, len(pkt), sharp)
                if not sharp:
                    sent_log.append((t_send, len(pkt)))
                    stat_frames += 1
                    stat_bytes += len(pkt)
                if len(sent) > 150:          # подтверждений нет совсем
                    sent.clear()
                    ack_mode = False
        except Exception as e:
            log(f"кадр: {e!r}")

    try:
        while st["on"]:
            t0 = time.monotonic()
            max_rung, fps, sharp_w, sharp_q = QUALITY.get(st["level"], QUALITY[2])
            game = bool(st.get("game"))
            if game:                 # игра: важнее всего плавность, чёткие кадры и курсор не нужны
                max_rung, fps = max(1, min(3, st["level"])), 60
            phone_w = st.get("w", 0)
            if phone_w:
                sharp_w = min(sharp_w, phone_w)
            if st.pop("force", False):
                last_raw = None
                need_sharp = False
                rung = min(max_rung, 1 if game else 3)
            if rung is None:
                rung = min(max_rung, 1 if game else 3)
            rung = min(rung, max_rung)
            try:
                raw, size, mode = await loop.run_in_executor(_grab_pool, grab_raw)
            except Exception as e:
                log(f"экран: {e!r}")
                await asyncio.sleep(1)
                continue
            if not st["on"]:
                break
            now = time.monotonic()
            cx, cy = cursor_norm(size)

            # подтверждения от телефона
            acks = st.get("acks")
            while acks:
                a_seq, t_ack = acks.pop(0)
                ever_acked = True
                rec = sent.get(a_seq)
                for k in [k for k in sent if k <= a_seq]:
                    old = sent.pop(k)
                    if not old[2]:
                        acked_log.append((t_ack, old[1]))     # подтверждение номера N подтверждает и всё до него
                if rec and not rec[2]:
                    rtt = t_ack - rec[0]
                    srtt = rtt if srtt is None else srtt * 0.8 + rtt * 0.2
            while sent_log and now - sent_log[0][0] > 8:
                sent_log.popleft()
            while acked_log and now - acked_log[0][0] > 8:
                acked_log.popleft()

            # сколько мы предлагали каналу и сколько он доставил за последние 2 секунды
            # (предложенное берём со сдвигом на время доставки). Доставил заметно меньше значит канал узкий.
            span = 3.0
            lag = min(srtt or 0.3, 1.5)
            offered = sum(b for t, b in sent_log if now - lag - span <= t < now - lag)
            acked = sum(b for t, b in acked_log if now - span <= t <= now)
            ratio = acked / offered if offered > 150_000 else None
            rate_acked = acked / span                      # байт/с, который реально доходит

            period = 1.0 / fps
            cpu_slow = enc_ms > period * 1000 * 1.25      # кодирование не успевает: кадр меньше, кодируется быстрее
            cpu_ok = enc_ms < period * 1000 * 0.8
            if ack_mode and rung is not None:
                cooldown_ok = now - last_rung_change > 1.0
                if rung > 0 and cooldown_ok and ratio is not None and ratio < 0.8:
                    rung -= 1
                    last_rung_change = now
                    calm_since = None
                    why = "канал"
                elif rung > 0 and cooldown_ok and cpu_slow and (now - last_activity) < 1.0:
                    rung -= 1
                    last_rung_change = now
                    calm_since = None
                    why = "процессор"
                elif ratio is not None and ratio >= 0.92 and cpu_ok:
                    if calm_since is None:
                        calm_since = now
                    elif now - calm_since > 3.0 and rung < max_rung and now - last_rung_change > 3.0:
                        rung += 1
                        last_rung_change = now
                        calm_since = now
                        why = "запас"
                else:
                    calm_since = None

            moving = raw != prev_raw
            dirty = moving if last_raw is prev_raw else raw != last_raw
            prev_raw = raw
            if moving:
                last_change = now
                last_activity = now
            if (cx, cy) != last_cur:
                last_activity = now

            buf = writer.transport.get_write_buffer_size()
            if ack_mode and not ever_acked and sent and now - min(v[0] for v in sent.values()) > 2.0:
                ack_mode = False             # подтверждений нет: приложение старое
                sent.clear()
            if ack_mode:
                inflight = sum(v[1] for v in sent.values())
                cap = max(600_000, int(rate_acked * 1.5))   # в пути не больше, чем канал доставит за 1,5 с
                congested = (bool(sent) and inflight > cap) or buf > MAX_BUFFER
            else:
                congested = buf > 400_000
            busy = len(enc_tasks) >= MAX_ENCODERS
            link_idle = (not sent) or not ack_mode
            sent_now = False
            if (dirty or need_sharp) and not busy and not congested:
                if not game and now - last_change >= SHARP_DELAY:
                    if link_idle and not enc_tasks:
                        w, q, sub = sharp_params(rung, sharp_w, sharp_q)
                        ticket += 1
                        t = asyncio.ensure_future(
                            send_frame(ticket, raw, size, mode, w, q, True, sub, cx, cy, rung))
                        enc_tasks.add(t)
                        t.add_done_callback(enc_tasks.discard)
                        need_sharp = False
                        last_raw = raw
                        sent_now = True
                elif dirty:
                    w, q = RUNGS[rung]
                    if phone_w:
                        w = min(w, phone_w)
                    ticket += 1
                    t = asyncio.ensure_future(
                        send_frame(ticket, raw, size, mode, w, q, False, None, cx, cy, rung))
                    enc_tasks.add(t)
                    t.add_done_callback(enc_tasks.discard)
                    need_sharp = not game
                    last_raw = raw
                    sent_now = True
            if sent_now:
                last_cur = (cx, cy)
            elif not game and (cx, cy) != last_cur and buf < MAX_BUFFER:
                # картинка та же, сдвинулся только курсор: шлём маленькое сообщение
                writer.write((json.dumps({"t": "cur", "cx": round(cx, 4), "cy": round(cy, 4)},
                                         separators=(",", ":")) + "\n").encode("ascii"))
                last_cur = (cx, cy)

            work = time.monotonic() - t0
            loop_ms = loop_ms * 0.8 + work * 1000 * 0.2

            if now - stat_t >= STATS_EVERY:
                if stat_frames:
                    dt = now - stat_t
                    log(f"экран: {stat_frames / dt:.0f} кадр/с, ступень {rung + 1} из {max_rung + 1}"
                        f"{' (' + why + ')' if why else ''}, кадр {stat_bytes // stat_frames // 1024} КБ, "
                        f"кодирование {enc_ms:.0f} мс, захват {loop_ms:.0f} мс, "
                        f"доставляется {rate_acked * 8 / 1e6:.1f} Мбит/с, задержка {int((srtt or 0) * 1000)} мс")
                stat_t = now
                stat_frames = 0
                stat_bytes = 0
                why = ""

            active = now - last_activity < 1.0 or need_sharp
            period_s = 1.0 / (fps if active else IDLE_FPS)
            await asyncio.sleep(max(0.0, period_s - (time.monotonic() - t0)))
    except asyncio.CancelledError:
        pass
    except Exception as e:
        log(f"экран остановлен: {e!r}")


# ---------- видео H.264 ----------
# Вместо отдельных JPEG-кадров идёт видеопоток: он передаёт только изменения, поэтому при той же полосе картинка
# заметно чётче и плавнее. Кодирует видеокарта (NVENC, AMF, QSV), если драйвер позволяет, иначе процессор (x264).
# Если видео не запустилось, сервер сам возвращается к JPEG. Кадр: строка-заголовок JSON, затем байты H.264.
try:
    import av
    import numpy as np
    from av.video.frame import PictureType
except Exception:
    av = None
    np = None
    PictureType = None

VIDEO_LADDER = [1200, 2000, 3000, 4500, 6500, 9000, 12000, 16000, 24000, 32000, 40000]   # битрейт, кбит/с
# уровень: (ширина кадра, самая высокая ступень лестницы). 5 это «Оригинал»: родной размер экрана, до 40 Мбит/с
VIDEO_LEVELS = {1: (960, 3), 2: (1280, 5), 3: (1920, 7), 4: (1280, 4), 5: (2560, 10)}
VIDEO_CRF = {3: 19, 5: 17}              # чем меньше, тем чётче (остальные уровни и игра: 21)
X264_PRESETS = ["ultrafast", "superfast", "veryfast", "faster"]
HW_ENCODERS = ("h264_nvenc", "h264_amf", "h264_qsv")
_bad_encoders = set()
_venc_pool = ThreadPoolExecutor(1)      # кодирование строго по очереди: порядок кадров в потоке важен
VIDEO_PENDING = 2                       # сколько кадров одновременно ждут кодирования


def video_available() -> bool:
    return av is not None and np is not None


def _encoder_options(name: str, fps: int, kbps: int, preset: str, crf: int = 21) -> dict:
    gop = fps * 10
    if name == "libx264":
        buf = max(100, kbps * 5 // fps)
        return {"preset": preset, "tune": "zerolatency", "profile": "high", "crf": str(crf),
                "x264-params": (f"keyint={gop}:min-keyint={gop}:scenecut=0:repeat-headers=1:forced-idr=1:aud=0:"
                                f"force-cfr=1:vbv-maxrate={kbps}:vbv-bufsize={buf}:"
                                "colorprim=bt709:transfer=bt709:colormatrix=bt709:range=tv")}
    if name == "h264_nvenc":
        return {"preset": "p1", "tune": "ull", "rc": "cbr", "zerolatency": "1", "delay": "0",
                "forced-idr": "1", "profile": "high"}
    if name == "h264_amf":
        return {"usage": "ultralowlatency", "quality": "speed", "rc": "cbr", "profile": "high",
                "header_insertion_mode": "idr", "forced_idr": "1"}
    return {"preset": "veryfast", "look_ahead": "0", "async_depth": "1", "forced_idr": "1"}    # h264_qsv


class VideoEnc:
    """Один открытый кодировщик с фиксированными размером и битрейтом."""

    def __init__(self, name, ctx, pix, w, h, kbps):
        self.name, self.ctx, self.pix, self.w, self.h, self.kbps = name, ctx, pix, w, h, kbps
        self.n = 0
        self.conv = {"dst_colorspace": "ITU709", "dst_color_range": "MPEG"}


def _make_ctx(name: str, w: int, h: int, fps: int, kbps: int, preset: str, crf: int = 21):
    from fractions import Fraction
    ctx = av.CodecContext.create(name, "w")
    ctx.width, ctx.height = w, h
    pix = "nv12" if name == "h264_qsv" else "yuv420p"
    ctx.pix_fmt = pix
    ctx.time_base = Fraction(1, fps)
    ctx.framerate = Fraction(fps, 1)
    if name != "libx264":
        ctx.bit_rate = kbps * 1000
    ctx.gop_size = fps * 10
    ctx.max_b_frames = 0
    for attr, val in (("colorspace", 1), ("color_range", 1), ("color_primaries", 1), ("color_trc", 1)):
        try:
            setattr(ctx, attr, val)
        except Exception:
            pass
    ctx.options = _encoder_options(name, fps, kbps, preset, crf)
    ctx.open()
    return ctx, pix


def open_encoder(w: int, h: int, fps: int, kbps: int, preset: str, prefer: str = None, crf: int = 21) -> VideoEnc:
    """Пробует кодировщики по очереди (видеокарта, потом процессор) и возвращает первый рабочий."""
    names = [n for n in HW_ENCODERS if n in av.codecs_available and n not in _bad_encoders]
    if prefer and prefer in names:
        names.remove(prefer)
        names.insert(0, prefer)
    names.append("libx264")
    last = None
    for name in names:
        try:
            if name != "libx264":
                # пробный кодировщик: аппаратные падают не при открытии, а на первом кадре, если нет драйвера
                tctx, tpix = _make_ctx(name, 640, 360, fps, kbps, preset)
                tf = av.VideoFrame(640, 360, tpix)
                for plane in tf.planes:
                    plane.update(bytes(plane.buffer_size))
                for i in range(3):
                    tf.pts = i
                    tctx.encode(tf)
            ctx, pix = _make_ctx(name, w, h, fps, kbps, preset, crf)
            return VideoEnc(name, ctx, pix, w, h, kbps)
        except Exception as e:
            last = e
            if name != "libx264":
                _bad_encoders.add(name)
                log(f"кодировщик {name} недоступен: {e!r}")
    raise RuntimeError(f"нет рабочего кодировщика H.264: {last!r}")


def video_encode(enc: VideoEnc, raw, size, mode, force_key: bool) -> bytes:
    w0, h0 = size
    if mode == "BGRX":
        arr = np.frombuffer(raw, np.uint8).reshape(h0, w0, 4)
        fmt = "bgra"
    else:
        arr = np.frombuffer(raw, np.uint8).reshape(h0, w0, 3)
        fmt = "rgb24"
    fr = av.VideoFrame.from_ndarray(arr, format=fmt)
    try:
        fr = fr.reformat(width=enc.w, height=enc.h, format=enc.pix, **enc.conv)
    except (TypeError, ValueError):
        enc.conv = {}                                  # старая версия PyAV: без явных цветовых параметров
        fr = fr.reformat(width=enc.w, height=enc.h, format=enc.pix)
    fr.pts = enc.n
    enc.n += 1
    if force_key:
        fr.pict_type = PictureType.I
    return b"".join(bytes(p) for p in enc.ctx.encode(fr))


def has_idr(data: bytes) -> bool:
    i = 0
    for _ in range(12):
        j = data.find(b"\x00\x00\x01", i)
        if j < 0 or j + 3 >= len(data):
            return False
        if data[j + 3] & 0x1F == 5:
            return True
        i = j + 3
    return False


def video_packet(data: bytes, key: bool, enc: VideoEnc, cx: float, cy: float, rung: int, seq: int, ping: int) -> bytes:
    head = json.dumps({"t": "vid", "n": len(data), "k": int(key), "s": seq, "w": enc.w, "h": enc.h,
                       "r": rung, "p": ping, "cx": round(cx, 4), "cy": round(cy, 4)}, separators=(",", ":"))
    return head.encode("ascii") + b"\n" + data


def video_sharp_params(rate_acked: float, size, phone_w: int):
    """Чёткий кадр в покое: на быстром канале родной размер и почти без потерь, на медленном поменьше."""
    cap_w = min(size[0], phone_w or size[0], 2560)
    if rate_acked <= 0 or rate_acked >= 2_500_000:
        return cap_w, 90, 0
    if rate_acked >= 1_000_000:
        return min(cap_w, 1920), 84, 0
    return min(cap_w, 1280), 76, 2


class LinkMeter:
    """Следит по подтверждениям телефона, сколько байт канал реально доставляет."""

    def __init__(self):
        self.sent = {}                      # номер -> (время, байт)
        self.sent_log = collections.deque()
        self.acked_log = collections.deque()
        self.srtt = None
        self.ever_acked = False
        self.ack_mode = True

    def on_send(self, seq: int, size: int, counted: bool = True) -> None:
        now = time.monotonic()
        self.sent[seq] = (now, size, counted)       # counted=False: разовый чёткий кадр, в статистику канала не входит
        if counted:
            self.sent_log.append((now, size))
        if len(self.sent) > 200:            # подтверждений нет совсем: старое приложение
            self.sent.clear()
            self.ack_mode = False

    def process(self, acks, now: float) -> None:
        while acks:
            a_seq, t_ack = acks.pop(0)
            self.ever_acked = True
            rec = self.sent.get(a_seq)
            for k in [k for k in self.sent if k <= a_seq]:
                old = self.sent.pop(k)
                if old[2]:
                    self.acked_log.append((t_ack, old[1]))       # подтверждение номера N подтверждает и всё до него
            if rec and rec[2]:
                rtt = t_ack - rec[0]
                self.srtt = rtt if self.srtt is None else self.srtt * 0.8 + rtt * 0.2
        while self.sent_log and now - self.sent_log[0][0] > 8:
            self.sent_log.popleft()
        while self.acked_log and now - self.acked_log[0][0] > 8:
            self.acked_log.popleft()
        if self.ack_mode and not self.ever_acked and self.sent and now - min(v[0] for v in self.sent.values()) > 2.0:
            self.ack_mode = False
            self.sent.clear()

    def stats(self, now: float):
        """(доставлено / предложено за 3 с или None, байт/с, байт в пути)"""
        span = 3.0
        lag = min(self.srtt or 0.3, 1.5)
        offered = sum(b for t, b in self.sent_log if now - lag - span <= t < now - lag)
        acked = sum(b for t, b in self.acked_log if now - span <= t <= now)
        ratio = acked / offered if offered > 120_000 else None
        return ratio, acked / span, sum(v[1] for v in self.sent.values())


async def stream_video(writer: asyncio.StreamWriter, st: dict) -> None:
    loop = asyncio.get_running_loop()
    meter = LinkMeter()
    enc = None
    sig = None
    idx = None
    last_idx_change = 0.0
    calm_since = None
    prev_raw = None
    last_raw = None
    last_change = time.monotonic()
    last_activity = last_change
    last_cur = (-1.0, -1.0)
    seq = 0
    pending = 0
    want_key = True
    preset_i = None
    broken = False
    enc_ms = 0.0
    ratio_s = None
    slow_since = None
    fast_since = None
    need_sharp = False
    vgen = 0                        # растёт с каждым новым видеокадром: устаревший чёткий кадр выбрасываем
    next_t = time.monotonic()
    stat_t = time.monotonic()
    stat_frames = 0
    stat_bytes = 0
    fps = 60

    async def send_sharp(raw, size, mode, gen, w, q, sub_, cx, cy, r):
        nonlocal seq, pending
        try:
            jpeg = await loop.run_in_executor(_enc_pool, encode_frame, raw, size, mode, w, q, True, sub_)
            if st["on"] and gen == vgen:               # пока кодировали, картинка не должна была измениться
                seq += 1
                pkt = frame_packet(jpeg, cx, cy, True, seq, r)
                writer.write(pkt)
                meter.on_send(seq, len(pkt), False)
        except Exception as e:
            log(f"чёткий кадр: {e!r}")
        finally:
            pending -= 1

    async def work(enc_obj, raw, size, mode, force_key, cx, cy, r):
        nonlocal seq, pending, enc_ms, broken, stat_frames, stat_bytes
        try:
            t = time.monotonic()
            data = await loop.run_in_executor(_venc_pool, video_encode, enc_obj, raw, size, mode, force_key)
            enc_ms = enc_ms * 0.8 + (time.monotonic() - t) * 1000 * 0.2
            if data and st["on"]:
                seq += 1
                ping = int((meter.srtt or 0) * 1000)
                pkt = video_packet(data, has_idr(data), enc_obj, cx, cy, r, seq, ping)
                writer.write(pkt)
                meter.on_send(seq, len(pkt))
                stat_frames += 1
                stat_bytes += len(pkt)
        except Exception as e:
            log(f"видео: {e!r}")
            broken = True
        finally:
            pending -= 1

    try:
        while st["on"]:
            t0 = time.monotonic()
            game = bool(st.get("game"))
            level = st["level"]
            lvl_w, max_idx = VIDEO_LEVELS.get(level, VIDEO_LEVELS[2])
            if game:
                lvl_w, max_idx = VIDEO_LEVELS[max(1, min(3, level))]
            if idx is None or st.pop("force", False):
                idx = min(max_idx, 3)
                want_key = True
            idx = min(idx, max_idx)
            if st.pop("idr", False):
                want_key = True
            try:
                raw, size, mode = await loop.run_in_executor(_grab_pool, grab_raw)
            except Exception as e:
                log(f"экран: {e!r}")
                await asyncio.sleep(1)
                continue
            if not st["on"]:
                break
            now = time.monotonic()
            cx, cy = cursor_norm(size)
            phone_w = st.get("w", 0)
            out_w = min(lvl_w, phone_w or lvl_w, size[0])
            out_w -= out_w % 2
            out_h = int(round(size[1] * out_w / size[0]))
            out_h -= out_h % 2
            if preset_i is None:
                preset_i = 1 if (out_h >= 1000 or game) else 2
            preset = X264_PRESETS[preset_i]
            crf = 22 if game else VIDEO_CRF.get(level, 21)

            meter.process(st.get("acks"), now)
            ratio_raw, rate_acked, inflight = meter.stats(now)
            if ratio_raw is not None:
                ratio_s = ratio_raw if ratio_s is None else ratio_s * 0.7 + ratio_raw * 0.3
            ratio = ratio_s if ratio_raw is not None else None
            if meter.ack_mode and idx is not None:
                if ratio is not None and ratio < 0.8 and now - last_idx_change > 1.5:
                    target_kbps = rate_acked * 8 / 1000 * 0.8       # канал узкий: ставим 80% от того, что он доставляет
                    new = max([i for i, v in enumerate(VIDEO_LADDER) if v <= target_kbps] or [0])
                    if new < idx:
                        idx = new
                        last_idx_change = now
                        calm_since = None
                elif ratio is not None and ratio >= 0.9:
                    if calm_since is None:
                        calm_since = now
                    elif now - calm_since > 2.0 and idx < max_idx and now - last_idx_change > 2.0:
                        idx = min(max_idx, idx + (2 if idx < 5 else 1))
                        last_idx_change = now
                        calm_since = now
                elif ratio is not None and ratio < 0.8:
                    calm_since = None
                # между 0,8 и 0,9 (и когда данных мало) ничего не меняем: так шум не сбрасывает набор запаса

            # процессор x264: не успевает, берём режим побыстрее; есть запас, берём качественнее
            period = 1.0 / fps
            if enc is not None and enc.name == "libx264":
                if enc_ms > period * 1000 * 0.9 and preset_i > 0:
                    slow_since = slow_since or now
                    fast_since = None
                    if now - slow_since > 2.0:
                        preset_i -= 1
                        slow_since = None
                elif enc_ms < period * 1000 * 0.4 and preset_i < len(X264_PRESETS) - 1:
                    fast_since = fast_since or now
                    slow_since = None
                    if now - fast_since > 5.0:
                        preset_i += 1
                        fast_since = None
                else:
                    slow_since = None
                    fast_since = None

            new_sig = (out_w, out_h, VIDEO_LADDER[idx], preset, crf)
            if enc is None or sig != new_sig or broken:
                try:
                    prefer = enc.name if (enc is not None and not broken) else None
                    enc = await loop.run_in_executor(_venc_pool, open_encoder, out_w, out_h, fps,
                                                     VIDEO_LADDER[idx], preset, prefer, crf)
                except Exception as e:
                    log(f"видео не запустилось, работаю через JPEG: {e!r}")
                    writer.write(b'{"t":"vmode","codec":"jpeg"}\n')
                    st["codec"] = "jpeg"
                    await stream_screen(writer, st)
                    return
                sig = new_sig
                broken = False
                want_key = True
                writer.write((json.dumps({"t": "vmode", "codec": "h264", "enc": enc.name, "w": enc.w, "h": enc.h},
                                         separators=(",", ":")) + "\n").encode("ascii"))

            moving = raw != prev_raw
            dirty = moving if last_raw is prev_raw else raw != last_raw
            prev_raw = raw
            if moving:
                last_change = now
                last_activity = now
            if (cx, cy) != last_cur:
                last_activity = now

            buf = writer.transport.get_write_buffer_size()
            cap = max(300_000, int(rate_acked * 1.3))
            congested = (meter.ack_mode and bool(meter.sent) and inflight > cap) or buf > MAX_BUFFER
            # после остановки картинки шлём ещё полсекунды кадров: видео доуточняет изображение, получается чётко
            want = dirty or (not game and now - last_change < 0.6)
            sent_now = False
            if want and pending < VIDEO_PENDING and not congested:
                pending += 1
                vgen += 1
                asyncio.ensure_future(work(enc, raw, size, mode, want_key, cx, cy, idx))
                want_key = False
                last_raw = raw
                sent_now = True
                need_sharp = not game
            elif (need_sharp and not want and pending == 0 and not meter.sent and not congested
                  and now - last_change >= 0.7):
                # картинка замерла: поверх видео один чёткий кадр (цвет без потерь, родной размер), текст как на ПК
                w_s, q_s, sub_s = video_sharp_params(rate_acked, size, phone_w)
                pending += 1
                asyncio.ensure_future(send_sharp(raw, size, mode, vgen, w_s, q_s, sub_s, cx, cy, idx))
                need_sharp = False
                sent_now = True
            if sent_now:
                last_cur = (cx, cy)
            elif not game and (cx, cy) != last_cur and buf < MAX_BUFFER:
                writer.write((json.dumps({"t": "cur", "cx": round(cx, 4), "cy": round(cy, 4)},
                                         separators=(",", ":")) + "\n").encode("ascii"))
                last_cur = (cx, cy)

            if now - stat_t >= STATS_EVERY:
                if stat_frames:
                    dt = now - stat_t
                    log(f"видео: {stat_frames / dt:.0f} кадр/с, {stat_bytes * 8 / dt / 1e6:.1f} Мбит/с "
                        f"(лимит {VIDEO_LADDER[idx] / 1000:.1f}, ступень {idx + 1} из {max_idx + 1}), "
                        f"{enc.name} {enc.w}x{enc.h}, кодирование {enc_ms:.0f} мс, "
                        f"доставляется {rate_acked * 8 / 1e6:.1f} Мбит/с, задержка {int((meter.srtt or 0) * 1000)} мс")
                stat_t = now
                stat_frames = 0
                stat_bytes = 0

            active = now - last_activity < 1.0 or game or need_sharp
            next_t += 1.0 / (fps if active else IDLE_FPS)       # ровный ритм: расписание, а не «спим после каждого кадра»
            delay = next_t - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
            else:
                next_t = time.monotonic()                        # отстали: нагонять не пытаемся
    except asyncio.CancelledError:
        pass
    except Exception as e:
        log(f"видео остановлено: {e!r}")


# ---------- сеть ----------

fail_times: list = []


def locked() -> bool:
    now = time.time()
    fail_times[:] = [x for x in fail_times if now - x < 60]
    return len(fail_times) >= 5


async def send(writer: asyncio.StreamWriter, obj: dict) -> None:
    writer.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    await writer.drain()


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    st = {"on": False, "level": 2}
    screen_task = None
    held: set = set()
    try:
        peer = writer.get_extra_info("peername")[0]
        ip = ipaddress.ip_address(peer)
        if not peer_allowed(ip):
            await send(writer, {"ok": False, "err": "NOT_LOCAL"})
            return

        line = await asyncio.wait_for(reader.readline(), 5)
        msg = json.loads(line)
        if msg.get("t") != "hello":
            await send(writer, {"ok": False, "err": "BAD_MESSAGE"})
            return
        if msg.get("v") != VERSION:
            await send(writer, {"ok": False, "err": "BAD_VERSION"})
            return
        if locked():
            await send(writer, {"ok": False, "err": "LOCKED"})
            return
        reload_settings()
        if not hmac.compare_digest(str(msg.get("code", "")), str(settings["code"])):
            fail_times.append(time.time())
            await asyncio.sleep(1)
            await send(writer, {"ok": False, "err": "BAD_CODE"})
            return

        buttons = [{"id": i, "name": str(b.get("name", "?"))} for i, b in enumerate(settings.get("buttons", []))]
        await send(writer, {"ok": True, "v": VERSION, "name": socket.gethostname(), "buttons": buttons})
        log(f"подключился {peer}")

        window_start = time.monotonic()
        count = 0
        while True:
            line = await reader.readline()
            if not line:
                break
            now = time.monotonic()
            if now - window_start >= 1:
                window_start = now
                count = 0
            count += 1
            if count > 500:      # ограничение частоты: лишнее отбрасываем
                continue
            try:
                m = json.loads(line)
                if m.get("t") == "ack":
                    st.setdefault("acks", []).append((int(m.get("s", 0)), time.monotonic()))
                elif m.get("t") == "idr":
                    st["idr"] = True                  # телефон просит новый ключевой кадр (например, после сбоя)
                elif m.get("t") == "screen":
                    st["level"] = int(m.get("q", 2))
                    st["w"] = clamp(int(m.get("w", 0)), 0, 4096)
                    st["force"] = True
                    st["game"] = bool(m.get("game"))
                    codec = "h264" if (m.get("codec") == "h264" and video_available()) else "jpeg"
                    if m.get("on"):
                        if screen_task is not None and not screen_task.done() and st.get("codec") != codec:
                            screen_task.cancel()                      # переключили видео и JPEG: запускаем заново
                            screen_task = None
                        st["codec"] = codec
                        st["on"] = True
                        if screen_task is None or screen_task.done():
                            if codec == "h264":
                                screen_task = asyncio.create_task(stream_video(writer, st))
                            else:
                                writer.write(b'{"t":"vmode","codec":"jpeg"}\n')
                                screen_task = asyncio.create_task(stream_screen(writer, st))
                    else:
                        st["on"] = False
                else:
                    reply = dispatch(m, held)
                    if reply:
                        await send(writer, reply)
            except Exception as e:
                log(f"команда не выполнена: {e!r}")
                await send(writer, {"ok": False, "err": "BAD_MESSAGE"})
    except Exception as e:
        log(f"соединение закрыто: {e!r}")
    finally:
        release_held(held)
        st["on"] = False
        if screen_task is not None:
            screen_task.cancel()
        try:
            writer.close()
        except Exception:
            pass


def run_server() -> None:
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        loop.run_until_complete(asyncio.start_server(handle, "0.0.0.0", int(settings["port"])))
    except OSError as e:
        log(f"не удалось открыть порт {settings['port']}: {e!r}")
        return
    loop.run_forever()


def local_ips() -> list:
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))      # пакеты не отправляются, так узнаём свой адрес
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            ips.add(ip)
    except OSError:
        pass
    ok = [i for i in ips if peer_allowed(ipaddress.ip_address(i)) and not i.startswith("127.")]
    # домашние адреса первыми, Tailscale после них
    return sorted(ok, key=lambda i: (is_tailscale(ipaddress.ip_address(i)), i))


# ---------- значок в трее ----------

def make_icon() -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((4, 4, 60, 60), radius=14, fill=(124, 92, 255, 255))
    d.polygon([(26, 20), (26, 44), (46, 32)], fill=(255, 255, 255, 255))
    return img


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def autostart_enabled() -> bool:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, APP_NAME)
            return True
    except Exception:
        return False


def toggle_autostart(icon, item) -> None:
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if autostart_enabled():
                winreg.DeleteValue(k, APP_NAME)
            else:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, f'"{sys.executable}"')
    except Exception as e:
        log(f"автозапуск: {e!r}")


def renew_code(icon, item) -> None:
    settings["code"] = new_code()
    save_settings()
    icon.update_menu()
    icon.notify(f"Новый код: {settings['code']}", "Пульт для ПК")


def open_settings(icon, item) -> None:
    try:
        subprocess.Popen(["notepad.exe", str(SETTINGS_FILE)])
    except Exception as e:
        log(f"настройки: {e!r}")


def quit_app(icon, item) -> None:
    icon.stop()


def noop(icon, item) -> None:
    pass


def menu_items():
    ips = local_ips() or ["нет сети"]
    for ip in ips:
        kind = "Tailscale" if is_tailscale(ipaddress.ip_address(ip)) else "Адрес"
        yield pystray.MenuItem(f"{kind}: {ip}:{settings['port']}", noop, enabled=False)
    yield pystray.MenuItem(f"Код: {settings['code']}", noop, enabled=False)
    yield pystray.Menu.SEPARATOR
    yield pystray.MenuItem("Новый код", renew_code)
    yield pystray.MenuItem("Настройки и мои кнопки…", open_settings)
    if getattr(sys, "frozen", False):
        yield pystray.MenuItem("Запускать вместе с Windows", toggle_autostart,
                               checked=lambda item: autostart_enabled())
    yield pystray.MenuItem("Выход", quit_app)


def tune_windows() -> None:
    """Точный таймер и чуть повышенный приоритет: без этого Windows будит программу раз в 15,6 мс,
    из-за чего кадры выходят неровно (16 или 31 мс) и плавность страдает."""
    try:
        ctypes.windll.winmm.timeBeginPeriod(1)
    except Exception:
        pass
    try:
        ctypes.windll.kernel32.SetPriorityClass(ctypes.windll.kernel32.GetCurrentProcess(), 0x00008000)  # выше обычного
    except Exception:
        pass


def main() -> None:
    tune_windows()
    threading.Thread(target=run_server, daemon=True).start()
    icon = pystray.Icon(APP_NAME, make_icon(), "Пульт для ПК", pystray.Menu(menu_items))

    def setup(i):
        i.visible = True
        ips = local_ips()
        where = f"{ips[0]}:{settings['port']}" if ips else "нет сети"
        i.notify(f"Адрес {where}, код {settings['code']}", "Пульт для ПК запущен")

    icon.run(setup)


if __name__ == "__main__":
    main()
