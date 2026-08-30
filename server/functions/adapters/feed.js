/**
 * Обычная лента RSS или Atom.
 *
 * Авторские озвучки чаще всего публикуются именно так, и это самый честный
 * источник: автор сам выложил файлы и сам дал ленту.
 */
const cheerio = require("cheerio");
const { fetchText } = require("../lib/http");

function make(config) {
  const id = config.id;

  return {
    id,
    name: config.name || id,

    async search(query) {
      const book = await this.book(config.url);
      if (!book) return [];
      // Лента — это одна «книга»; в выдачу она попадает, если запрос совпал
      // с названием ленты или хотя бы с одной серией.
      const needle = query.toLowerCase();
      const matches =
        book.title.toLowerCase().includes(needle) ||
        book.chapters.some((chapter) => chapter.title.toLowerCase().includes(needle));
      return matches ? [{ ...book, chapters: undefined, chapters_count: book.chapters.length }] : [];
    },

    async book(url) {
      const { body } = await fetchText(url || config.url);
      const $ = cheerio.load(body, { xmlMode: true });

      const chapters = $("item, entry")
        .toArray()
        .map((element) => {
          const item = $(element);
          const enclosure = item.find("enclosure").attr("url") || item.find("link[rel=enclosure]").attr("href");
          if (!enclosure) return null;
          return {
            title: (item.find("title").first().text() || "").trim(),
            audio_url: enclosure,
            duration: (item.find("itunes\\:duration").first().text() || "").trim() || undefined,
          };
        })
        .filter(Boolean);

      if (chapters.length === 0) return null;

      // Ленты отдают свежее первым — книгу удобнее слушать с начала.
      chapters.reverse();

      return {
        id: config.url,
        title: (config.name || $("channel > title, feed > title").first().text() || "Лента").trim(),
        author: (config.author || $("itunes\\:author").first().text() || config.name || "").trim(),
        cover_url: $("itunes\\:image").attr("href") || $("channel > image > url").text() || null,
        source: id,
        page_url: config.url,
        chapters,
      };
    },
  };
}

module.exports = { make };
