"""Authentication helpers for the UNO Q local HTTP API."""
from __future__ import annotations

import hmac
import os

MIN_TOKEN_BYTES = 32


def valid_token(token: str | None) -> bool:
    return bool(token) and token == token.strip() and not any(character.isspace() for character in token) and len(token.encode("utf-8")) >= MIN_TOKEN_BYTES


def configured_token() -> str | None:
    token = os.environ.get("TZINDA_API_TOKEN")
    return token if valid_token(token) else None


def authorized(header: str | None, expected: str | None) -> bool:
    if not valid_token(expected) or not header:
        return False
    scheme, separator, supplied = header.partition(" ")
    return bool(separator and scheme.lower() == "bearer" and supplied) and hmac.compare_digest(
        supplied.encode("utf-8"), expected.encode("utf-8")
    )
