#!/usr/bin/env python3
"""Serve a narrow, HMD-readable top snapshot on localhost only."""
from __future__ import annotations

import os
import re
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8765
WIDTH = 36
PROC_ROWS = 10


def _bar(pct: float, width: int = 14) -> str:
    pct = max(0.0, min(100.0, pct))
    fill = int(round(width * pct / 100.0))
    return "#" * fill + "-" * (width - fill)


def _cpu_pct() -> float:
    def read() -> tuple[int, int]:
        with open("/proc/stat", encoding="ascii") as fh:
            nums = [int(x) for x in fh.readline().split()[1:]]
        idle = nums[3] + nums[4]
        return idle, sum(nums)

    i1, t1 = read()
    time.sleep(0.15)
    i2, t2 = read()
    dt = max(1, t2 - t1)
    return 100.0 * (1.0 - (i2 - i1) / dt)


def _mem() -> tuple[str, str, float]:
    info: dict[str, int] = {}
    with open("/proc/meminfo", encoding="ascii") as fh:
        for line in fh:
            key, val = line.split(":", 1)
            info[key] = int(val.strip().split()[0])
    total = info["MemTotal"] / 1024 / 1024
    avail = info.get("MemAvailable", info["MemFree"]) / 1024 / 1024
    used = max(0.0, total - avail)
    swap_t = info.get("SwapTotal", 0) / 1024 / 1024
    swap_f = info.get("SwapFree", 0) / 1024 / 1024
    swap_u = max(0.0, swap_t - swap_f)
    return f"{used:.1f}/{total:.0f}G", f"{swap_u:.1f}G", 100.0 * used / total if total else 0.0


def _load() -> str:
    with open("/proc/loadavg", encoding="ascii") as fh:
        a, b, c = fh.read().split()[:3]
    return f"{a} {b} {c}"


def _uptime() -> str:
    with open("/proc/uptime", encoding="ascii") as fh:
        secs = int(float(fh.read().split()[0]))
    days, rem = divmod(secs, 86400)
    hours, rem = divmod(rem, 3600)
    mins = rem // 60
    if days:
        return f"{days}d {hours}h {mins:02d}m"
    return f"{hours}h {mins:02d}m"


def _procs() -> list[str]:
    out = subprocess.check_output(
        ["ps", "-eo", "pid,pcpu,pmem,comm", "--sort=-pcpu"],
        text=True,
        env={**os.environ, "LC_ALL": "C"},
    )
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split(None, 3)
        if len(parts) < 4:
            continue
        pid, cpu, mem, comm = parts
        if comm in {"ps", "top", "head"}:
            continue
        name = comm[:12]
        rows.append(f"{int(pid):7d} {float(cpu):5.1f} {float(mem):4.1f} {name}")
        if len(rows) >= PROC_ROWS:
            break
    return rows


def snapshot() -> str:
    cpu = _cpu_pct()
    ram, swap, ram_pct = _mem()
    host = os.uname().nodename[:18]
    now = time.strftime("%H:%M:%S")
    lines = [
        f"{host:<18} {now:>16}"[:WIDTH],
        f"up {_uptime():<10} load {_load()}"[:WIDTH],
        f"CPU {cpu:5.1f}% {_bar(cpu)}"[:WIDTH],
        f"RAM {ram:<10} {_bar(ram_pct)}"[:WIDTH],
        f"swp {swap}"[:WIDTH],
        "    PID   CPU  MEM COMMAND"[:WIDTH],
    ]
    lines.extend(row[:WIDTH] for row in _procs())
    text = "\n".join(lines) + "\n"
    return re.sub(r"\x1b\[[0-9;]*[mK]", "", text)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        return

    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] not in ("/", "/top"):
            self.send_error(404)
            return
        body = snapshot().encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"top snapshot on http://{HOST}:{PORT}/top", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
