"""Điều khiển (block 3, port 5200): ``POST /add``, ``POST /stop``, ``POST /start``, ``GET /status``
plus the loop that feeds the download nodes."""

from __future__ import annotations

import asyncio
import contextlib
import functools
import json
import logging
import time
from collections.abc import AsyncIterator

from aiohttp import web
from aiohttp.typedefs import Handler

from mediator.api.common import error, json_object
from mediator.application.controller.add_urls import add_urls
from mediator.application.controller.dispatch import Dispatcher
from mediator.application.controller.ports import Clock, Downloader, UrlQueue
from mediator.domain.controller.models import ControllerConfig

log = logging.getLogger(__name__)

SUMMARY_SECONDS = 30.0
RUNNING_TEXT = "đang chạy"
STOPPED_TEXT = "đã dừng"
# Set while urls may be sent to the downloaders; ``/stop`` clears it, a restart runs again.
RUNNING = web.AppKey("running", asyncio.Event)


@web.middleware
async def allow_any_origin(request: web.Request, handler: Handler) -> web.StreamResponse:
    """Let a dashboard page opened from disk (``file://``) read the answers and press start/stop."""
    response = await handler(request)
    response.headers["Access-Control-Allow-Origin"] = "*"
    return response


def create_app(queue: UrlQueue, clock: Clock) -> web.Application:
    running = asyncio.Event()
    running.set()

    async def status(_: web.Request) -> web.Response:
        counts = queue.counts(clock.now())
        return web.json_response(
            {
                "status": RUNNING_TEXT if running.is_set() else STOPPED_TEXT,
                "total": counts.total,
                "due": counts.due,
            },
            dumps=functools.partial(json.dumps, ensure_ascii=False),  # curl shows "đã dừng"
        )

    async def stop(request: web.Request) -> web.Response:
        running.clear()
        log.info("lệnh dừng từ %s: ngừng gửi url, hàng đợi giữ nguyên", request.remote)
        return await status(request)

    async def start(request: web.Request) -> web.Response:
        running.set()
        log.info("lệnh chạy từ %s: gửi url tiếp", request.remote)
        return await status(request)

    async def add(request: web.Request) -> web.Response:
        body = await json_object(request)
        urls = body.get("urls")
        if not isinstance(urls, list) or not all(isinstance(url, str) for url in urls):
            return error(400, "urls must be a list of strings")
        result = add_urls(queue, urls, clock)
        if urls:  # the healthcheck posts an empty list every 10 s; keep it out of the log
            log.info(
                "đã nhận %d url từ %s: %d mới, %d trùng, %d loại",
                len(urls),
                request.remote,
                result.accepted,
                result.duplicates,
                result.rejected,
            )
            for url, outcome in result.outcomes:
                log.info("  %-5s %s", outcome.removeprefix("bị "), url)
        return web.json_response(
            {
                "accepted": result.accepted,
                "duplicates": result.duplicates,
                "rejected": result.rejected,
            }
        )

    app = web.Application(client_max_size=16 * 1024 * 1024, middlewares=[allow_any_origin])
    app[RUNNING] = running
    app.router.add_post("/add", add)
    app.router.add_post("/stop", stop)
    app.router.add_post("/start", start)
    app.router.add_get("/status", status)
    return app


def create_service(
    queue: UrlQueue, downloader: Downloader, clock: Clock, config: ControllerConfig
) -> web.Application:
    """``create_app`` with the dispatch and summary loops running for the app's lifetime."""
    app = create_app(queue, clock)
    dispatcher = Dispatcher(queue, downloader, clock, config)

    async def background(_: web.Application) -> AsyncIterator[None]:
        tasks = [
            asyncio.create_task(dispatch_loop(dispatcher, config.tick_seconds, app[RUNNING])),
            asyncio.create_task(summary_loop(queue, dispatcher, clock)),
        ]
        yield
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        await downloader.close()

    app.cleanup_ctx.append(background)
    return app


async def dispatch_loop(
    dispatcher: Dispatcher, tick_seconds: float, running: asyncio.Event
) -> None:
    while True:
        await running.wait()
        try:
            sent = await dispatcher.step()
        except Exception:
            log.exception("dispatch step failed")
            sent = False
        if not sent:
            await asyncio.sleep(tick_seconds)


async def summary_loop(
    queue: UrlQueue, dispatcher: Dispatcher, clock: Clock, every: float = SUMMARY_SECONDS
) -> None:
    """Log the queue and node state every ``every`` seconds, only when it changed."""
    last = ""
    while True:
        await asyncio.sleep(every)
        now = clock.now()
        counts = queue.counts(now)
        ready = set(dispatcher.rotation.order(now))
        nodes = ", ".join(
            f"{node} sẵn sàng" if node in ready else f"{node} đang nghỉ tới {_clock_time(rest)}"
            for node in dispatcher.config.downloaders
            for rest in [dispatcher.rotation.resting.get(node, 0.0)]
        )
        line = f"tổng: {counts.total} url, {counts.due} chờ gửi | downloader: {nodes}"
        if line != last:
            log.info("%s", line)
            last = line


def _clock_time(seconds: float) -> str:
    return time.strftime("%H:%M:%S", time.localtime(seconds))
