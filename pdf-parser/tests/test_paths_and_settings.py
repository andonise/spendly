from __future__ import annotations

from datetime import timedelta
from pathlib import Path

import pytest

from spendly_pdf_parser.contracts import load_contracts
from spendly_pdf_parser.paths import UnsafePathError, resolve_upload_path
from spendly_pdf_parser.settings import ConfigurationError, Settings, load_hmac_key, parse_iso_duration


@pytest.mark.parametrize(
    "bad",
    ["../secret.pdf", "a/../../secret.pdf", "/etc/passwd", "..", ".", "a/./b.pdf", "C:/x.pdf", "a\\..\\b.pdf",
     "", "a//b.pdf", "sub/..", "%2e%2e/x.pdf"],
)
def test_path_traversal_rejected(tmp_path: Path, bad: str) -> None:
    with pytest.raises(UnsafePathError):
        resolve_upload_path(tmp_path, bad)


def test_valid_paths_resolve_inside(tmp_path: Path) -> None:
    p = resolve_upload_path(tmp_path, "9b2e4c71-5a3f-4d88-b1e0-6f7a2c9d3e15.pdf")
    assert p.parent == tmp_path.resolve()
    assert resolve_upload_path(tmp_path, "sub/x.pdf").is_relative_to(tmp_path.resolve())


def test_symlink_escape_rejected(tmp_path: Path) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    uploads = tmp_path / "uploads"
    uploads.mkdir()
    try:
        (uploads / "link").symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip("symlinks not permitted on this machine")
    with pytest.raises(UnsafePathError):
        resolve_upload_path(uploads, "link/x.pdf")


@pytest.mark.parametrize(("value", "expected"), [("PT0S", 0), ("PT10M", 600), ("P1D", 86400), ("PT1H30M", 5400)])
def test_iso_duration(value: str, expected: int) -> None:
    assert parse_iso_duration(value) == timedelta(seconds=expected)


@pytest.mark.parametrize("bad", ["", "P", "PT", "10s", "-PT1S"])
def test_iso_duration_rejects(bad: str) -> None:
    with pytest.raises(ValueError):
        parse_iso_duration(bad)


def test_hmac_key_fail_fast_never_reveals_value(tmp_path: Path) -> None:
    with pytest.raises(ConfigurationError):
        load_hmac_key(None)
    with pytest.raises(ConfigurationError):
        load_hmac_key(tmp_path / "missing.key")
    empty = tmp_path / "empty.key"
    empty.write_text("  \n", encoding="utf-8")
    with pytest.raises(ConfigurationError):
        load_hmac_key(empty)
    key = tmp_path / "hmac.key"
    key.write_text("  super-secret-value-0123456789-0123456789\n", encoding="utf-8")
    assert load_hmac_key(key) == b"super-secret-value-0123456789-0123456789"


def test_contracts_fail_fast(tmp_path: Path) -> None:
    with pytest.raises(ConfigurationError, match="SPENDLY_CONTRACTS_DIR"):
        load_contracts(tmp_path)


def test_settings_defaults_and_empty_contracts_dir(tmp_path: Path) -> None:
    s = Settings(_env_file=None, SPENDLY_CONTRACTS_DIR="", SPENDLY_UPLOAD_DIR=str(tmp_path))
    assert s.spendly_contracts_dir is None
    assert s.contracts_dir.name == "contracts"
    assert s.upload_retention == timedelta(0)
    assert s.pdf_parser_port == 8090
