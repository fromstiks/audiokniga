#!/usr/bin/env python3
"""
Парсер аудиокниг с knigavuhe.org

Достаёт список глав (id, название, прямая ссылка на mp3) со страницы книги
и может сразу скачать все файлы.

Использование:
    python knigavuhe_parser.py <url_книги>                  # только показать список глав (JSON)
    python knigavuhe_parser.py <url_книги> --download out/  # скачать все главы в папку out/

Пример url книги:
    https://knigavuhe.org/book/zhilec-3/
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    "Referer": "https://knigavuhe.org/",
}

# Паттерн встречающихся в HTML страницы объектов вида:
# "id":123,"title":"Жилец 01","url":"https:\/\/u4.knigavuhe.org\/.../zhilec-01.mp3"
CHAPTER_RE = re.compile(
    r'"id":(\d+),"title":"((?:\\.|[^"\\])*)","url":"((?:\\.|[^"\\])*\.mp3)"'
)

# Заголовок книги (meta og:title) и автор — для имени файлов/папки
TITLE_RE = re.compile(r'"og:title"\s+content="([^"]*)"')


def fetch_html(url: str) -> str:
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = resp.read()
    return raw.decode("utf-8", errors="replace")


def json_unescape(s: str) -> str:
    """Декодирует JSON-экранирование (\\u0416, \\/  и т.п.) внутри найденного фрагмента."""
    try:
        return json.loads(f'"{s}"')
    except json.JSONDecodeError:
        return s.replace("\\/", "/")


def extract_chapters(html: str):
    chapters = []
    seen_ids = set()
    for m in CHAPTER_RE.finditer(html):
        ch_id, title_raw, url_raw = m.groups()
        if ch_id in seen_ids:
            continue
        seen_ids.add(ch_id)
        chapters.append({
            "id": ch_id,
            "title": json_unescape(title_raw),
            "url": json_unescape(url_raw),
        })
    return chapters


def extract_book_title(html: str) -> str:
    m = TITLE_RE.search(html)
    return m.group(1) if m else "book"


def sanitize_filename(name: str) -> str:
    return re.sub(r'[\\/*?:"<>|]', "_", name).strip()


def download_chapters(chapters, out_dir: str, delay: float = 0.5):
    os.makedirs(out_dir, exist_ok=True)
    total = len(chapters)
    for i, ch in enumerate(chapters, 1):
        fname = sanitize_filename(ch["title"]) + ".mp3"
        fpath = os.path.join(out_dir, fname)
        if os.path.exists(fpath):
            print(f"[{i}/{total}] уже есть: {fname}")
            continue
        print(f"[{i}/{total}] скачиваю: {fname}")
        req = urllib.request.Request(ch["url"], headers=HEADERS)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp, open(fpath, "wb") as f:
                f.write(resp.read())
        except Exception as e:
            print(f"  ОШИБКА: {e}", file=sys.stderr)
        time.sleep(delay)  # вежливая пауза, чтобы не долбить сервер подряд


def main():
    parser = argparse.ArgumentParser(description="Парсер аудиокниг knigavuhe.org")
    parser.add_argument("url", help="URL страницы книги, например https://knigavuhe.org/book/zhilec-3/")
    parser.add_argument("--download", metavar="DIR", help="Скачать все главы в указанную папку")
    args = parser.parse_args()

    html = fetch_html(args.url)
    chapters = extract_chapters(html)
    book_title = extract_book_title(html)

    if not chapters:
        print("Главы не найдены. Возможно, изменилась структура страницы.", file=sys.stderr)
        sys.exit(1)

    print(f"Книга: {book_title}")
    print(f"Найдено глав: {len(chapters)}")

    if args.download:
        out_dir = os.path.join(args.download, sanitize_filename(book_title))
        download_chapters(chapters, out_dir)
        print(f"Готово. Файлы в: {out_dir}")
    else:
        print(json.dumps(chapters, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
