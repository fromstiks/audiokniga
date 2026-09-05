from __future__ import annotations

import logging
import time

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse

from app.aggregator import Aggregator
from app.models import BookDetail, SearchItem

logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="Audiokniga Aggregator",
    description=(
        "Внутренний API-агрегатор: обходит настроенные в sites.yaml источники и отдаёт "
        "единый JSON с метаданными и прямыми ссылками на MP3. Подключайте сюда только "
        "сайты, на скрейпинг которых у вас есть право (собственный сервис, партнёрский "
        "доступ, открытые API)."
    ),
    version="0.1.0",
)

aggregator = Aggregator()

_CACHE_TTL_SEC = 60.0
_search_cache: dict[str, tuple[float, list[SearchItem]]] = {}
_detail_cache: dict[str, tuple[float, BookDetail]] = {}


@app.get("/healthz")
async def healthz() -> dict:
    return {"status": "ok", "sites": aggregator.site_keys}


@app.get("/search", response_model=list[SearchItem])
async def search(q: str = Query(..., min_length=1, description="Название или автор")) -> list[SearchItem]:
    cached = _search_cache.get(q)
    if cached and time.monotonic() - cached[0] < _CACHE_TTL_SEC:
        return cached[1]
    items = await aggregator.search(q)
    _search_cache[q] = (time.monotonic(), items)
    return items


@app.get("/book", response_model=BookDetail)
async def book(id: str = Query(..., description="id из ответа /search")) -> BookDetail:
    cached = _detail_cache.get(id)
    if cached and time.monotonic() - cached[0] < _CACHE_TTL_SEC:
        return cached[1]
    try:
        detail = await aggregator.details(id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:  # ошибка конкретного сайта — не 500 на пустом месте
        raise HTTPException(status_code=502, detail=f"Источник не ответил: {exc}") from exc
    _detail_cache[id] = (time.monotonic(), detail)
    return detail


@app.exception_handler(Exception)
async def unhandled_exception_handler(_, exc: Exception) -> JSONResponse:
    logging.getLogger("aggregator").exception("Необработанная ошибка")
    return JSONResponse(status_code=500, content={"detail": str(exc)})
