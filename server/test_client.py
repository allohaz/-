"""Простой тестовый клиент: python test_client.py 127.0.0.1 8765 КОД [screen]

С "screen h264" просит видео H.264 и считает кадры (в песочнице сервер, возможно, ответит JPEG).
Без "screen": подключается, двигает курсор и нажимает Win+D (показать рабочий стол).
С "screen": просит картинку экрана 3 секунды и печатает, сколько кадров в секунду пришло
(двигай мышь или листай что-нибудь на ПК: в покое кадры не шлются, это нормально)."""
import json
import socket
import sys
import time

VERSION = 2

host, port, code = sys.argv[1], int(sys.argv[2]), sys.argv[3]
want_screen = len(sys.argv) > 4 and sys.argv[4] == "screen"
want_h264 = len(sys.argv) > 5 and sys.argv[5] == "h264"
s = socket.create_connection((host, port), timeout=5)
f = s.makefile("rwb")


def send(obj):
    f.write((json.dumps(obj) + "\n").encode("utf-8"))
    f.flush()


send({"v": VERSION, "t": "hello", "code": code})
print("ответ:", f.readline().decode("utf-8").strip())

if want_screen:
    msg = {"v": VERSION, "t": "screen", "on": True, "q": 2, "w": 1920}
    if want_h264:
        msg["codec"] = "h264"
    send(msg)
    s.settimeout(0.2)
    buf = b""
    t0 = time.time()
    fast = sharp = cur = size = vid = keys = 0
    mode = "?"
    while time.time() - t0 < 3:
        try:
            chunk = s.recv(1 << 20)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            pass                      # экран не меняется: кадров нет, это нормально
        while True:                   # разбираем всё, что накопилось: заголовок, затем n байт JPEG
            nl = buf.find(b"\n")
            if nl < 0:
                break
            head = json.loads(buf[:nl])
            if head["t"] == "vid":
                end = nl + 1 + head["n"]
                if len(buf) < end:
                    break
                size += head["n"]
                vid += 1
                keys += head["k"]
                buf = buf[end:]
            elif head["t"] == "frame":
                end = nl + 1 + head["n"]
                if len(buf) < end:
                    break
                size += head["n"]
                if head["k"]:
                    sharp += 1
                else:
                    fast += 1
                buf = buf[end:]
            else:
                if head["t"] == "cur":
                    cur += 1
                elif head["t"] == "vmode":
                    mode = head.get("codec", "?") + " " + head.get("enc", "")
                buf = buf[nl + 1:]
    dt = time.time() - t0
    print(f"режим: {mode}; видеокадров: {vid} ({vid / dt:.1f}/с, ключевых {keys}); JPEG быстрых: {fast} ({fast / dt:.1f}/с), "
          f"чётких: {sharp}; сдвигов курсора: {cur}; трафик: {size / dt / 1e6 * 8:.1f} Мбит/с")
    send({"v": VERSION, "t": "screen", "on": False, "q": 2})
else:
    for _ in range(20):
        send({"v": VERSION, "t": "move", "dx": 10, "dy": 0})
        time.sleep(0.02)
    send({"v": VERSION, "t": "key", "k": "win+d"})
    time.sleep(0.5)
    send({"v": VERSION, "t": "key", "k": "win+d"})
s.close()
