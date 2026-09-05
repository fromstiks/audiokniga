import respx
from httpx import Response

from app.aggregator import Aggregator
from app.aggregator_utils import decode_book_id, encode_book_id
from app.config import SiteConfig

SITE = SiteConfig(
    key="test",
    name="Test site",
    base_url="https://example.test",
    search_url="https://example.test/search?q={query}",
    selectors={
        "result_item": "div.card",
        "title": "h3",
        "author": ".author",
        "link": "a@href",
    },
    detail_selectors={
        "title": "h1",
        "author": ".author",
        "chapter_item": "li.chapter",
        "chapter_title": ".title",
        "chapter_audio": "a@href",
        "chapter_duration": ".duration",
    },
    rate_limit_sec=0.0,
)

SEARCH_HTML = """
<html><body>
  <div class="card"><h3>Книга раз</h3><span class="author">Автор А</span><a href="/book/1">.</a></div>
  <div class="card"><h3>Книга два</h3><span class="author">Автор Б</span><a href="/book/2">.</a></div>
</body></html>
"""

DETAIL_HTML = """
<html><body>
  <h1>Книга раз</h1><span class="author">Автор А</span>
  <li class="chapter"><span class="title">Глава 1</span><a href="/files/1.mp3">.</a><span class="duration">12:30</span></li>
  <li class="chapter"><span class="title">Глава 2</span><a href="/files/2.mp3">.</a><span class="duration">5:00</span></li>
</body></html>
"""


def test_encode_decode_book_id_roundtrip():
    book_id = encode_book_id("test", "https://example.test/book/1")
    assert decode_book_id(book_id) == ("test", "https://example.test/book/1")


@respx.mock
async def test_search_returns_items_from_configured_site():
    respx.get("https://example.test/search?q=title").mock(return_value=Response(200, text=SEARCH_HTML))
    aggregator = Aggregator(sites=[SITE])

    items = await aggregator.search("title")

    assert len(items) == 2
    assert items[0].title == "Книга раз"
    assert items[0].author == "Автор А"
    assert items[0].url == "https://example.test/book/1"


@respx.mock
async def test_details_parses_chapters_with_direct_mp3_links():
    respx.get("https://example.test/book/1").mock(return_value=Response(200, text=DETAIL_HTML))
    aggregator = Aggregator(sites=[SITE])
    book_id = encode_book_id("test", "https://example.test/book/1")

    detail = await aggregator.details(book_id)

    assert detail.title == "Книга раз"
    assert len(detail.chapters) == 2
    assert detail.chapters[0].mp3_url == "https://example.test/files/1.mp3"
    assert detail.chapters[0].duration_sec == 12 * 60 + 30
