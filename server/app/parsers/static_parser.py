from __future__ import annotations

from urllib.parse import urljoin

import httpx
from bs4 import BeautifulSoup

from app.aggregator_utils import encode_book_id
from app.models import BookDetail, ChapterItem, SearchItem
from app.parsers.base import SiteParser
from app.parsers.selectors import extract, extract_all, parse_duration_sec


class StaticParser(SiteParser):
    """Сайты без JS-рендеринга: обычный GET + BeautifulSoup."""

    async def _get(self, url: str) -> BeautifulSoup:
        headers = {"User-Agent": "Audiokniga-Aggregator/1.0", **self.config.request_headers}
        async with httpx.AsyncClient(timeout=self.config.timeout_sec, follow_redirects=True) as client:
            response = await client.get(url, headers=headers)
            response.raise_for_status()
            return BeautifulSoup(response.text, "lxml")

    async def search(self, query: str) -> list[SearchItem]:
        url = self.config.search_url.format(query=query)
        soup = await self._get(url)
        sel = self.config.selectors
        items: list[SearchItem] = []
        for card in extract_all(soup, sel.get("result_item")):
            link = extract(card, sel.get("link"))
            if not link:
                continue
            book_url = urljoin(self.config.base_url, link)
            title = extract(card, sel.get("title")) or "Без названия"
            items.append(
                SearchItem(
                    id=encode_book_id(self.config.key, book_url),
                    source=self.config.key,
                    title=title,
                    author=extract(card, sel.get("author")),
                    cover_url=_abs(self.config.base_url, extract(card, sel.get("cover"))),
                    duration_sec=parse_duration_sec(extract(card, sel.get("duration"))),
                    url=book_url,
                )
            )
        return items

    async def fetch_detail(self, book_url: str) -> BookDetail:
        soup = await self._get(book_url)
        sel = self.config.detail_selectors
        chapters: list[ChapterItem] = []
        for node in extract_all(soup, sel.get("chapter_item")):
            audio = extract(node, sel.get("chapter_audio"))
            if not audio:
                continue
            chapters.append(
                ChapterItem(
                    title=extract(node, sel.get("chapter_title")) or f"Часть {len(chapters) + 1}",
                    mp3_url=urljoin(self.config.base_url, audio),
                    duration_sec=parse_duration_sec(extract(node, sel.get("chapter_duration"))),
                )
            )
        return BookDetail(
            id=encode_book_id(self.config.key, book_url),
            source=self.config.key,
            title=extract(soup, sel.get("title")) or "Без названия",
            author=extract(soup, sel.get("author")),
            cover_url=_abs(self.config.base_url, extract(soup, sel.get("cover"))),
            description=extract(soup, sel.get("description")),
            url=book_url,
            chapters=chapters,
        )


def _abs(base_url: str, maybe_relative: str | None) -> str | None:
    return urljoin(base_url, maybe_relative) if maybe_relative else None
