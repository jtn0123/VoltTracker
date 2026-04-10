"""
JTN-488: Regression tests for the Socket.IO polling handshake.

Before the fix, Flask-SocketIO 5.3.6 tried to assign
``ctx.session = session_obj`` on every connect, which Flask 3.1+ rejects with
``AttributeError: property 'session' of 'RequestContext' object has no setter``.
The namespace ``connect`` handler then failed, the client received
``connect_error``, and the browser cycled the handshake forever -- producing
the ``POST /socket.io/?EIO=4&transport=polling&sid=...`` 400 flood that the
user reported in the dashboard console.

These tests exercise the HTTP layer (GET handshake + POST auth packet + second
POST) and verify that:

* the GET handshake returns 200 and surfaces a session id,
* the POST auth packet is accepted with a 2xx response,
* the server-side connect handler runs without raising (previously it crashed
  in Flask-SocketIO because ``ctx.session`` is read-only on Flask 3.1+),
* ``manage_session=False`` is configured on the SocketIO instance so the
  incompatible code path is disabled.
"""

import logging
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))


SID_PATTERN = re.compile(r'"sid":"([^"]+)"')


def _extract_sid(body: bytes) -> str:
    """Pull the engine.io sid out of the handshake response payload."""
    match = SID_PATTERN.search(body.decode("utf-8", errors="replace"))
    assert match is not None, f"no sid in handshake body: {body!r}"
    return match.group(1)


class TestSocketIOHandshakePolling:
    """HTTP-level smoke tests for the /socket.io/ polling transport."""

    def test_handshake_get_returns_200_with_sid(self, client):
        """GET /socket.io/?EIO=4&transport=polling yields a session id."""
        resp = client.get("/socket.io/?EIO=4&transport=polling")
        assert resp.status_code == 200, resp.data
        sid = _extract_sid(resp.data)
        assert sid

    def test_auth_post_after_handshake_is_accepted(self, client, monkeypatch, caplog):
        """The Socket.IO auth packet POST must be accepted *and* run cleanly.

        Regression for JTN-488: with ``manage_session=True`` (the library
        default), this POST triggered
        ``AttributeError: property 'session' of 'RequestContext' object has no
        setter`` inside ``engineio.server.run_handler``. The HTTP POST still
        returned 200 because engine.io swallows exceptions raised inside a
        handler, but the namespace ``connect`` handler never ran, so the
        browser immediately received a connect_error and looped -- producing
        the 25+ POST-400 flood the user reported once the client gave up and
        started reusing stale sids.

        We therefore assert *both* that the POST is accepted *and* that no
        ``property 'session'`` AttributeError was logged to
        ``engineio.server``. The fix is ``SocketIO(..., manage_session=False)``.
        """
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", False)

        handshake = client.get("/socket.io/?EIO=4&transport=polling")
        assert handshake.status_code == 200
        sid = _extract_sid(handshake.data)

        with caplog.at_level(logging.ERROR, logger="engineio.server"):
            auth_post = client.post(
                f"/socket.io/?EIO=4&transport=polling&sid={sid}",
                data='40{"token":"","password":""}',
                headers={"Content-Type": "text/plain;charset=UTF-8"},
            )

        assert auth_post.status_code < 400, f"auth POST returned {auth_post.status_code}: {auth_post.data!r}"

        offenders = [
            r
            for r in caplog.records
            if r.name == "engineio.server"
            and (
                "property 'session'" in r.getMessage()
                or "property 'session'" in ("".join(str(a) for a in (r.args or ())))
                or (r.exc_info and "property 'session'" in str(r.exc_info[1]))
            )
        ]
        assert not offenders, (
            "Flask-SocketIO 5.3.6 session-setter bug is back -- "
            "ensure SocketIO(..., manage_session=False) is set. "
            f"Offending log records: {[r.getMessage() for r in offenders]}"
        )

    def test_repeated_handshake_and_auth_post_cycle_is_stable(self, client, monkeypatch):
        """Simulate the browser's reconnect loop -- every cycle must stay clean.

        The dogfood report showed 25+ ``POST /socket.io/ ... 400`` in two
        minutes. We run several full handshake+auth cycles here and assert
        none of them regress to 4xx.
        """
        monkeypatch.setattr("config.Config.WEBSOCKET_AUTH_ENABLED", False)

        for _ in range(5):
            handshake = client.get("/socket.io/?EIO=4&transport=polling")
            assert handshake.status_code == 200
            sid = _extract_sid(handshake.data)

            auth_post = client.post(
                f"/socket.io/?EIO=4&transport=polling&sid={sid}",
                data='40{"token":"","password":""}',
                headers={"Content-Type": "text/plain;charset=UTF-8"},
            )
            assert auth_post.status_code < 400, f"auth POST returned {auth_post.status_code}: {auth_post.data!r}"

    def test_post_with_unknown_sid_is_400_but_does_not_crash(self, client):
        """A stale sid should be rejected cleanly (not raise)."""
        resp = client.post(
            "/socket.io/?EIO=4&transport=polling&sid=does-not-exist",
            data="40{}",
            headers={"Content-Type": "text/plain;charset=UTF-8"},
        )
        # engine.io returns 400 "Invalid session" for unknown sids, which is
        # expected behaviour. The point is that the POST still gets an HTTP
        # response instead of raising a 500.
        assert resp.status_code in (400, 404)


class TestSocketIOManageSession:
    """Configuration guard: ``manage_session`` must stay disabled."""

    def test_socketio_manage_session_is_false(self, app):
        """
        Regression guard for JTN-488.

        Flask-SocketIO 5.3.6's ``manage_session=True`` code path assigns
        ``ctx.session = session_obj`` inside the request context, which
        Flask 3.1 rejects. Until we upgrade Flask-SocketIO past that bug,
        this flag must stay off.
        """
        sio = app.extensions["socketio"]
        # ``manage_session`` lives on the Flask-SocketIO wrapper.
        assert getattr(sio, "manage_session", True) is False, (
            "SocketIO(manage_session=False) is required on Flask 3.1+ to avoid "
            "the RequestContext.session read-only crash (JTN-488)."
        )
