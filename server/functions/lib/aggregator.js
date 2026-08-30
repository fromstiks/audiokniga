/**
 * Опрос всех источников разом и склейка ответов.
 *
 * Источники отвечают вразнобой, и ждать самого медленного нельзя: у функции есть
 * свой предел времени, а у человека — терпение. Поэтому у каждого источника свой
 * крайний срок, а те, кто не успел, попадают в sources с пометкой.
 */
const { build } = require("../adapters");
const { withTimeout } = require("./http");
const { remember } = require("./cache");

/** Столько ждём один источник. Остальные к этому времени уже ответили. */
const SOURCE_TIMEOUT_MS = 9000;
const SEARCH_CACHE_MS = 5 * 60 * 1000;

async function searchAll(query, baseUrl) {
  const { adapters, problems } = build();

  const answers = await Promise.all(
    adapters.map(async (adapter) => {
      const started = Date.now();
      try {
        const work = remember(`search ${adapter.id} ${query}`, SEARCH_CACHE_MS, () => adapter.search(query));
        // Опоздавшая ошибка гонку уже проиграла, но без обработчика Node ругается
        // на неё в логи. Гасим отдельной веткой — на саму гонку это не влияет.
        work.catch(() => {});

        const books = await withTimeout(work, SOURCE_TIMEOUT_MS, null);
        if (books === null) {
          return { id: adapter.id, name: adapter.name, count: 0, error: "не ответил вовремя" };
        }
        return {
          id: adapter.id,
          name: adapter.name,
          count: books.length,
          ms: Date.now() - started,
          books,
        };
      } catch (error) {
        return { id: adapter.id, name: adapter.name, count: 0, error: String(error.message || error) };
      }
    }),
  );

  const results = [];
  for (const answer of answers) {
    for (const book of answer.books || []) {
      results.push(normalize(book, answer.id, baseUrl));
    }
  }

  return {
    results,
    sources: answers.map(({ books, ...rest }) => rest),
    // Ошибку настройки нельзя проглатывать: иначе источник просто «не ищет».
    config_problems: problems,
  };
}

async function loadBook(sourceId, localId, baseUrl) {
  const { adapters } = build();
  const adapter = adapters.find((item) => item.id === sourceId);
  if (!adapter) return null;

  const book = await adapter.book(localId);
  if (!book) return null;
  return normalize(book, sourceId, baseUrl);
}

/**
 * Приведение к формату, который понимает приложение. Главное здесь — book_url:
 * по нему телефон потом заберёт главы, не повторяя поиск.
 */
function normalize(book, sourceId, baseUrl) {
  const localId = String(book.id ?? book.page_url ?? "");
  const bookUrl = `${baseUrl}/book?src=${encodeURIComponent(sourceId)}&u=${encodeURIComponent(localId)}`;

  const normalized = {
    id: `${sourceId}:${localId}`,
    title: book.title,
    author: book.author || null,
    cover_url: book.cover_url || null,
    description: book.description || null,
    source: sourceId,
    page_url: book.page_url || null,
    book_url: bookUrl,
  };

  if (Array.isArray(book.chapters)) {
    normalized.chapters = book.chapters.map((chapter, index) => ({
      index,
      title: chapter.title || `Глава ${index + 1}`,
      audio_url: chapter.audio_url,
      duration: chapter.duration,
      size_bytes: chapter.size_bytes,
    }));
    normalized.chapters_count = normalized.chapters.length;
  } else if (book.chapters_count) {
    normalized.chapters_count = book.chapters_count;
  }

  return normalized;
}

module.exports = { searchAll, loadBook, normalize };
