# Audiokniga Aggregator (сервер)

Внутренний API-агрегатор для Android-приложения: обходит сайты, перечисленные в
`sites.yaml`, каждый — своим парсером (обычный HTTP или headless-браузер), и отдаёт
единый JSON с метаданными и прямыми ссылками на MP3.

**Подключайте сюда только источники, на скрейпинг которых у вас есть право** —
собственный сервис, партнёрский доступ, открытый API. Обход платного доступа,
авторизации или защиты чужого сайта — вне задач этого кода.

## Быстрый старт

```bash
cd server
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m playwright install chromium   # только если используете engine: dynamic

cp sites.example.yaml sites.yaml
# отредактируйте sites.yaml под свой сайт — см. комментарии в файле

uvicorn app.main:app --reload
```

Проверка: `curl "http://127.0.0.1:8000/search?q=война"`.

## API

### `GET /search?q=<строка>`

```json
[
  {
    "id": "mycorp:aHR0cHM6Ly9hdWRpb2Jvb2tzLmV4YW1wbGUtY29ycC5pbnRlcm5hbC9ib29rLzEyMw",
    "source": "mycorp",
    "title": "Война и мир",
    "author": "Лев Толстой",
    "cover_url": "https://.../cover.jpg",
    "duration_sec": 61200,
    "url": "https://audiobooks.example-corp.internal/book/123"
  }
]
```

### `GET /book?id=<id из /search>`

```json
{
  "id": "mycorp:aHR0...",
  "source": "mycorp",
  "title": "Война и мир",
  "author": "Лев Толстой",
  "description": "...",
  "chapters": [
    {"title": "Часть 1, глава 1", "mp3_url": "https://.../ch1.mp3", "duration_sec": 1820}
  ]
}
```

`id` — это `site_key:base64url(url_страницы_книги)`, сервер сам расшифровывает его
на `/book`, чтобы не держать отдельную базу данных для сопоставления.

## Как описать свой сайт

Каждый сайт в `sites.yaml` — это набор CSS-селекторов, а не код. Формат селектора:

- `"h3.title"` — взять текст узла;
- `"img.cover@src"` — взять значение атрибута (после `@`).

Два движка:

| `engine`  | Когда использовать | Как работает |
|-----------|--------------------|---------------|
| `static`  | Обычный HTML, ссылки на MP3 есть в исходном коде страницы | `httpx` + `BeautifulSoup` |
| `dynamic` | Список/плеер собирается JS-ом на клиенте | Playwright рендерит страницу headless-Chromium, дальше те же селекторы |

Полный пример с комментариями — в `sites.example.yaml`.

## Тесты

```bash
pip install -r requirements-dev.txt
pytest
```

Тесты гоняют парсер на захардкоженном HTML (через `respx`), без обращения к сети.

## Docker

```bash
docker build -t audiokniga-aggregator .
docker run -p 8000:8000 -v $(pwd)/sites.yaml:/srv/sites.yaml audiokniga-aggregator
```

## Подключение к Android-приложению

В приложении это провайдер `AggregatorApiProvider` (см. `app/src/main/java/.../data/provider/`).
Адрес сервера задаётся в настройках приложения — по умолчанию агрегатор выключен, пока
адрес не указан.
