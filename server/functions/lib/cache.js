/**
 * Память между запросами.
 *
 * Экземпляр функции живёт какое-то время после ответа и обслуживает следующие
 * запросы — этим и пользуемся. Кеш не переживает холодный старт, и это нормально:
 * его задача — не ходить на сайт по десять раз за минуту, а не хранить вечно.
 */
const store = new Map();

const MAX_ENTRIES = 300;

function get(key) {
  const entry = store.get(key);
  if (!entry) return null;
  if (Date.now() > entry.until) {
    store.delete(key);
    return null;
  }
  return entry.value;
}

function put(key, value, ttlMs) {
  if (store.size >= MAX_ENTRIES) {
    // Простое вытеснение: карта хранит порядок вставки, убираем самое старое.
    const oldest = store.keys().next().value;
    store.delete(oldest);
  }
  store.set(key, { value, until: Date.now() + ttlMs });
}

/** Обёртка «посчитай, если ещё не считали». */
async function remember(key, ttlMs, produce) {
  const hit = get(key);
  if (hit !== null) return hit;
  const value = await produce();
  put(key, value, ttlMs);
  return value;
}

module.exports = { get, put, remember };
