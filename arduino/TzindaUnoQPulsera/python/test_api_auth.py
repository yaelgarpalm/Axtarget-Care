import http.client
import os
import http.client
import socket
import sys
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

from api_auth import authorized, configured_token, valid_token
from api_server import BoundedThreadingHTTPServer
import api_tls
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bricks/tzindable/app"))
from gatt_security import CHARACTERISTIC_FLAGS

# Arduino App Lab libraries are not present in host tests; importing the module
# must not start hardware services and the HTTP handler itself is host-testable.
arduino = types.ModuleType("arduino")
arduino.__path__ = []
app_utils = types.ModuleType("arduino.app_utils")
app_utils.App = object()
app_utils.Bridge = object()
arduino.app_utils = app_utils
with patch.dict(sys.modules, {"arduino": arduino, "arduino.app_utils": app_utils, "tzindable": types.ModuleType("tzindable")}):
    import main


class ApiAuthTests(unittest.TestCase):
    token = "0123456789abcdef" * 2

    def test_rejects_missing_short_and_whitespace_tokens(self):
        for token in (None, "short", "x" * 31, "x" * 31 + " "):
            with self.subTest(token=token):
                self.assertFalse(valid_token(token))

    def test_accepts_long_random_token(self):
        self.assertTrue(valid_token(self.token))

    def test_missing_server_secret_is_unconfigured(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(configured_token())

    def test_requires_exact_bearer_token(self):
        self.assertTrue(authorized(f"Bearer {self.token}", self.token))
        self.assertTrue(authorized(f"bearer {self.token}", self.token))
        for header in (None, self.token, "Basic " + self.token, "Bearer wrong", "Bearer " + self.token + "x"):
            with self.subTest(header=header):
                self.assertFalse(authorized(header, self.token))

    def setUp(self):
        self.server = BoundedThreadingHTTPServer(("127.0.0.1", 0), main.ApiHandler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(patch.stopall)
        self.configured = patch.dict(os.environ, {"TZINDA_API_TOKEN": self.token})
        self.configured.start()

    def request(self, method, path, token=None, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        headers = {"Content-Type": "application/json"} if body is not None else {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        payload = response.read()
        result = response.status, payload
        connection.close()
        return result

    def test_private_reads_reject_missing_or_wrong_credentials(self):
        for path in ("/api/v1/stats", "/api/v1/sensor"):
            for supplied in (None, "wrong"):
                with self.subTest(path=path, supplied=supplied):
                    status, _ = self.request("GET", path, supplied)
                    self.assertEqual(401, status)

    def test_health_remains_available_without_credentials(self):
        status, _ = self.request("GET", "/health")
        self.assertEqual(200, status)

    def test_post_rejects_missing_credentials_before_writing(self):
        with patch.object(main, "store_reading") as store:
            # Reject at the header stage. Sending a request body before the
            # server has authorized it leaves unread bytes on a closed socket
            # and Windows reports ConnectionAbortedError instead of the 401.
            status, _ = self.request("POST", "/api/v1/sensor")
            self.assertEqual(401, status)
            store.assert_not_called()

    def test_authenticated_post_is_accepted(self):
        with patch.object(main, "store_reading", return_value={"id": 7, "received_at_ms": 1}) as store:
            status, _ = self.request("POST", "/api/v1/sensor", self.token, '{"bpm":72}')
            self.assertEqual(201, status)
            store.assert_called_once()

    def test_missing_server_secret_disables_private_api(self):
        with patch.dict(os.environ, {}, clear=True):
            status, _ = self.request("GET", "/api/v1/stats", self.token)
            self.assertEqual(503, status)


class ApiTlsTests(unittest.TestCase):
    def test_missing_or_partial_certificate_configuration_fails_closed(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "HTTPS is required"):
                api_tls.create_server_context()
        with patch.dict(os.environ, {"TZINDA_TLS_KEY_FILE": "/device/key.pem"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "Both TZINDA_TLS_CERT_FILE"):
                api_tls.create_server_context()

    def test_server_context_requires_tls_12_and_loads_the_configured_pair(self):
        with patch("api_tls.ssl.SSLContext") as ssl_context:
            context = api_tls.create_server_context("/device/cert.pem", "/device/key.pem")
        self.assertIs(context, ssl_context.return_value)
        self.assertEqual(api_tls.ssl.TLSVersion.TLSv1_2, context.minimum_version)
        context.load_cert_chain.assert_called_once_with(certfile="/device/cert.pem", keyfile="/device/key.pem")

    def test_server_start_does_not_open_an_http_listener_without_tls_files(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(main, "initialize_db") as initialize_db:
            with self.assertRaisesRegex(RuntimeError, "HTTPS is required"):
                main.start_https_server()
        initialize_db.assert_not_called()


class ApiServerResourceTests(unittest.TestCase):
    def start_server(self, server):
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 2)
        self.addCleanup(server.shutdown)
        return thread

    def test_tls_handshake_is_deferred_until_after_accept(self):
        class DeferredHandshakeContext:
            def __init__(self):
                self.kwargs = None

            def wrap_socket(self, request, **kwargs):
                self.kwargs = kwargs
                return request

        context = DeferredHandshakeContext()
        server = BoundedThreadingHTTPServer(("127.0.0.1", 0), main.ApiHandler, ssl_context=context)
        client = socket.create_connection(server.server_address, timeout=1)
        try:
            request, _ = server.get_request()
            self.assertIsNotNone(request)
            self.assertEqual({"server_side": True, "do_handshake_on_connect": False}, context.kwargs)
            request.close()
        finally:
            client.close()
            server.server_close()

    def test_stalled_tls_handshake_uses_a_bounded_worker_timeout(self):
        class StalledTlsSocket:
            timeout = None
            was_shutdown = False
            was_closed = False

            def settimeout(self, value):
                self.timeout = value

            def do_handshake(self):
                raise TimeoutError("test handshake timeout")

            def shutdown(self, how):
                self.was_shutdown = True

            def close(self):
                self.was_closed = True

        server = BoundedThreadingHTTPServer(
            ("127.0.0.1", 0), main.ApiHandler,
            ssl_context=object(),
            max_clients=1,
            tls_handshake_timeout_seconds=0.01,
        )
        request = StalledTlsSocket()
        server.process_request(request, ("127.0.0.1", 0))
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            slot_available = server._client_slots.acquire(blocking=False)
            if slot_available:
                server._client_slots.release()
                break
            time.sleep(0.01)
        self.assertTrue(slot_available, "stalled handshake must release its bounded worker slot")
        self.assertEqual(0.01, request.timeout)
        self.assertTrue(request.was_shutdown)
        self.assertTrue(request.was_closed)
        server.server_close()

    def test_stalled_headers_use_a_bounded_slot_and_deadline(self):
        server = BoundedThreadingHTTPServer(
            ("127.0.0.1", 0), main.ApiHandler,
            max_clients=1,
            socket_idle_timeout_seconds=2,
            request_deadline_seconds=0.25,
        )
        self.start_server(server)
        stalled = socket.create_connection(server.server_address, timeout=1)
        stalled.sendall(b"GET /health HTTP/1.1\r\nHost: ")
        self.addCleanup(stalled.close)
        time.sleep(0.05)

        saturated = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=1)
        saturated.request("GET", "/health")
        with self.assertRaises((OSError, http.client.HTTPException)):
            saturated.getresponse()
        saturated.close()

        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            slot_available = server._client_slots.acquire(blocking=False)
            if slot_available:
                server._client_slots.release()
                break
            time.sleep(0.01)
        self.assertTrue(slot_available, "expired request must release its bounded worker slot")
        recovered = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=1)
        recovered.request("GET", "/health")
        response = recovered.getresponse()
        self.assertEqual(200, response.status)
        response.read()
        recovered.close()

    def test_slow_authenticated_body_times_out_before_writing(self):
        server = BoundedThreadingHTTPServer(
            ("127.0.0.1", 0), main.ApiHandler,
            max_clients=1,
            socket_idle_timeout_seconds=2,
            request_deadline_seconds=0.25,
        )
        self.start_server(server)
        token = "0123456789abcdef" * 2
        with patch.dict(os.environ, {"TZINDA_API_TOKEN": token}), patch.object(main, "store_reading") as store:
            stalled = socket.create_connection(server.server_address, timeout=1)
            stalled.sendall(
                f"POST /api/v1/sensor HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\n"
                "Content-Type: application/json\r\nContent-Length: 20\r\n\r\n{\"bpm\":".encode("ascii")
            )
            self.addCleanup(stalled.close)

            deadline = time.monotonic() + 1
            while time.monotonic() < deadline:
                slot_available = server._client_slots.acquire(blocking=False)
                if slot_available:
                    server._client_slots.release()
                    break
                time.sleep(0.01)
            self.assertTrue(slot_available, "expired request body must release its bounded worker slot")
            store.assert_not_called()


class GattSecurityTests(unittest.TestCase):
    def test_sensor_characteristics_require_an_encrypted_link(self):
        self.assertEqual({"read", "notify", "encrypt-read", "encrypt-notify"}, set(CHARACTERISTIC_FLAGS))
        server_source = (Path(__file__).resolve().parents[1] / "bricks/tzindable/app/server.py").read_text(encoding="utf-8")
        self.assertIn("return list(CHARACTERISTIC_FLAGS)", server_source)
        self.assertIn('Variant("as", list(CHARACTERISTIC_FLAGS))', server_source)


if __name__ == "__main__":
    unittest.main()
