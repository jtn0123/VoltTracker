"""
Application version.

Kept in its own tiny module so that request handlers can do
``from version import APP_VERSION`` without triggering a re-import of
``app.py``. When the server is started via ``python receiver/app.py``
(as ``docker-compose.dev.yml`` does), ``app.py`` is loaded under the
``__main__`` module name, so any ``from app import ...`` inside a
request handler forces Python to import ``app.py`` a **second** time
under the name ``app``. That second import re-runs ``create_app()``
and blows up when it tries to re-decorate already-registered
blueprints. See JTN-482.
"""

import json
import os


def _read_version() -> str:
    """Read version from frontend/package.json, or fall back to 'unknown'."""
    try:
        pkg_path = os.path.join(os.path.dirname(__file__), "frontend", "package.json")
        with open(pkg_path) as f:
            return json.load(f).get("version", "unknown")
    except Exception:
        return "unknown"


APP_VERSION = _read_version()
