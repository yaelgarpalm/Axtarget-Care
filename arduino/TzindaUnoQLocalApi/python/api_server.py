"""Bounded TLS/HTTP server lifecycle for the local UNO Q API."""
from __future__ import annotations

import socket
import ssl
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from typing import Any


MAX_CONCURRENT_CLIENTS = 4
REQUEST_QUEUE_SIZE = 8
TLS_HANDSHAKE_TIMEOUT_SECONDS = 5.0
SOCKET_IDLE_TIMEOUT_SECONDS = 5.0
REQUEST_DEADLINE_SECONDS = 15.0


class DeadlineReader:
    """Socket-backed request reader with both idle and absolute read deadlines."""

    def __init__(self, connection: Any, deadline: float, idle_timeout_seconds: float) -> None:
        self.connection = connection
        self.deadline = deadline
        self.idle_timeout_seconds = idle_timeout_seconds
        self.buffer = bytearray()
        self.eof = False
        self.closed = False

    def _receive(self) -> bool:
        if self.eof:
            return False
        if self.closed:
            raise ValueError("I/O operation on closed request reader")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("request deadline exceeded")
        self.connection.settimeout(min(self.idle_timeout_seconds, remaining))
        try:
            chunk = self.connection.recv(4096)
        except socket.timeout as error:
            raise TimeoutError("request idle or total deadline exceeded") from error
        if not chunk:
            self.eof = True
            return False
        self.buffer.extend(chunk)
        return True

    def _consume(self, count: int) -> bytes:
        result = bytes(self.buffer[:count])
        del self.buffer[:count]
        return result

    def readline(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        while True:
            newline = self.buffer.find(b"\n")
            if newline >= 0:
                count = newline + 1
                if size >= 0:
                    count = min(count, size)
                return self._consume(count)
            if size >= 0 and len(self.buffer) >= size:
                return self._consume(size)
            if not self._receive():
                return self._consume(len(self.buffer))

    def read(self, size: int = -1) -> bytes:
        if size == 0:
            return b""
        if size < 0:
            while self._receive():
                pass
            return self._consume(len(self.buffer))
        while len(self.buffer) < size and self._receive():
            pass
        return self._consume(min(size, len(self.buffer)))

    def close(self) -> None:
        self.closed = True


class DeadlineRequestHandler:
    """Mixin that applies one absolute deadline to headers and any request body."""

    def setup(self) -> None:
        super().setup()
        self.rfile.close()
        self.rfile = DeadlineReader(
            self.connection,
            time.monotonic() + self.server.request_deadline_seconds,
            self.server.socket_idle_timeout_seconds,
        )


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """Limit workers and expire stalled TLS or HTTP clients."""

    daemon_threads = True
    block_on_close = False
    request_queue_size = REQUEST_QUEUE_SIZE

    def __init__(
        self,
        server_address: tuple[str, int],
        request_handler: type,
        *,
        ssl_context: ssl.SSLContext | None = None,
        max_clients: int = MAX_CONCURRENT_CLIENTS,
        tls_handshake_timeout_seconds: float = TLS_HANDSHAKE_TIMEOUT_SECONDS,
        socket_idle_timeout_seconds: float = SOCKET_IDLE_TIMEOUT_SECONDS,
        request_deadline_seconds: float = REQUEST_DEADLINE_SECONDS,
    ) -> None:
        if max_clients < 1:
            raise ValueError("max_clients must be positive")
        if min(tls_handshake_timeout_seconds, socket_idle_timeout_seconds, request_deadline_seconds) <= 0:
            raise ValueError("connection timeouts must be positive")
        self.ssl_context = ssl_context
        self.max_clients = max_clients
        self.tls_handshake_timeout_seconds = tls_handshake_timeout_seconds
        self.socket_idle_timeout_seconds = socket_idle_timeout_seconds
        self.request_deadline_seconds = request_deadline_seconds
        self._client_slots = threading.BoundedSemaphore(max_clients)
        super().__init__(server_address, request_handler)

    def get_request(self) -> tuple[Any, Any]:
        request, client_address = super().get_request()
        if self.ssl_context is None:
            return request, client_address
        request.settimeout(self.tls_handshake_timeout_seconds)
        try:
            # Defer the handshake until a bounded worker owns this socket.
            request = self.ssl_context.wrap_socket(
                request, server_side=True, do_handshake_on_connect=False
            )
        except Exception:
            request.close()
            raise
        return request, client_address

    def process_request(self, request: Any, client_address: Any) -> None:
        if not self._client_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._client_slots.release()
            self.shutdown_request(request)
            raise

    def process_request_thread(self, request: Any, client_address: Any) -> None:
        handed_to_parent = False
        try:
            request.settimeout(self.tls_handshake_timeout_seconds)
            if self.ssl_context is not None:
                request.do_handshake()
            else:
                request.settimeout(self.socket_idle_timeout_seconds)
            handed_to_parent = True
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError, TimeoutError):
            # Stalled or malformed peers are expected network input, not server faults.
            if not handed_to_parent:
                self.shutdown_request(request)
        finally:
            self._client_slots.release()

    def handle_error(self, request: Any, client_address: Any) -> None:
        # Clients routinely disconnect when a bounded request expires.
        if isinstance(sys.exc_info()[1], OSError):
            return
        super().handle_error(request, client_address)
