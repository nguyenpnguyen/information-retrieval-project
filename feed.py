#!/usr/bin/env python3
"""Nạp danh sách url (mỗi dòng một url) cho mediator rồi bật gửi url. Crawl chạy tiếp, lan ra theo link reader tìm được,
tới khi tắt service hoặc gọi POST /stop.

    python3 feed.py                          # link_all.txt -> http://localhost:5200
    python3 feed.py ds.txt --mediator http://10.177.29.102:5200 --batch 2000
"""

import argparse
import json
import time
import urllib.error
import urllib.request


def call(base, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data, {"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def wait_ready(base, seconds=60):
    for _ in range(seconds):
        try:
            return call(base, "GET", "/status")
        except (urllib.error.URLError, ConnectionError):
            time.sleep(1)
    raise SystemExit(f"mediator {base} không trả lời sau {seconds} giây")


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("file", nargs="?", default="link_all.txt")
    p.add_argument("--mediator", default="http://localhost:5200")
    p.add_argument("--batch", type=int, default=5000)
    a = p.parse_args()
    base = a.mediator.rstrip("/")

    with open(a.file, encoding="utf-8") as f:
        urls = [line.strip() for line in f if line.strip()]
    print(f"{len(urls)} url trong {a.file}; mediator: {wait_ready(base)}")

    total = {"accepted": 0, "duplicates": 0, "rejected": 0}
    for i in range(0, len(urls), a.batch):
        got = call(base, "POST", "/add", {"urls": urls[i : i + a.batch]})
        for k in total:
            total[k] += got[k]
        print(f"  {min(i + a.batch, len(urls))}/{len(urls)}: {got}")
    print(f"xong: {total['accepted']} mới, {total['duplicates']} trùng, {total['rejected']} loại")
    print(f"bật gửi url: {call(base, 'POST', '/start')}")
    print(f"dừng: curl -X POST {base}/stop   chạy tiếp: curl -X POST {base}/start   xem: curl {base}/status")


if __name__ == "__main__":
    main()
