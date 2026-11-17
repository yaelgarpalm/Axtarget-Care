"""Fail-closed TLS setup for the local UNO Q API."""
from __future__ import annotations

import os
import ssl


def create_server_context(cert_file: str | None = None, key_file: str | None = None) -> ssl.SSLContext:
    certificate = cert_file or os.environ.get("TZINDA_TLS_CERT_FILE")
    private_key = key_file or os.environ.get("TZINDA_TLS_KEY_FILE")
    if not certificate and not private_key:
        raise RuntimeError("HTTPS is required: configure TZINDA_TLS_CERT_FILE and TZINDA_TLS_KEY_FILE.")
    if not certificate or not private_key:
        raise RuntimeError("Both TZINDA_TLS_CERT_FILE and TZINDA_TLS_KEY_FILE are required; HTTP fallback is disabled.")

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile=certificate, keyfile=private_key)
    return context
