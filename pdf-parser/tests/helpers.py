from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any

# Strings from the sample header / raw rows that must never appear in any output, log or event.
FORBIDDEN = (
    "MARIA EXEMPLU",
    "Exemplului",
    "4000 0566 5566 5556",
    "4000056655665556",
    "RO45 SPND 0000 7777 8888 9999",
    "RO45SPND000077778888 9999".replace(" ", ""),
    "RO54SPND0000111122223333",
    "RO54 SPND 0000 1111 2222 3333",
    # balances (AC-SD-6), in both source and canonical formats
    "1.200,00",
    "1.351,00",
    "1200.00",
    "1351.00",
)


def assert_no_pii(text: str) -> None:
    for needle in FORBIDDEN:
        assert needle not in text, f"forbidden value leaked: {needle[:6]}..."


def recompute_tx_hashes(payload: dict[str, Any]) -> list[str]:
    seen: dict[tuple, int] = {}
    out = []
    for tx in payload["transactions"]:
        key = (tx["bookingDate"], tx["amount"], tx["currency"], tx["description"])
        occ = seen.get(key, 0)
        seen[key] = occ + 1
        parts = [payload["accountId"], tx["bookingDate"], tx["amount"], tx["currency"], tx["description"], str(occ)]
        out.append(hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest())
    return out


def statement_uploaded(statement_id: str, account_id: str, temp_file_path: str, *, file_type: str = "PDF",
                       file_sha256: str = "0" * 64, correlation_id: str = "test-corr-1") -> dict[str, Any]:
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "StatementUploaded",
        "schemaVersion": 1,
        "occurredAt": "2026-09-01T08:15:02.481Z",
        "correlationId": correlation_id,
        "producer": "spendly-backend",
        "payload": {
            "statementId": statement_id,
            "accountId": account_id,
            "bank": "Generic Bank",
            "accountCurrency": "RON",
            "fileType": file_type,
            "tempFilePath": temp_file_path,
            "fileSha256": file_sha256,
        },
    }


def to_bytes(event: dict[str, Any]) -> bytes:
    return json.dumps(event).encode("utf-8")


def minimal_pdf(text: str = "Hello world") -> bytes:
    """A tiny valid one-page text PDF with no tables (built by hand, with a correct xref)."""
    stream = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)
