"""One-task loopback Responses relay; only model networking leaves Android.

AndroidWorld fixes Android's clock to 2023. HTTPS certificate validation must
therefore happen on the host's real clock, without weakening TLS or the benchmark.
This relay has one fixed upstream/model, no redirects, no request/response logs,
and an unguessable, task-scoped token instead of the provider key on Android.
"""
from __future__ import annotations

import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import socket
import ssl
import threading
import time
from urllib.parse import urlsplit

from task_catalog import validate_model_connection


MAX_BODY = 16 * 1024 * 1024


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("Duplicate JSON field")
        value[key] = item
    return value


class ModelRelay:
    def __init__(self, *, endpoint: str, model: str, api_key: str,
                 allow_http: bool = False, lifetime_seconds: float = 900,
                 request_timeout_seconds: float = 180, max_body_bytes: int = MAX_BODY):
        validate_model_connection(model, "default", endpoint, allow_http)
        if not api_key or len(api_key) > 4096 or any(not 33 <= ord(c) <= 126 for c in api_key):
            raise ValueError("Invalid API key")
        if not 1 <= lifetime_seconds <= 1800 or not 0 < request_timeout_seconds <= 1800:
            raise ValueError("Invalid relay timeout")
        if not 1 <= max_body_bytes <= MAX_BODY:
            raise ValueError("Invalid relay size limit")
        self.model = model
        self._endpoint = urlsplit(endpoint)
        self._key = api_key
        self._token = secrets.token_urlsafe(32)
        self._max_body = max_body_bytes
        self._timeout = request_timeout_seconds
        self._lifetime = lifetime_seconds
        self._deadline = 0.0
        self._started = 0.0
        self._server = None
        self._thread = None
        self._closed = False
        self._busy = threading.Lock()
        self._state = threading.Lock()
        self._active = None
        self._active_socket = None
        self._client = None
        self._requests = 0
        self._http_successes = 0
        self._request_seconds = 0.0

    def start(self):
        if self._server is not None or self._closed:
            raise RuntimeError("Relay is single-use")
        relay = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.0"

            def setup(self):
                super().setup()
                self.connection.settimeout(min(10, relay._timeout))

            def log_message(self, *_):
                pass  # Never log URLs, headers, input, or model output.

            def do_POST(self):
                relay._handle(self)

            def do_GET(self):
                relay._error(self, 405, "method_not_allowed")

        class Server(ThreadingHTTPServer):
            daemon_threads = True
            block_on_close = False

            def handle_error(self, *_):
                pass  # Socket failures must not print request data/tracebacks.

        self._server = Server(("127.0.0.1", 0), Handler)
        self._started = time.monotonic()
        self._deadline = self._started + self._lifetime
        self._thread = threading.Thread(target=self._server.serve_forever,
                                        kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        return self

    @property
    def local_endpoint(self):
        if self._server is None or self._closed:
            raise RuntimeError("Relay is not active")
        return f"http://127.0.0.1:{self._server.server_port}/responses"

    @property
    def device_endpoint(self):
        return self.local_endpoint.replace("127.0.0.1", "10.0.2.2")

    @property
    def token(self):
        if self._server is None or self._closed:
            raise RuntimeError("Relay is not active")
        return self._token

    @staticmethod
    def _error(handler, status, code):
        payload = json.dumps({"error": {"code": code, "message": code}}).encode()
        try:
            handler.send_response(status)
            handler.send_header("Content-Type", "application/json")
            handler.send_header("Content-Length", str(len(payload)))
            handler.send_header("Connection", "close")
            handler.send_header("Cache-Control", "no-store")
            handler.end_headers()
            handler.wfile.write(payload)
        except OSError:
            pass
        handler.close_connection = True

    def _handle(self, handler):
        if self._closed or time.monotonic() >= self._deadline:
            return self._error(handler, 410, "relay_expired")
        auth = handler.headers.get_all("Authorization", [])
        if len(auth) != 1 or not hmac.compare_digest(auth[0].encode("utf-8"), ("Bearer " + self._token).encode("ascii")):
            return self._error(handler, 401, "relay_unauthorized")
        if handler.path != "/responses":
            return self._error(handler, 404, "relay_path_denied")
        lengths = handler.headers.get_all("Content-Length", [])
        if (handler.headers.get_all("Transfer-Encoding") or len(lengths) != 1
                or len(lengths[0]) > 10 or not lengths[0].isascii() or not lengths[0].isdigit()):
            return self._error(handler, 400, "relay_length_required")
        length = int(lengths[0])
        if length < 1 or length > self._max_body:
            return self._error(handler, 413, "relay_body_too_large")
        if handler.headers.get_content_type() != "application/json":
            return self._error(handler, 415, "relay_json_required")
        if not self._busy.acquire(blocking=False):
            return self._error(handler, 429, "relay_request_in_progress")
        connection = None
        timer = None
        forwarded = False
        response_started = False
        start = time.monotonic()
        try:
            timeout = min(self._timeout, self._deadline - start)
            if timeout <= 0:
                return self._error(handler, 410, "relay_expired")
            handler.connection.settimeout(timeout)
            with self._state:
                self._client = handler.connection
            timer = threading.Timer(timeout, self._cancel_active, args=(handler.connection,))
            timer.daemon = True
            timer.start()
            payload = handler.rfile.read(length)
            if len(payload) != length:
                return self._error(handler, 400, "relay_incomplete_body")
            request = json.loads(payload.decode("utf-8"), object_pairs_hook=_unique_object,
                                 parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
            if (not isinstance(request, dict) or request.get("model") != self.model
                    or request.get("stream") is not True or request.get("store") is not False):
                return self._error(handler, 400, "relay_request_scope_denied")
            endpoint = self._endpoint
            options = {"host": endpoint.hostname, "port": endpoint.port, "timeout": timeout}
            if endpoint.scheme == "https":
                connection = http.client.HTTPSConnection(**options, context=ssl.create_default_context())
            else:
                connection = http.client.HTTPConnection(**options)
            with self._state:
                if self._closed:
                    return self._error(handler, 410, "relay_expired")
                self._active = connection
                self._requests += 1
            forwarded = True
            connection.connect()
            with self._state:
                self._active_socket = connection.sock
            # DNS lookup may outlive a socket timeout. Never send credentials or
            # a request if a delayed connect completed after this task expired.
            if self._closed or time.monotonic() >= min(self._deadline, start + timeout):
                return self._error(handler, 410, "relay_expired")
            connection.auto_open = False  # A cancelled socket must not reconnect.
            # Never forward incoming Authorization, URL, cookies or custom headers.
            connection.request("POST", endpoint.path, body=payload, headers={
                "Authorization": "Bearer " + self._key,
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "text/event-stream", "Accept-Encoding": "identity",
                "Connection": "close",
            })
            response = connection.getresponse()
            if 300 <= response.status < 400:
                return self._error(handler, 502, "relay_upstream_redirect_denied")
            if not 200 <= response.status < 300:
                # Upstream error bodies may echo credentials. Return only status.
                return self._error(handler, response.status, "upstream_http_" + str(response.status))
            if not response.getheader("Content-Type", "").lower().startswith("text/event-stream"):
                return self._error(handler, 502, "relay_upstream_not_sse")
            with self._state:
                self._http_successes += 1
            handler.send_response(response.status)
            handler.send_header("Content-Type", "text/event-stream")
            handler.send_header("Cache-Control", "no-store")
            handler.send_header("Connection", "close")
            handler.end_headers()
            response_started = True
            count = 0
            while not self._closed and time.monotonic() < min(self._deadline, start + timeout):
                chunk = response.read1(min(65536, self._max_body + 1 - count))
                if not chunk:
                    break
                count += len(chunk)
                if count > self._max_body:
                    break  # Truncated SSE fails the Planner's completion check.
                handler.wfile.write(chunk)
                handler.wfile.flush()
        except (ValueError, UnicodeDecodeError, RecursionError):
            if not response_started:
                self._error(handler, 400, "relay_invalid_json")
        except Exception:
            if not response_started:
                self._error(handler, 502, "relay_upstream_unavailable")
        finally:
            if timer is not None:
                timer.cancel()
            if connection is not None:
                connection.close()
            with self._state:
                self._active = None
                self._active_socket = None
                self._client = None
                if forwarded:
                    self._request_seconds += time.monotonic() - start
            handler.close_connection = True
            self._busy.release()

    def _cancel_active(self, expected_client=None):
        with self._state:
            active, upstream_socket, client = self._active, self._active_socket, self._client
            if expected_client is not None and client is not expected_client:
                return
        for sock in (upstream_socket or getattr(active, "sock", None), client):
            if sock is not None:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    sock.close()
                except OSError:
                    pass
        if active is not None:
            active.close()

    def statistics(self):
        with self._state:
            return {"transport": "host_relay", "upstream_requests": self._requests,
                    "upstream_http_successes": self._http_successes,
                    "request_elapsed_seconds": self._request_seconds,
                    "timing_note": "Network time overlaps Android model_seconds; do not add again",
                    "provider_key_sent_to_android": False}

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._cancel_active()
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=2)
        self._key = ""
        self._token = ""

    def __enter__(self):
        return self.start()

    def __exit__(self, *_):
        self.close()
