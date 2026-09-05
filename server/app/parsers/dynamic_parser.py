from __future__ import annotations

from urllib.parse import urljoin

from bs4 import BeautifulSoup
from playwright.async_api import async_playwright

from app.aggregator_utils import encode_book_id
from app.models import BookDetail, ChapterItem, SearchItem
from app.parsers.base import SiteParser
from app.parsers.selectors import extract, extract_all, parse_duration_sec


class DynamicParser(SiteParser):
    """Сайты, где список/плеер собирается JS-ом: рендерим страницу headless-Chromium
    и дальше разбираем итоговый HTML теми же селекторами, что и StaticParser.
    """

    async def _render(self, url: str) -> BeautifulSoup:
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=True)
            try:
                page = await browser.new_page(user_agent="Audiokniga-Aggregator/1.0")
                await page.goto(url, timeout=self.config.timeout_sec * 1000, wait_until="networkidle")
                html = await page.content()
            finally:
                await browser.close()
        return BeautifulSoup(html, "lxml")

    async def search(self, query: str) -> list[SearchItem]:
        url = self.config.search_url.format(query=query)
        soup = await self._render(url)
        sel = self.config.selectors
        items: list[SearchItem] = []
        for card in extract_all(soup, sel.get("result_item")):
            link = extract(card, sel.get("link"))
            if not link:
                continue
            book_url = urljoin(self.config.base_url, link)
            items.append(
                SearchItem(
                    id=encode_book_id(self.config.key, book_url),
                    source=self.config.key,
                    title=extract(card, sel.get("title")) or "Без названия",
                    author=extract(card, sel.get("author")),
                    cover_url=_abs(self.config.base_url, extract(card, sel.get("cover"))),
                    duration_sec=parse_duration_sec(extract(card, sel.get("duration"))),
                    url=book_url,
                )
            )
        return items

    async def fetch_detail(self, book_url: str) -> BookDetail:
        soup = await self._render(book_url)
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
