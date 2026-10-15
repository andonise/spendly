"""Safe resolution of ``tempFilePath`` (relative to SPENDLY_UPLOAD_DIR)."""

from __future__ import annotations

import re
from pathlib import Path, PurePosixPath

_ALLOWED = re.compile(r"^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")


class UnsafePathError(ValueError):
    """tempFilePath is absolute, malformed, or escapes the upload folder. The message never echoes the path."""


def resolve_upload_path(upload_dir: Path, temp_file_path: str) -> Path:
    if not temp_file_path or not _ALLOWED.match(temp_file_path):
        raise UnsafePathError("tempFilePath has an invalid format")
    rel = PurePosixPath(temp_file_path)
    if rel.is_absolute() or any(part in ("..", ".") for part in temp_file_path.split("/")):
        raise UnsafePathError("tempFilePath must be a plain relative path")
    base = upload_dir.resolve()
    candidate = (base / Path(*rel.parts)).resolve()  # also resolves symlinks
    if candidate == base or not candidate.is_relative_to(base):
        raise UnsafePathError("tempFilePath escapes the upload folder")
    return candidate
