/**
 * Сборка списка источников из настроек.
 *
 * Конкретных сайтов в коде нет намеренно: всё, что знает сервер о каталогах,
 * лежит в sources.json и правится без программирования.
 */
const sources = require("../sources.json");
const archive = require("./archive");
const librivox = require("./librivox");
const feed = require("./feed");
const site = require("./site");

function build() {
  const adapters = [];
  const problems = [];

  if (sources.builtin?.archive !== false) adapters.push(archive.make(sources.builtin || {}));
  if (sources.builtin?.librivox !== false) adapters.push(librivox.make(sources.builtin || {}));

  for (const config of sources.feeds || []) {
    if (config.enabled === false) continue;
    if (!config.url) {
      problems.push(`лента ${config.id || "без имени"}: нет поля url`);
      continue;
    }
    adapters.push(feed.make(config));
  }

  for (const config of sources.sites || []) {
    if (config.enabled === false) continue;
    const problem = site.validate(config);
    if (problem) {
      problems.push(`сайт ${config.id || "без имени"}: ${problem}`);
      continue;
    }
    adapters.push(site.make(config));
  }

  return { adapters, problems };
}

module.exports = { build };
