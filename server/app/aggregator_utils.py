from __future__ import annotations

import base64


def encode_book_id(site_key: str, book_url: str) -> str:
    """id книги = "site_key:base64url(book_url)" — самодостаточный, не требует базы данных."""
    token = base64.urlsafe_b64encode(book_url.encode("utf-8")).decode("ascii").rstrip("=")
    return f"{site_key}:{token}"


def decode_book_id(book_id: str) -> tuple[str, str]:
    site_key, _, token = book_id.partition(":")
    if not token:
        raise ValueError(f"Некорректный id книги: {book_id!r}")
    padding = "=" * (-len(token) % 4)
    book_url = base64.urlsafe_b64decode(token + padding).decode("utf-8")
    return site_key, book_url
