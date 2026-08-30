/**
 * Загрузка страниц. Node 20 умеет fetch сам, поэтому зависимостей здесь нет.
 */
const { remember } = require("./cache");

const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
  "Chrome/120.0.0.0 Safari/537.36 Audiokniga-Aggregator/1.0";

const DEFAULT_TIMEOUT_MS = 12000;
const CACHE_TTL_MS = 5 * 60 * 1000;

/** Тело ответа плюс конечный адрес: по нему достраиваются относительные ссылки. */
async function fetchText(url, { timeoutMs = DEFAULT_TIMEOUT_MS, cache = true } = {}) {
  const load = async () => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(url, {
        redirect: "follow",
        signal: controller.signal,
        headers: {
          "User-Agent": USER_AGENT,
          Accept: "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
          "Accept-Language": "ru,en;q=0.8",
        },
      });
      if (!response.ok) {
        throw new Error(`ответил ${response.status}`);
      }
      return { body: await response.text(), url: response.url || url };
    } finally {
      clearTimeout(timer);
    }
  };

  return cache ? remember(`GET ${url}`, CACHE_TTL_MS, load) : load();
}

async function fetchJson(url, options) {
  const { body, url: finalUrl } = await fetchText(url, options);
  return { data: JSON.parse(body), url: finalUrl };
}

/**
 * Ограничитель одновременных запросов. Без него страница выдачи на два десятка
 * книг превращается в два десятка одновременных соединений к чужому сайту —
 * так себя ведут не гости, а нагрузочный тест.
 */
async function mapLimited(items, limit, worker) {
  const results = new Array(items.length);
  let next = 0;

  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const index = next++;
      if (index >= items.length) return;
      try {
        results[index] = await worker(items[index], index);
      } catch (error) {
        results[index] = null;
      }
    }
  });

  await Promise.all(runners);
  return results.filter((item) => item !== null && item !== undefined);
}

/** Обещание с крайним сроком: один медленный источник не держит весь поиск. */
function withTimeout(promise, ms, fallback) {
  return Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve(fallback), ms)),
  ]);
}

module.exports = { fetchText, fetchJson, mapLimited, withTimeout, USER_AGENT };
