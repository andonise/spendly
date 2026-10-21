"""Validate contracts/examples/*.json against contracts/schemas and the txHash rule.

Usage (from the repository root):
    uv run --with jsonschema --with referencing python contracts/tools/validate_examples.py
"""

import hashlib
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

CONTRACTS = Path(__file__).resolve().parent.parent
SCHEMAS = CONTRACTS / "schemas"
EXAMPLES = CONTRACTS / "examples"

SCHEMA_BY_EVENT = {
    "StatementUploaded": "statement-uploaded.schema.json",
    "TransactionsImported": "transactions-imported.schema.json",
    "StatementParseFailed": "statement-parse-failed.schema.json",
    "StatementParsed": "statement-parsed.schema.json",
    "InputRequired": "input-required.schema.json",
    "AnswersSubmitted": "answers-submitted.schema.json",
    "TransactionsChanged": "transactions-changed.schema.json",
    "ReportRequested": "report-requested.schema.json",
}


def load_registry() -> Registry:
    resources = []
    for path in sorted(SCHEMAS.glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        resources.append((schema["$id"], Resource.from_contents(schema)))
    return Registry().with_resources(resources)


def tx_hash(account_id: str, tx: dict, occurrence: int) -> str:
    parts = [account_id, tx["bookingDate"], tx["amount"], tx["currency"], tx["description"], str(occurrence)]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()


def check_transactions(event: dict) -> list[str]:
    payload = event["payload"]
    errors = []
    if payload["transactionCount"] != len(payload["transactions"]):
        errors.append("transactionCount != len(transactions)")
    seen: dict[tuple, int] = {}
    for tx in payload["transactions"]:
        key = (tx["bookingDate"], tx["amount"], tx["currency"], tx["description"])
        occurrence = seen.get(key, 0)
        seen[key] = occurrence + 1
        expected = tx_hash(payload["accountId"], tx, occurrence)
        if tx["txHash"] != expected:
            errors.append(f"sequence {tx['sequence']}: txHash {tx['txHash']} != {expected}")
    return errors


def main() -> int:
    registry = load_registry()
    failures = 0
    for path in sorted(EXAMPLES.glob("*.json")):
        event = json.loads(path.read_text(encoding="utf-8"))
        schema_id = "https://spendly.local/contracts/schemas/" + SCHEMA_BY_EVENT[event["eventType"]]
        validator = Draft202012Validator(
            registry.contents(schema_id), registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
        )
        errors = [f"{'/'.join(map(str, e.absolute_path))}: {e.message}" for e in validator.iter_errors(event)]
        if event["eventType"] == "TransactionsImported" and not errors:
            errors += check_transactions(event)
        status = "OK  " if not errors else "FAIL"
        print(f"{status} {path.name}")
        for err in errors:
            print(f"     {err}")
        failures += bool(errors)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
