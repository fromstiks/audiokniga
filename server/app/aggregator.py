from __future__ import annotations

import asyncio
import logging
import time

from app.aggregator_utils import decode_book_id
from app.config import SiteConfig, load_sites
from app.models import BookDetail, SearchItem
from app.parsers.base import SiteParser
from app.parsers.dynamic_parser import DynamicParser
from app.parsers.static_parser import StaticParser

logger = logging.getLogger("aggregator")


def _build_parser(site: SiteConfig) -> SiteParser:
    if site.engine == "dynamic":
        return DynamicParser(site)
    return StaticParser(site)


class Aggregator:
    """Держит по одному парсеру на сайт и обходит их параллельно на каждый запрос.
    Один упавший сайт не должен портить результаты остальных.
    """

    def __init__(self, sites: list[SiteConfig] | None = None):
        self._sites = {s.key: s for s in (sites if sites is not None else load_sites())}
        self._parsers: dict[str, SiteParser] = {key: _build_parser(site) for key, site in self._sites.items()}
        self._last_request_at: dict[str, float] = {}

    @property
    def site_keys(self) -> list[str]:
        return list(self._sites.keys())

    async def _throttle(self, site_key: str) -> None:
        site = self._sites[site_key]
        last = self._last_request_at.get(site_key, 0.0)
        wait = site.rate_limit_sec - (time.monotonic() - last)
        if wait > 0:
            await asyncio.sleep(wait)
        self._last_request_at[site_key] = time.monotonic()

    async def search(self, query: str) -> list[SearchItem]:
        async def run_one(site_key: str) -> list[SearchItem]:
            await self._throttle(site_key)
            try:
                return await self._parsers[site_key].search(query)
            except Exception:
                logger.exception("Источник %s не ответил на поиск %r", site_key, query)
                return []

        results = await asyncio.gather(*(run_one(key) for key in self._sites))
        return [item for site_items in results for item in site_items]

    async def details(self, book_id: str) -> BookDetail:
        site_key, book_url = decode_book_id(book_id)
        parser = self._parsers.get(site_key)
        if parser is None:
            raise KeyError(f"Источник «{site_key}» не подключён")
        await self._throttle(site_key)
        return await parser.fetch_detail(book_url)
