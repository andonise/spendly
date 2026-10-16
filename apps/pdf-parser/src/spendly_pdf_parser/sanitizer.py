"""Shared PII sanitizer: implements the algorithm in contracts/README.md ("Sanitizer algorithm") exactly,
driven by contracts/pii-rules.json. Must produce output identical to the Kotlin sanitizer (AC-SD-4).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_IBAN_SHAPE = re.compile(r"^[A-Z]{2}[0-9]{2}[A-Z0-9]+$")
_SPACES = re.compile(r" {2,}")
_CNP_WEIGHTS = (2, 7, 9, 1, 4, 6, 3, 5, 8, 2, 7, 9)


@dataclass(frozen=True)
class Token:
    rule_id: str
    token: str

    def as_dict(self) -> dict[str, str]:
        return {"ruleId": self.rule_id, "token": self.token}


@dataclass(frozen=True)
class SanitizedText:
    text: str
    tokens: tuple[Token, ...] = field(default_factory=tuple)

    def first_token(self, rule_id: str) -> str | None:
        return next((t.token for t in self.tokens if t.rule_id == rule_id), None)


# --------------------------------------------------------------------------- validators


def iban_mod97(candidate: str) -> bool:
    if not (15 <= len(candidate) <= 34) or not _IBAN_SHAPE.match(candidate):
        return False
    rearranged = candidate[4:] + candidate[:4]
    digits = "".join(str(int(ch, 36)) for ch in rearranged)  # 0-9 -> 0-9, A-Z -> 10..35
    return int(digits) % 97 == 1


def luhn(digits: str) -> bool:
    if not (13 <= len(digits) <= 19) or not digits.isascii() or not digits.isdigit():
        return False
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = ord(ch) - 48
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def cnp_checksum(cnp: str) -> bool:
    if len(cnp) != 13 or not cnp.isascii() or not cnp.isdigit():
        return False
    r = sum(int(cnp[i]) * w for i, w in enumerate(_CNP_WEIGHTS)) % 11
    control = 1 if r == 10 else r
    return control == int(cnp[12])


# --------------------------------------------------------------------------- sanitizer


class Sanitizer:
    """Stateless after construction; safe to share across threads."""

    def __init__(self, rules: dict[str, Any], hmac_key: bytes) -> None:
        if not hmac_key:
            raise ValueError("HMAC key must not be empty")
        self._key = hmac_key
        norm = rules["normalization"]
        self._unicode_form = norm.get("unicodeForm", "NFC")
        self._ws_table = {ord(ch): " " for ch in norm["whitespaceToSpace"]}
        self._rules: list[tuple[dict[str, Any], re.Pattern[str]]] = [
            (rule, re.compile(rule["pattern"])) for rule in rules["rules"]
        ]
        self._handlers: dict[str, Callable[[dict[str, Any], re.Match[str], list[Token]], str]] = {
            "iban": self._iban,
            "cnp": self._cnp,
            "pan": self._pan,
            "person_name": self._person_name,
        }
        for rule, _ in self._rules:
            if rule["id"] not in self._handlers and rule["action"] not in ("mask", "remove"):
                raise ValueError(f"Unsupported sanitizer rule: {rule['id']}")

    @classmethod
    def from_file(cls, rules_path: Path, hmac_key: bytes) -> Sanitizer:
        return cls(json.loads(rules_path.read_text(encoding="utf-8")), hmac_key)

    def hmac_hex(self, message: str) -> str:
        return hmac.new(self._key, message.encode("utf-8"), hashlib.sha256).hexdigest()

    def sanitize(self, text: str | None) -> SanitizedText:
        # 1. normalize
        current = unicodedata.normalize(self._unicode_form, text or "").translate(self._ws_table)
        current = _SPACES.sub(" ", current).strip()
        tokens: list[Token] = []
        # 2. rules in file order
        for rule, pattern in self._rules:
            handler = self._handlers.get(rule["id"])
            if handler is None:
                replacement = rule.get("replacement", "")
                current = pattern.sub(lambda _m, r=replacement: r, current)
            else:
                current = pattern.sub(lambda m, r=rule, h=handler: h(r, m, tokens), current)
        # 3. clean up
        current = _SPACES.sub(" ", current).strip()
        return SanitizedText(current, tuple(tokens))

    # ---------------------------------------------------------------- rule handlers

    @staticmethod
    def _shrink(match_text: str, valid: Callable[[str], bool]) -> tuple[int, str] | None:
        chunks = match_text.split(" ")
        for n in range(len(chunks), 0, -1):
            candidate = "".join(chunks[:n])
            if valid(candidate):
                return n, candidate
        return None

    @staticmethod
    def _rest(match_text: str, n: int) -> str:
        chunks = match_text.split(" ")
        return match_text[len(" ".join(chunks[:n])) :]

    def _iban(self, rule: dict[str, Any], m: re.Match[str], tokens: list[Token]) -> str:
        found = self._shrink(m.group(0), iban_mod97)
        if found is None:
            return m.group(0)
        n, compact = found
        tokens.append(Token(rule["id"], self.hmac_hex(f"{rule['tokenNamespace']}:{compact}")))
        return f"{compact[:2]}**…{compact[-4:]}" + self._rest(m.group(0), n)

    def _cnp(self, rule: dict[str, Any], m: re.Match[str], tokens: list[Token]) -> str:
        return rule.get("replacement", "") if cnp_checksum(m.group(0)) else m.group(0)

    def _pan(self, rule: dict[str, Any], m: re.Match[str], tokens: list[Token]) -> str:
        found = self._shrink(m.group(0), luhn)
        if found is None:
            return m.group(0)
        n, digits = found
        return f"****{digits[-4:]}" + self._rest(m.group(0), n)

    @staticmethod
    def _norm_word(word: str) -> str:
        decomposed = unicodedata.normalize("NFKD", word)
        stripped = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
        return stripped.upper().replace(".", "")

    def _person_name(self, rule: dict[str, Any], m: re.Match[str], tokens: list[Token]) -> str:
        keyword = m.group(rule.get("keywordGroup", 1))
        words = [w for w in m.group(rule.get("nameGroup", 2)).split(" ") if w]
        norms = [self._norm_word(w) for w in words]
        stop_words = set(rule["stopWords"])
        company_words = set(rule["companyWords"])
        cut = next((i for i, n in enumerate(norms) if n in stop_words), len(words))
        kept, tail = norms[:cut], words[cut:]
        if len(kept) < rule.get("minWords", 2) or any(n in company_words for n in kept):
            return m.group(0)
        token = self.hmac_hex(f"{rule['tokenNamespace']}:{' '.join(kept)}")
        tokens.append(Token(rule["id"], token))
        result = f"{keyword} [PERSON#{token[:8]}]"
        if tail:
            result += " " + " ".join(tail)
        return result
