/**
 * Поиск аудиофайлов на произвольной странице.
 *
 * Ровно то же, что делает приложение в MediaScraper, и по тем же причинам:
 * сначала честный обход DOM, потом — сырой текст. Второй проход нужен, потому что
 * плеер сплошь и рядом получает адрес файла из встроенного скрипта: ссылка на
 * странице есть, но ни в одном атрибуте её нет.
 */
const cheerio = require("cheerio");

const AUDIO_EXTENSIONS = [".mp3", ".m4a", ".m4b", ".ogg", ".oga", ".opus", ".aac", ".wav", ".flac"];

/** Атрибуты, в которых плееры держат адрес файла. */
const URL_ATTRIBUTES = [
  "src", "href", "data-src", "data-file", "data-url", "data-track",
  "data-mp3", "data-audio", "data-source", "content",
];

function looksLikeAudio(url) {
  if (!url) return false;
  const clean = url.split("?")[0].split("#")[0].toLowerCase();
  return AUDIO_EXTENSIONS.some((extension) => clean.endsWith(extension));
}

function absolute(url, baseUrl) {
  try {
    return new URL(url, baseUrl).toString();
  } catch (error) {
    return null;
  }
}

/**
 * Возвращает главы в том порядке, в каком они стоят на странице: для книги это
 * и есть порядок глав, и терять его нельзя.
 */
function extractTracks(html, baseUrl) {
  const $ = cheerio.load(html);
  const found = [];
  const seen = new Set();

  const add = (rawUrl, title) => {
    const url = absolute(rawUrl, baseUrl);
    if (!url || !looksLikeAudio(url) || seen.has(url)) return;
    seen.add(url);
    found.push({ title: (title || "").trim(), audio_url: url });
  };

  // Первый проход: настоящий DOM в порядке разметки.
  $("*").each((_, element) => {
    const node = $(element);
    for (const attribute of URL_ATTRIBUTES) {
      const value = node.attr(attribute);
      if (value) add(value, node.attr("title") || node.text());
    }
  });

  // Второй проход: адреса внутри скриптов. Слэши там экранированы —
  // "https:\/\/site.ru\/file.mp3", — и без разворачивания не распознаются.
  const plain = html.replace(/\\\//g, "/");
  const pattern = new RegExp(
    `https?://[^\\s"'<>\\\\)]+?(?:${AUDIO_EXTENSIONS.map((e) => e.slice(1)).join("|")})`,
    "gi",
  );
  for (const match of plain.matchAll(pattern)) {
    add(match[0], "");
  }

  return found.map((track, index) => ({
    title: track.title || `Глава ${index + 1}`,
    audio_url: track.audio_url,
  }));
}

/** Название, обложка и описание страницы — из мета-тегов, а не выражениями. */
function extractMeta(html, baseUrl) {
  const $ = cheerio.load(html);
  const meta = (name) =>
    $(`meta[property="${name}"]`).attr("content") || $(`meta[name="${name}"]`).attr("content") || null;

  const cover = meta("og:image") || $("img").first().attr("src") || null;
  return {
    title: (meta("og:title") || $("h1").first().text() || $("title").text() || "").trim(),
    description: (meta("og:description") || meta("description") || "").trim() || null,
    cover_url: cover ? absolute(cover, baseUrl) : null,
  };
}

module.exports = { extractTracks, extractMeta, looksLikeAudio, absolute };
