"""Loopback-only relay tests: synthetic keys/responses, no device or provider API."""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import socket
import ssl
import threading
import time
import unittest
from unittest import mock
from urllib.parse import urlsplit

from model_relay import ModelRelay


class RelayTests(unittest.TestCase):
    def setUp(self):
        self.requests = []
        self.status = 200
        self.body = b'data: {"type":"response.completed"}\n\n'
        self.content_type = "text/event-stream"
        self.gate = None
        self.after_chunk_gate = None
        self.arrived = threading.Event()
        fixture = self

        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                payload = self.rfile.read(int(self.headers["Content-Length"]))
                fixture.requests.append((self.path, dict(self.headers), payload))
                fixture.arrived.set()
                if fixture.gate:
                    fixture.gate.wait(2)
                self.send_response(fixture.status)
                self.send_header("Content-Type", fixture.content_type)
                self.send_header("Location", "http://127.0.0.1:1/do-not-follow")
                self.end_headers()
                try:
                    if fixture.after_chunk_gate is not None:
                        self.wfile.write(b"data: first\n\n")
                        self.wfile.flush()
                        fixture.after_chunk_gate.wait(2)
                    self.wfile.write(fixture.body)
                except OSError:
                    pass

        self.upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        self.upstream.daemon_threads = True
        threading.Thread(target=self.upstream.serve_forever,
                         kwargs={"poll_interval": 0.02}, daemon=True).start()
        self.addCleanup(self.upstream.server_close)
        self.addCleanup(self.upstream.shutdown)
        self.endpoint = f"http://127.0.0.1:{self.upstream.server_port}/v1/responses"

    def relay(self, **kwargs):
        relay = ModelRelay(endpoint=kwargs.pop("endpoint", self.endpoint), model="fixture-model",
                           api_key="fixture-provider-secret", allow_http=True, **kwargs).start()
        self.addCleanup(relay.close)
        return relay

    def request(self, relay, *, token=None, path="/responses", payload=None, method="POST", extra_headers=None):
        value = {"model": "fixture-model", "stream": True, "store": False, "input": "fixture"}
        if payload is None:
            payload = json.dumps(value).encode()
        headers = {"Authorization": "Bearer " + (token if token is not None else relay.token),
                   "Content-Type": "application/json"}
        headers.update(extra_headers or {})
        url = urlsplit(relay.local_endpoint)
        connection = http.client.HTTPConnection(url.hostname, url.port, timeout=3)
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            return response.status, response.getheader("Content-Type"), response.read()
        finally:
            connection.close()

    def test_forwards_sse_to_fixed_endpoint_with_host_key_only(self):
        relay = self.relay()
        self.assertEqual(relay._server.server_address[0], "127.0.0.1")
        self.assertIn("10.0.2.2", relay.device_endpoint)
        self.assertNotEqual(relay.token, "fixture-provider-secret")
        status, content_type, body = self.request(relay, extra_headers={"X-Target": "evil", "Cookie": "secret"})
        self.assertEqual((status, content_type, body), (200, "text/event-stream", self.body))
        path, headers, payload = self.requests[0]
        self.assertEqual(path, "/v1/responses")
        self.assertEqual(headers["Authorization"], "Bearer fixture-provider-secret")
        self.assertNotIn("Cookie", headers)
        self.assertNotIn("X-Target", headers)
        self.assertEqual(json.loads(payload)["model"], "fixture-model")
        stats = relay.statistics()
        self.assertEqual(stats["upstream_requests"], 1)
        self.assertEqual(stats["upstream_http_successes"], 1)
        self.assertNotIn("fixture-provider-secret", json.dumps(stats))
        self.assertNotIn(relay.token, json.dumps(stats))

    def test_rejects_missing_token_and_other_paths_without_upstream_calls(self):
        relay = self.relay()
        self.assertEqual(self.request(relay, token="wrong")[0], 401)
        self.assertEqual(self.request(relay, path="/responses?target=elsewhere")[0], 404)
        self.assertEqual(self.request(relay, path="http://elsewhere/responses")[0], 404)
        self.assertEqual(self.request(relay, method="GET")[0], 405)
        self.assertEqual(self.requests, [])

    def test_model_stream_storage_and_duplicate_fields_are_enforced(self):
        relay = self.relay()
        values = [b'{"model":"other","stream":true,"store":false}',
                  b'{"model":"fixture-model","stream":false,"store":false}',
                  b'{"model":"fixture-model","stream":true,"store":true}',
                  b'{"model":"fixture-model","stream":true,"store":false,"model":"fixture-model"}',
                  b'{"model":"fixture-model","stream":true,"store":false,"temperature":NaN}',
                  b'not json']
        for value in values:
            with self.subTest(payload=value):
                self.assertEqual(self.request(relay, payload=value)[0], 400)
        self.assertEqual(self.requests, [])

    def test_no_redirect_following_or_upstream_error_body_leak(self):
        relay = self.relay()
        self.status = 302
        self.assertEqual(self.request(relay)[0], 502)
        self.status = 401
        self.body = b'fixture-provider-secret and raw upstream error'
        status, _, body = self.request(relay)
        self.assertEqual(status, 401)
        self.assertNotIn(self.body, body)
        self.assertNotIn(b"fixture-provider-secret", body)
        self.assertEqual(len(self.requests), 2)

    def test_request_and_response_size_limits(self):
        relay = self.relay(max_body_bytes=100)
        self.assertEqual(self.request(relay, payload=b"x" * 101)[0], 413)
        self.body = b"x" * 101
        status, _, body = self.request(relay)
        self.assertEqual(status, 200)
        self.assertLessEqual(len(body), 100)
        self.assertEqual(len(self.requests), 1)

    def test_only_sse_success_is_accepted(self):
        relay = self.relay()
        self.content_type = "text/html"
        self.assertEqual(self.request(relay)[0], 502)

    def test_sse_is_streamed_before_upstream_finishes(self):
        self.after_chunk_gate = threading.Event()
        self.addCleanup(self.after_chunk_gate.set)
        relay = self.relay()
        url = urlsplit(relay.local_endpoint)
        client = http.client.HTTPConnection(url.hostname, url.port, timeout=1)
        self.addCleanup(client.close)
        client.request("POST", "/responses", body=b'{"model":"fixture-model","stream":true,"store":false}',
                       headers={"Authorization": "Bearer " + relay.token, "Content-Type": "application/json"})
        response = client.getresponse()
        self.assertEqual(response.read1(13), b"data: first\n\n")
        self.assertFalse(self.after_chunk_gate.is_set())
        self.after_chunk_gate.set()
        self.assertEqual(response.read(), self.body)

    def test_expiration_and_single_use_close_clear_secrets(self):
        relay = self.relay()
        relay._deadline = time.monotonic() - 1
        self.assertEqual(self.request(relay)[0], 410)
        endpoint = urlsplit(relay.local_endpoint)
        relay.close()
        relay.close()
        self.assertEqual(relay._key, "")
        self.assertEqual(relay._token, "")
        with self.assertRaises(RuntimeError):
            relay.start()
        with self.assertRaises(OSError):
            socket.create_connection((endpoint.hostname, endpoint.port), timeout=0.2)

    def test_timeout_is_bounded_while_upstream_is_stalled(self):
        self.gate = threading.Event()
        self.addCleanup(self.gate.set)
        relay = self.relay(request_timeout_seconds=0.1)
        start = time.monotonic()
        try:
            status, _, _ = self.request(relay)
            self.assertEqual(status, 502)
        except (OSError, http.client.HTTPException):
            pass  # Deadline can close the socket before a safe error response.
        self.assertLess(time.monotonic() - start, 1.5)

    def test_parallel_requests_rejected_and_close_cancels_active_request(self):
        self.gate = threading.Event()
        self.addCleanup(self.gate.set)
        relay = self.relay()
        failures = []
        def blocked():
            try:
                self.request(relay)
            except (OSError, http.client.HTTPException) as error:
                failures.append(type(error).__name__)
        thread = threading.Thread(target=blocked, daemon=True)
        thread.start()
        self.assertTrue(self.arrived.wait(1))
        self.assertEqual(self.request(relay)[0], 429)
        relay.close()
        thread.join(1)
        self.assertFalse(thread.is_alive())
        self.assertEqual(len(self.requests), 1)

    def test_https_uses_verified_default_context_and_never_plain_http(self):
        relay = self.relay(endpoint="https://provider.example/v1/responses")
        with mock.patch("model_relay.http.client.HTTPSConnection", side_effect=OSError) as https, \
             mock.patch("model_relay.http.client.HTTPConnection") as plain:
            # Patching module http.client is process global; use original class for the client.
            original = self.original_http
            url = urlsplit(relay.local_endpoint)
            client = original(url.hostname, url.port, timeout=2)
            payload = b'{"model":"fixture-model","stream":true,"store":false}'
            client.request("POST", "/responses", body=payload, headers={
                "Authorization": "Bearer " + relay.token, "Content-Type": "application/json"})
            response = client.getresponse()
            self.assertEqual(response.status, 502)
            response.read()
            client.close()
            context = https.call_args.kwargs["context"]
            self.assertTrue(context.check_hostname)
            self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
            plain.assert_not_called()

    original_http = http.client.HTTPConnection


if __name__ == "__main__":
    unittest.main()
