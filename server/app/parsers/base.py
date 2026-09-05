from __future__ import annotations

from abc import ABC, abstractmethod

from app.config import SiteConfig
from app.models import BookDetail, SearchItem


class SiteParser(ABC):
    """Один парсер = один способ вытащить данные с сайта: обычным HTTP-запросом
    (StaticParser) или через браузер для страниц, рендерящихся JS (DynamicParser).
    Оба реализуют один и тот же контракт, поэтому агрегатору всё равно, кто ответил.
    """

    def __init__(self, config: SiteConfig):
        self.config = config

    @abstractmethod
    async def search(self, query: str) -> list[SearchItem]:
        ...

    @abstractmethod
    async def fetch_detail(self, book_url: str) -> BookDetail:
        ...
