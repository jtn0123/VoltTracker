#!/usr/bin/env python3
"""Bulk fix SonarQube issues."""
import re, sys

def fix_file(path, replacements):
    """Apply list of (old, new) replacements to a file."""
    try:
        with open(path) as f:
            content = f.read()
        original = content
        for old, new in replacements:
            content = content.replace(old, new, 1)
        if content != original:
            with open(path, 'w') as f:
                f.write(content)
            print(f"  Fixed: {path}")
        else:
            print(f"  SKIP (no match): {path}")
    except Exception as e:
        print(f"  ERROR: {path}: {e}")

# === Alembic migration: S6546 Union type hints ===
fix_file('receiver/migrations_alembic/versions/8fddfd9e0ab8_initial_schema.py', [
    ("revision: str = '8fddfd9e0ab8'\ndown_revision: Union[str, Sequence[str], None] = None\nbranch_labels: Union[str, Sequence[str], None] = None\ndepends_on: Union[str, Sequence[str], None] = None",
     "revision: str = '8fddfd9e0ab8'\ndown_revision: str | Sequence[str] | None = None\nbranch_labels: str | Sequence[str] | None = None\ndepends_on: str | Sequence[str] | None = None"),
])

print("Done with fix_sonar.py")
