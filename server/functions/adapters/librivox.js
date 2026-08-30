/**
 * LibriVox — записи книг общественного достояния, читанные добровольцами.
 * API открытый и отдаёт сразу список дорожек.
 */
const { fetchJson } = require("../lib/http");

const BASE = "https://librivox.org/api/feed/audiobooks";

function make(config = {}) {
  const limit = config.limit || 8;

  return {
    id: "librivox",
    name: "LibriVox",

    async search(query) {
      // Поле сравнивается точно, а с ^ — «начинается с»: одного запроса почти
      // всегда мало, поэтому пробуем и по названию, и по автору.
      const attempts = [
        `${BASE}/?title=^${encodeURIComponent(query)}&format=json&limit=${limit}&extended=1`,
        `${BASE}/?author=^${encodeURIComponent(query)}&format=json&limit=${limit}&extended=1`,
      ];

      const books = new Map();
      for (const url of attempts) {
        const { data } = await fetchJson(url).catch(() => ({ data: null }));
        for (const book of data?.books || []) {
          if (!books.has(book.id)) books.set(book.id, book);
        }
        if (books.size >= limit) break;
      }

      return [...books.values()].map((book) => ({
        id: String(book.id),
        title: book.title,
        author: book.authors?.map((a) => [a.first_name, a.last_name].filter(Boolean).join(" ")).join(", ") || "LibriVox",
        description: stripTags(book.description),
        source: "librivox",
        page_url: book.url_librivox || null,
        chapters_count: Number(book.num_sections) || 0,
      }));
    },

    async book(id) {
      const url = `${BASE}/?id=${encodeURIComponent(id)}&format=json&extended=1`;
      const { data } = await fetchJson(url);
      const book = data?.books?.[0];
      if (!book) return null;

      const chapters = (book.sections || [])
        .filter((section) => section.listen_url)
        .map((section) => ({
          title: section.title || `Часть ${section.section_number}`,
          audio_url: section.listen_url,
          duration: section.playtime || undefined,
        }));

      if (chapters.length === 0) return null;

      return {
        id: String(book.id),
        title: book.title,
        author: book.authors?.map((a) => [a.first_name, a.last_name].filter(Boolean).join(" ")).join(", ") || "LibriVox",
        description: stripTags(book.description),
        source: "librivox",
        page_url: book.url_librivox || null,
        chapters,
      };
    },
  };
}

function stripTags(text) {
  if (!text) return null;
  return String(text).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim() || null;
}

module.exports = { make };
