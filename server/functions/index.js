/**
 * Общий поиск по нескольким источникам сразу.
 *
 * Приложение умеет опрашивать источники само, и для обычных сайтов этого хватает.
 * Сервер нужен там, где разбор страницы приходится часто править: правка здесь
 * не требует пересборки APK, а телефон получает готовый разобранный ответ.
 *
 * Ручек две, и обе живут в одной функции — так у них общий адрес, и ответ поиска
 * может честно сослаться на ручку книги:
 *
 *   GET  <base>/search?q=запрос      → список книг
 *   GET  <base>/book?src=…&u=…       → одна книга со списком глав
 */
const { onRequest } = require("firebase-functions/v2/https");
const { searchAll, loadBook } = require("./lib/aggregator");

const options = {
  // Ближе к пользователю — меньше задержка. Регион меняется одной строкой.
  region: "europe-west1",
  timeoutSeconds: 60,
  memory: "256MiB",
  // Ограничение на всякий случай: чужой бот не должен разорить на счёте.
  maxInstances: 5,
  cors: true,
};

exports.api = onRequest(options, async (req, res) => {
  const path = (req.path || "/").replace(/\/+$/, "") || "/";

  try {
    if (path === "/book") {
      const src = String(req.query.src || "");
      const url = String(req.query.u || "");
      if (!src || !url) return bad(res, "нужны параметры src и u");
      const book = await loadBook(src, url, base(req));
      if (!book) return bad(res, "книга не найдена", 404);
      return ok(res, { version: 1, book });
    }

    // Всё остальное считаем поиском: так адрес источника в приложении можно
    // записать и как <base>/search?q={q}, и просто как <base>?q={q}.
    const query = String(req.query.q || "").trim();
    if (!query) return ok(res, { version: 1, query: "", results: [], sources: [] });

    const found = await searchAll(query, base(req));
    return ok(res, { version: 1, query, ...found });
  } catch (error) {
    console.error("Запрос упал", error);
    return bad(res, error.message || "внутренняя ошибка", 500);
  }
});

/**
 * Адрес самого сервера — из него собираются ссылки на книги.
 *
 * У функций второго поколения свой домен run.app, а вызывать их можно и через
 * cloudfunctions.net с префиксом имени функции. Поэтому префикс берём из самого
 * запроса, а не собираем по частям. PUBLIC_BASE_URL перекрывает всё — он нужен,
 * когда перед функцией стоит свой домен.
 */
function base(req) {
  const configured = process.env.PUBLIC_BASE_URL;
  if (configured) return configured.replace(/\/+$/, "");

  const host = req.headers["x-forwarded-host"] || req.headers.host;
  const proto = req.headers["x-forwarded-proto"] || "https";
  const full = (req.originalUrl || req.url || "/").split("?")[0];
  const prefix = full.replace(/\/[^/]*$/, "");
  return `${proto}://${host}${prefix}`;
}

function ok(res, body) {
  // Кеш на стороне клиента и Google Frontend: одинаковые запросы стоят денег.
  res.set("Cache-Control", "public, max-age=300");
  return res.status(200).json(body);
}

function bad(res, message, code = 400) {
  return res.status(code).json({ version: 1, error: message, results: [] });
}
