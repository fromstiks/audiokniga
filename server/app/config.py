from __future__ import annotations

import os
from dataclasses import dataclass, field

import yaml


@dataclass
class SiteConfig:
    """Настройки одного сайта-источника. Никакой логики парсинга — только "где что искать"."""

    key: str
    name: str
    base_url: str
    search_url: str
    engine: str = "static"  # "static" (requests+BeautifulSoup) или "dynamic" (Playwright)
    enabled: bool = True
    detail_url_attr: str = "href"
    request_headers: dict[str, str] = field(default_factory=dict)
    selectors: dict[str, str] = field(default_factory=dict)
    detail_selectors: dict[str, str] = field(default_factory=dict)
    rate_limit_sec: float = 1.0
    timeout_sec: float = 20.0

    @staticmethod
    def from_dict(raw: dict) -> "SiteConfig":
        return SiteConfig(
            key=raw["key"],
            name=raw.get("name", raw["key"]),
            base_url=raw["base_url"],
            search_url=raw["search_url"],
            engine=raw.get("engine", "static"),
            enabled=raw.get("enabled", True),
            request_headers=raw.get("request_headers", {}) or {},
            selectors=raw.get("selectors", {}) or {},
            detail_selectors=raw.get("detail_selectors", {}) or {},
            rate_limit_sec=float(raw.get("rate_limit_sec", 1.0)),
            timeout_sec=float(raw.get("timeout_sec", 20.0)),
        )


def load_sites(config_path: str | None = None) -> list[SiteConfig]:
    path = config_path or os.environ.get("SITES_CONFIG", "sites.yaml")
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh) or {}
    sites = [SiteConfig.from_dict(item) for item in raw.get("sites", [])]
    return [site for site in sites if site.enabled]
