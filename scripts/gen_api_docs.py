#!/usr/bin/env python3
"""
Generate API.md by scanning all registered Flask routes.

Usage:
    cd receiver && python ../scripts/gen_api_docs.py > ../API.md

D33: Auto-generates route documentation from Flask blueprints.
"""
import os
import sys

# Add receiver to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "receiver"))

os.environ.setdefault("DATABASE_URL", "sqlite:///:memory:")
os.environ.setdefault("FLASK_TESTING", "true")
os.environ.setdefault("FLASK_ENV", "development")


def main():
    from app import create_app

    app = create_app()

    print("# VoltTracker API Reference")
    print()
    print("Auto-generated from registered Flask routes.")
    print()

    # Group by blueprint
    routes_by_bp: dict[str, list] = {}
    for rule in sorted(app.url_map.iter_rules(), key=lambda r: r.rule):
        if rule.endpoint == "static":
            continue
        bp_name = rule.endpoint.split(".")[0] if "." in rule.endpoint else "app"
        routes_by_bp.setdefault(bp_name, []).append(rule)

    for bp_name, rules in sorted(routes_by_bp.items()):
        print(f"## {bp_name}")
        print()
        print("| Method | Path | Endpoint |")
        print("|--------|------|----------|")
        for rule in rules:
            methods = ", ".join(sorted(rule.methods - {"HEAD", "OPTIONS"}))
            print(f"| {methods} | `{rule.rule}` | {rule.endpoint} |")
        print()


if __name__ == "__main__":
    main()
