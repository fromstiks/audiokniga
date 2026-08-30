/**
 * Internet Archive — открытый API, публичное достояние.
 *
 * Включён по умолчанию, чтобы сервер что-то умел сразу после развёртывания:
 * пустой агрегатор невозможно отличить от сломанного.
 */
const { fetchJson } = require("../lib/http");

const SEARCH =
  "https://archive.org/advancedsearch.php?q=%QUERY%&fl%5B%5D=identifier&fl%5B%5D=title" +
  "&fl%5B%5D=creator&fl%5B%5D=description&rows=%ROWS%&page=1&output=json";

const AUDIO_FORMATS = ["VBR MP3", "MP3", "128Kbps MP3", "64Kbps MP3", "Ogg Vorbis"];

function make(config = {}) {
  const rows = config.limit || 10;

  return {
    id: "archive",
    name: "Internet Archive",

    async search(query) {
      const q = `(${query}) AND mediatype:(audio)`;
      const url = SEARCH.replace("%QUERY%", encodeURIComponent(q)).replace("%ROWS%", String(rows));
      const { data } = await fetchJson(url);

      const docs = data?.response?.docs || [];
      return docs.map((doc) => ({
        id: doc.identifier,
        title: doc.title || doc.identifier,
        author: Array.isArray(doc.creator) ? doc.creator.join(", ") : doc.creator || "Internet Archive",
        description: typeof doc.description === "string" ? doc.description : null,
        cover_url: `https://archive.org/services/img/${doc.identifier}`,
        source: "archive",
        page_url: `https://archive.org/details/${doc.identifier}`,
      }));
    },

    async book(identifier) {
      const { data } = await fetchJson(`https://archive.org/metadata/${encodeURIComponent(identifier)}`);
      const files = data?.files || [];

      const chapters = files
        .filter((file) => AUDIO_FORMATS.includes(file.format))
        .sort((a, b) => String(a.name).localeCompare(String(b.name), "ru", { numeric: true }))
        .map((file) => ({
          title: file.title || String(file.name).replace(/\.[^.]+$/, ""),
          audio_url: `https://archive.org/download/${encodeURIComponent(identifier)}/${encodeURIComponent(file.name)}`,
          duration: file.length || undefined,
          size_bytes: Number(file.size) || undefined,
        }));

      if (chapters.length === 0) return null;

      const meta = data?.metadata || {};
      return {
        id: identifier,
        title: meta.title || identifier,
        author: Array.isArray(meta.creator) ? meta.creator.join(", ") : meta.creator || "Internet Archive",
        description: typeof meta.description === "string" ? meta.description : null,
        cover_url: `https://archive.org/services/img/${identifier}`,
        source: "archive",
        page_url: `https://archive.org/details/${identifier}`,
        chapters,
      };
    },
  };
}

module.exports = { make };
