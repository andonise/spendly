"""Loads the shared contracts (JSON Schemas + pii-rules.json) from SPENDLY_CONTRACTS_DIR.

All schemas are registered locally by ``$id``; nothing is ever fetched from ``spendly.local``.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.exceptions import NoSuchResource

from .settings import ConfigurationError

SCHEMA_BASE = "https://spendly.local/contracts/schemas/"
SCHEMA_BY_EVENT = {
    "StatementUploaded": "statement-uploaded.schema.json",
    "TransactionsImported": "transactions-imported.schema.json",
    "StatementParseFailed": "statement-parse-failed.schema.json",
}


def _no_remote(uri: str) -> Resource:
    raise NoSuchResource(ref=uri)


@dataclass(frozen=True)
class Contracts:
    directory: Path
    pii_rules: dict[str, Any]
    validators: dict[str, Draft202012Validator]

    def validate(self, event: dict[str, Any]) -> list[str]:
        """Validate an envelope. Returns PII-free error summaries (JSON paths + validator keyword only)."""
        event_type = event.get("eventType") if isinstance(event, dict) else None
        validator = self.validators.get(event_type) if isinstance(event_type, str) else None
        if validator is None:
            return ["eventType: unknown or missing"]
        return [
            f"{'/'.join(map(str, err.absolute_path)) or '<root>'}: {err.validator}"
            for err in validator.iter_errors(event)
        ]

    def validate_as(self, event_type: str, event: Any) -> list[str]:
        """Validate against one specific event schema, whatever ``eventType`` the message claims."""
        return [
            f"{'/'.join(map(str, err.absolute_path)) or '<root>'}: {err.validator}"
            for err in self.validators[event_type].iter_errors(event)
        ]

    def is_valid(self, event: dict[str, Any]) -> bool:
        return not self.validate(event)


def load_contracts(directory: Path) -> Contracts:
    schemas_dir = directory / "schemas"
    rules_path = directory / "pii-rules.json"
    if not schemas_dir.is_dir() or not rules_path.is_file():
        raise ConfigurationError(
            "Contracts not found: SPENDLY_CONTRACTS_DIR must point to the contracts/ folder "
            "(containing schemas/ and pii-rules.json)"
        )
    resources = []
    for path in sorted(schemas_dir.glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        resources.append((schema["$id"], Resource.from_contents(schema)))
    registry = Registry(retrieve=_no_remote).with_resources(resources)
    validators = {}
    for event_type, file_name in SCHEMA_BY_EVENT.items():
        try:
            schema = registry.contents(SCHEMA_BASE + file_name)
        except Exception:  # noqa: BLE001 - normalize to a configuration error
            raise ConfigurationError(f"Contracts incomplete: schema {file_name} is missing") from None
        validators[event_type] = Draft202012Validator(
            schema, registry=registry, format_checker=Draft202012Validator.FORMAT_CHECKER
        )
    pii_rules = json.loads(rules_path.read_text(encoding="utf-8"))
    return Contracts(directory=directory, pii_rules=pii_rules, validators=validators)
