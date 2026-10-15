"""AC-SD-4 (shared fixture, identical output to the Kotlin sanitizer) and AC-SD-5 (stable, keyed tokens)."""

from __future__ import annotations

import json

import pytest

from spendly_pdf_parser.sanitizer import Sanitizer, cnp_checksum, iban_mod97, luhn

from .conftest import CONTRACTS_DIR

FIXTURES = json.loads((CONTRACTS_DIR / "fixtures" / "sanitizer-cases.json").read_text(encoding="utf-8"))
RULES = json.loads((CONTRACTS_DIR / "pii-rules.json").read_text(encoding="utf-8"))


def test_fixture_has_29_cases() -> None:
    assert len(FIXTURES["cases"]) == 29


@pytest.fixture(scope="module")
def fixture_sanitizer() -> Sanitizer:
    return Sanitizer(RULES, FIXTURES["hmacSecret"].strip().encode("utf-8"))


@pytest.mark.parametrize("case", FIXTURES["cases"], ids=[c["id"] for c in FIXTURES["cases"]])
def test_shared_fixture(case: dict, fixture_sanitizer: Sanitizer) -> None:
    result = fixture_sanitizer.sanitize(case["input"])
    assert result.text == case["expected"]["text"]
    assert [t.as_dict() for t in result.tokens] == case["expected"]["tokens"]


def test_tokens_are_stable_and_keyed() -> None:
    text = "TRANSFER CATRE ION POPESCU RO38TEST7777888899990000"
    a = Sanitizer(RULES, b"key-one-0123456789-0123456789-0123")
    a2 = Sanitizer(RULES, b"key-one-0123456789-0123456789-0123")
    b = Sanitizer(RULES, b"key-two-0123456789-0123456789-0123")
    first, again, other = a.sanitize(text), a2.sanitize(text), b.sanitize(text)
    assert first == again
    assert len(first.tokens) == 2
    for t1, t2 in zip(first.tokens, other.tokens, strict=True):
        assert t1.rule_id == t2.rule_id
        assert t1.token != t2.token
    # compact and spaced IBAN map to the same token
    spaced = a.sanitize("IBAN RO38 TEST 7777 8888 9999 0000")
    assert spaced.first_token("iban") == first.first_token("iban")


def test_validators() -> None:
    assert iban_mod97("RO49AAAA1B31007593840000")
    assert not iban_mod97("RO00AAAA1B31007593840000")
    assert luhn("4111111111111111")
    assert not luhn("1234567812345678")
    assert cnp_checksum("1960523460032")
    assert not cnp_checksum("1960523460031")
