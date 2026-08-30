/**
 * Сайт, описанный настройкой, а не кодом.
 *
 * Здесь намеренно нет ни одного конкретного сайта: адаптер получает шаблон поиска
 * и несколько CSS-селекторов из sources.json и работает по ним. Добавить каталог —
 * значит дописать шесть строк в файл настроек, не трогая код и не пересобирая
 * приложение. Файлы со страницы книги снимаются общим извлекателем.
 */
const { fetchText, mapLimited } = require("../lib/http");
const { extractTracks, extractMeta, absolute } = require("../lib/extract");
const cheerio = require("cheerio");

/** Сколько страниц книг открывать за один поиск: каждая — отдельный запрос к сайту. */
const DEFAULT_LIMIT = 8;

function make(config) {
  const id = config.id;

  return {
    id,
    name: config.name || id,

    async search(query) {
      const url = config.search_url.replace("{q}", encodeURIComponent(query));
      const { body, url: finalUrl } = await fetchText(url);
      const $ = cheerio.load(body);

      const cards = $(config.result || "a").toArray().slice(0, config.limit || DEFAULT_LIMIT);

      return cards
        .map((element) => {
          const card = $(element);
          const pick = (selector) => (selector ? card.find(selector).first() : card);

          const link = pick(config.link || "a").attr("href");
          const page = link ? absolute(link, finalUrl) : null;
          if (!page) return null;

          const cover = pick(config.cover || "img").attr("src");
          return {
            id: page,
            title: (pick(config.title || "a").text() || "").trim(),
            author: (config.author ? card.find(config.author).first().text() : "").trim() || config.name,
            cover_url: cover ? absolute(cover, finalUrl) : null,
            source: id,
            page_url: page,
          };
        })
        .filter((book) => book && book.title);
    },

    /** Страница книги: заголовок с неё же, главы — общим извлекателем. */
    async book(pageUrl) {
      const { body, url: finalUrl } = await fetchText(pageUrl);
      const meta = extractMeta(body, finalUrl);
      const chapters = extractTracks(body, finalUrl);
      if (chapters.length === 0) return null;

      return {
        id: pageUrl,
        title: meta.title || config.name,
        author: config.name,
        cover_url: meta.cover_url,
        description: meta.description,
        source: id,
        page_url: pageUrl,
        chapters,
      };
    },
  };
}

/** Проверить настройку заранее: молча пропущенный источник хуже явной ошибки. */
function validate(config) {
  if (!config.id) return "нет поля id";
  if (!config.search_url) return "нет поля search_url";
  if (!config.search_url.includes("{q}")) return "в search_url нет подстановки {q}";
  return null;
}

module.exports = { make, validate };
