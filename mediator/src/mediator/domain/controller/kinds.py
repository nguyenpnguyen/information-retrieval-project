from __future__ import annotations

from pathlib import PurePosixPath
from urllib.parse import urlsplit

from mediator.domain.controller.models import UrlKind

DOCUMENT_SUFFIXES = frozenset(
    {
        ".pdf",
        ".doc",
        ".docx",
        ".xls",
        ".xlsx",
        ".ppt",
        ".pptx",
        ".odt",
        ".ods",
        ".odp",
        ".rtf",
        ".txt",
        ".csv",
        ".zip",
        ".rar",
        ".7z",
        ".jpg",
        ".jpeg",
        ".png",
        ".gif",
        ".webp",
        ".svg",
        ".bmp",
    }
)  # fmt: skip  -- files, images included: sent before pages, recrawled like documents


def url_kind(url: str) -> UrlKind:
    """``document`` by file suffix (images too), ``hub`` for a site's front page, else ``page``."""
    path = urlsplit(url).path
    if PurePosixPath(path).suffix.lower() in DOCUMENT_SUFFIXES:
        return "document"
    return "hub" if path in {"", "/"} else "page"
