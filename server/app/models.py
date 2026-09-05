from __future__ import annotations

from pydantic import BaseModel


class SearchItem(BaseModel):
    id: str
    source: str
    title: str
    author: str | None = None
    cover_url: str | None = None
    duration_sec: int | None = None
    url: str


class ChapterItem(BaseModel):
    title: str
    mp3_url: str
    duration_sec: int | None = None


class BookDetail(BaseModel):
    id: str
    source: str
    title: str
    author: str | None = None
    cover_url: str | None = None
    description: str | None = None
    url: str
    chapters: list[ChapterItem]
