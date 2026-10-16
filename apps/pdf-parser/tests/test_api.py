from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from spendly_pdf_parser.api import Runtime, create_app
from spendly_pdf_parser.settings import Settings

from .conftest import ACCOUNT_ID, STATEMENT_ID
from .helpers import assert_no_pii, recompute_tx_hashes


@pytest.fixture
def client(tmp_path: Path, contracts, sanitizer):
    settings = Settings(_env_file=None, SPENDLY_UPLOAD_DIR=str(tmp_path), PDF_PARSER_KAFKA_ENABLED="false")
    runtime = Runtime(settings=settings, contracts=contracts, sanitizer=sanitizer)
    with TestClient(create_app(runtime)) as c:
        yield c


def test_health(client) -> None:
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "UP"
    assert body["contractsLoaded"] is True
    assert body["kafka"]["state"] == "DISABLED"


def test_parse_sample(client, sample_pdf_bytes, contracts, tmp_path: Path) -> None:
    r = client.post(
        "/parse",
        files={"file": ("statement.pdf", sample_pdf_bytes, "application/pdf")},
        data={"accountId": ACCOUNT_ID, "statementId": STATEMENT_ID, "correlationId": "dbg-1"},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["outcome"] == "TransactionsImported" and body["published"] is False
    v = body["validation"]
    assert v["balanced"] is True and v["schemaValid"] is True
    assert (v["transactionCount"], v["debitCount"], v["creditCount"]) == (5, 4, 1)
    assert (v["debitTotal"], v["creditTotal"]) == ("951.00", "800.00")
    event = body["event"]
    assert contracts.validate(event) == []
    assert event["payload"]["accountId"] == ACCOUNT_ID and event["correlationId"] == "dbg-1"
    assert [t["txHash"] for t in event["payload"]["transactions"]] == recompute_tx_hashes(event["payload"])
    text = r.text
    assert_no_pii(json.dumps(body, ensure_ascii=False))
    for word in ("opening", "closing", "sold"):
        assert word not in text.lower()
    assert list(tmp_path.iterdir()) == [], "/parse must not store the file"


def test_parse_defaults_and_failure(client, contracts) -> None:
    r = client.post("/parse", files={"file": ("x.pdf", b"garbage", "application/pdf")})
    assert r.status_code == 200
    body = r.json()
    assert body["outcome"] == "StatementParseFailed"
    assert body["event"]["payload"]["reason"] == "FILE_UNREADABLE"
    assert contracts.validate(body["event"]) == []


def test_parse_rejects_bad_input(client, sample_pdf_bytes) -> None:
    assert client.post("/parse", data={"accountId": ACCOUNT_ID}, files={"x": ("a", b"b")}).status_code == 400
    r = client.post("/parse", files={"file": ("s.pdf", sample_pdf_bytes)}, data={"accountId": "not-a-uuid"})
    assert r.status_code == 422
    assert client.post("/parse", content=b"{}", headers={"content-type": "application/json"}).status_code == 415
