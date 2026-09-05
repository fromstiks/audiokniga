from __future__ import annotations

import re

from bs4 import BeautifulSoup, Tag

_DURATION_RE = re.compile(r"(?:(\d+):)?(\d{1,2}):(\d{2})$")


def extract(node: Tag | BeautifulSoup, spec: str | None) -> str | None:
    """spec — это CSS-селектор, опционально с "@атрибут" в конце.

    Примеры: "h1.title" (текст узла), "img.cover@src" (значение атрибута).
    Пустой/отсутствующий spec -> None.
    """
    if not spec:
        return None
    selector, _, attr = spec.partition("@")
    target = node.select_one(selector) if selector else node
    if target is None:
        return None
    if attr:
        value = target.get(attr)
        return value.strip() if isinstance(value, str) else None
    text = target.get_text(strip=True)
    return text or None


def extract_all(node: Tag | BeautifulSoup, spec: str | None) -> list[Tag]:
    if not spec:
        return []
    return node.select(spec)


def parse_duration_sec(text: str | None) -> int | None:
    """Понимает "1:02:03", "45:30" и голые секунды."""
    if not text:
        return None
    text = text.strip()
    match = _DURATION_RE.search(text)
    if match:
        hours = int(match.group(1)) if match.group(1) else 0
        minutes = int(match.group(2))
        seconds = int(match.group(3))
        return hours * 3600 + minutes * 60 + seconds
    digits = re.sub(r"[^\d]", "", text)
    return int(digits) if digits else None
