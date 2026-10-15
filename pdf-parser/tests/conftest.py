from __future__ import annotations

import os
from pathlib import Path

import pytest

from spendly_pdf_parser.contracts import Contracts, load_contracts
from spendly_pdf_parser.sanitizer import Sanitizer

PROJECT_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = PROJECT_DIR.parent
CONTRACTS_DIR = Path(os.environ.get("SPENDLY_CONTRACTS_DIR") or REPO_DIR / "contracts")
SAMPLE_PDF = REPO_DIR / "samples" / "credit-card-2026-08.pdf"

TEST_HMAC_KEY = b"pytest-only-hmac-key-0123456789-abcdefghij"
ACCOUNT_ID = "3f1c2a9e-7b4d-4e21-9a6f-0c5d8e2b1a47"
STATEMENT_ID = "9b2e4c71-5a3f-4d88-b1e0-6f7a2c9d3e15"


@pytest.fixture(scope="session")
def contracts() -> Contracts:
    return load_contracts(CONTRACTS_DIR)


@pytest.fixture(scope="session")
def sanitizer(contracts: Contracts) -> Sanitizer:
    return Sanitizer(contracts.pii_rules, TEST_HMAC_KEY)


@pytest.fixture(scope="session")
def sample_pdf_bytes() -> bytes:
    return SAMPLE_PDF.read_bytes()
