"""
Regression tests for JTN-482.

When the dev server is started via ``python receiver/app.py`` (which is what
``docker-compose.dev.yml`` does), the entrypoint module is loaded under the
name ``__main__``, not ``app``. Previously, route handlers did
``from app import APP_VERSION`` inside the request, which forced Python to
import ``app.py`` a *second* time under the name ``app``. That second import
re-ran ``create_app()`` and crashed with::

    AssertionError: The setup method 'after_request' can no longer be called
    on the blueprint 'trips'. It has already been registered at least once,
    any changes will not be applied consistently.

These tests guard against regressions by:

1. Ensuring ``APP_VERSION`` lives in a dedicated ``version`` module that is
   safe to import from request handlers.
2. Ensuring the dashboard and health routes do not reference ``app.APP_VERSION``
   via a late ``from app import ...`` (the exact pattern that caused the bug).
3. Exercising the routes through the test client and confirming that
   ``create_app()`` is NOT invoked a second time as a side effect of serving
   them.
"""

import importlib

import pytest


def test_version_module_exposes_app_version():
    """version.APP_VERSION is the canonical source of truth."""
    import version

    assert hasattr(version, "APP_VERSION")
    assert isinstance(version.APP_VERSION, str)
    assert version.APP_VERSION  # non-empty


def test_app_reexports_version_for_backward_compat():
    """``from app import APP_VERSION`` must still work and match ``version``."""
    import app
    import version

    assert app.APP_VERSION == version.APP_VERSION


def test_dashboard_route_does_not_late_import_from_app():
    """The dashboard route must not contain ``from app import APP_VERSION``.

    That pattern is the root cause of JTN-482: under ``python app.py``, the
    running module is ``__main__``, so ``from app import ...`` inside a
    request handler triggers a full second import of ``app.py`` under the
    name ``app`` and re-runs ``create_app()``.
    """
    import inspect

    from routes import dashboard

    source = inspect.getsource(dashboard)
    assert "from app import APP_VERSION" not in source, (
        "routes/dashboard.py must not do `from app import APP_VERSION` "
        "inside a request handler — see JTN-482. Import from `version` "
        "instead."
    )


def test_system_route_does_not_late_import_from_app():
    """The /health route must not contain ``from app import APP_VERSION``."""
    import inspect

    from routes import system

    source = inspect.getsource(system)
    assert "from app import APP_VERSION" not in source, (
        "routes/system.py must not do `from app import APP_VERSION` inside "
        "a request handler — see JTN-482. Import from `version` instead."
    )


def test_dashboard_route_does_not_trigger_second_create_app(client, monkeypatch):
    """Serving the dashboard must not re-run create_app().

    This simulates the JTN-482 crash path: if any code imported during the
    request handler re-runs ``create_app()``, blueprint ``after_request``
    decorators fire twice and Flask raises an AssertionError.
    """
    import app as app_module

    call_count = {"n": 0}
    original_create_app = app_module.create_app

    def tracking_create_app(*args, **kwargs):
        call_count["n"] += 1
        return original_create_app(*args, **kwargs)

    monkeypatch.setattr(app_module, "create_app", tracking_create_app)

    # Hitting the dashboard must NOT call create_app() — the request-handling
    # code path should not re-import app.py. Note: auth is disabled in tests
    # (conftest sets FLASK_TESTING), so we expect 200.
    response = client.get("/")
    assert response.status_code == 200
    assert call_count["n"] == 0, (
        f"create_app() was invoked {call_count['n']} times while serving /. "
        "A route handler is likely re-importing app.py. See JTN-482."
    )


def test_health_route_does_not_trigger_second_create_app(client, monkeypatch):
    """Serving /health must not re-run create_app() either."""
    import app as app_module

    call_count = {"n": 0}
    original_create_app = app_module.create_app

    def tracking_create_app(*args, **kwargs):
        call_count["n"] += 1
        return original_create_app(*args, **kwargs)

    monkeypatch.setattr(app_module, "create_app", tracking_create_app)

    response = client.get("/health")
    assert response.status_code == 200
    assert call_count["n"] == 0

    data = response.get_json()
    assert data["status"] == "healthy"
    # Version should be populated from the version module (not the string "unknown"
    # unless package.json literally cannot be read).
    assert "version" in data


def test_dashboard_response_carries_app_version(client):
    """The rendered dashboard HTML should include the version string."""
    import version

    response = client.get("/")
    assert response.status_code == 200
    # The template renders ``app_version`` somewhere in the page; at the very
    # least the exact version string should appear in the HTML body.
    assert version.APP_VERSION.encode() in response.data


def test_version_module_is_importable_without_app():
    """``version`` must be importable on its own, with no side effects.

    This is what lets request handlers safely ``from version import APP_VERSION``
    regardless of whether the running entrypoint is ``app`` or ``__main__``.
    """
    # Fresh import (unload any cached copy first).
    import sys

    sys.modules.pop("version", None)
    mod = importlib.import_module("version")
    assert hasattr(mod, "APP_VERSION")
    # Importing ``version`` must not pull in Flask or spin up an app.
    assert not hasattr(mod, "create_app")
    assert not hasattr(mod, "Flask")


@pytest.mark.parametrize("module_name", ["routes.dashboard", "routes.system"])
def test_route_modules_import_version_at_module_level(module_name):
    """Route modules should import APP_VERSION once at import time.

    Importing at module level (rather than lazily inside the handler) is the
    fix direction recommended in JTN-482 and prevents any repeat-import
    shenanigans on the hot path.
    """
    module = importlib.import_module(module_name)
    # APP_VERSION should be accessible as a module attribute after import.
    assert hasattr(module, "APP_VERSION"), (
        f"{module_name} should import APP_VERSION at module level so it is "
        "resolved once, not on every request. See JTN-482."
    )
