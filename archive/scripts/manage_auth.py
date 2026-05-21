#!/usr/bin/env python3
"""
VoltTracker Authentication Management CLI

Utility script for managing:
- API tokens (TORQUE_API_TOKEN)
- WebSocket tokens (WEBSOCKET_TOKEN)
- Dashboard passwords
- Secret keys (SECRET_KEY)
- API key rotation

Usage:
    python scripts/manage_auth.py generate-token --type torque
    python scripts/manage_auth.py generate-token --type websocket
    python scripts/manage_auth.py generate-secret-key
    python scripts/manage_auth.py hash-password <password>
    python scripts/manage_auth.py rotate-key --current <current_token>
"""

import sys
import os
import argparse
from pathlib import Path

# Add parent directory to Python path
sys.path.insert(0, str(Path(__file__).parent.parent / "receiver"))

from utils.auth_utils import (
    generate_api_token,
    generate_websocket_token,
    hash_api_key,
    generate_secret_key,
    get_api_key_manager,
)
from werkzeug.security import generate_password_hash


def generate_token_command(args):
    """Generate a new authentication token."""
    token_type = args.type

    if token_type == "torque":
        token = generate_api_token(prefix="torque", length=32)
        print("Generated Torque API Token:")
        print(f"  {token}")
        print("\nAdd to your .env file:")
        print(f"  TORQUE_API_TOKEN={token}")
        print("\nIn Torque Pro, use URL:")
        print(f"  http://your-server:8080/torque/upload/{token}")

    elif token_type == "websocket":
        token = generate_websocket_token(length=32)
        print("Generated WebSocket Token:")
        print(f"  {token}")
        print("\nAdd to your .env file:")
        print(f"  WEBSOCKET_TOKEN={token}")
        print("\nIn your WebSocket client, connect with:")
        print(f"  auth: {{ token: '{token}' }}")

    elif token_type == "secret":
        token = generate_secret_key(length=32)
        print("Generated Flask SECRET_KEY:")
        print(f"  {token}")
        print("\nAdd to your .env file:")
        print(f"  SECRET_KEY={token}")

    else:
        print(f"Error: Unknown token type '{token_type}'")
        print("Valid types: torque, websocket, secret")
        sys.exit(1)


def hash_password_command(args):
    """Hash a password for storage."""
    password = args.password
    method = args.method

    hashed = generate_password_hash(password, method=method)
    print(f"Hashed password ({method}):")
    print(f"  {hashed}")
    print("\nAdd to your .env file:")
    print(f"  DASHBOARD_PASSWORD={hashed}")


def rotate_key_command(args):
    """Rotate an API key."""
    current_token = args.current
    grace_days = args.grace_days

    # Generate new token
    new_token = generate_api_token(prefix="torque", length=32)

    print("API Key Rotation")
    print("=" * 60)
    print(f"Current token: {current_token[:20]}...")
    print(f"New token:     {new_token}")
    print(f"\nGrace period:  {grace_days} days")
    print("\nSteps to complete rotation:")
    print("1. Update your .env file with the new token:")
    print(f"   TORQUE_API_TOKEN={new_token}")
    print("\n2. Restart VoltTracker to load the new token")
    print("\n3. Update Torque Pro URL to:")
    print(f"   http://your-server:8080/torque/upload/{new_token}")
    print(f"\n4. Test that telemetry uploads work with the new token")
    print(f"\n5. The old token will remain valid for {grace_days} days")
    print("\nIMPORTANT: Keep the old token until Torque Pro is updated!")


def generate_secret_key_command(args):
    """Generate a new Flask SECRET_KEY."""
    key = generate_secret_key(length=32)
    print("Generated Flask SECRET_KEY:")
    print(f"  {key}")
    print("\nAdd to your .env file:")
    print(f"  SECRET_KEY={key}")
    print("\n⚠️  WARNING: Changing SECRET_KEY will invalidate existing sessions!")
    print("Only change this if you understand the implications.")


def list_env_vars_command(args):
    """Show current authentication configuration from environment."""
    print("Current Authentication Configuration")
    print("=" * 60)

    vars_to_check = {
        "TORQUE_API_TOKEN": "Torque Pro API authentication",
        "WEBSOCKET_TOKEN": "WebSocket authentication token",
        "DASHBOARD_PASSWORD": "Dashboard password",
        "SECRET_KEY": "Flask secret key",
        "WEBSOCKET_AUTH_ENABLED": "WebSocket auth enabled",
        "RATE_LIMIT_ENABLED": "Rate limiting enabled",
    }

    for var, description in vars_to_check.items():
        value = os.environ.get(var)
        if value:
            # Mask sensitive values
            if var in ["TORQUE_API_TOKEN", "WEBSOCKET_TOKEN", "DASHBOARD_PASSWORD", "SECRET_KEY"]:
                display_value = f"{value[:10]}..." if len(value) > 10 else "***"
            else:
                display_value = value
            print(f"✓ {var:25s} = {display_value:20s} # {description}")
        else:
            print(f"✗ {var:25s} = NOT SET                  # {description}")


def main():
    """Main CLI entry point."""
    parser = argparse.ArgumentParser(
        description="VoltTracker Authentication Management",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Generate a new Torque API token
  python scripts/manage_auth.py generate-token --type torque

  # Generate a WebSocket authentication token
  python scripts/manage_auth.py generate-token --type websocket

  # Generate a Flask SECRET_KEY
  python scripts/manage_auth.py generate-secret-key

  # Hash a password for DASHBOARD_PASSWORD
  python scripts/manage_auth.py hash-password mypassword123

  # Rotate the Torque API token
  python scripts/manage_auth.py rotate-key --current torque_abc123...

  # Show current configuration
  python scripts/manage_auth.py list-config
        """
    )

    subparsers = parser.add_subparsers(dest="command", help="Command to execute")

    # Generate token command
    token_parser = subparsers.add_parser("generate-token", help="Generate a new authentication token")
    token_parser.add_argument(
        "--type",
        choices=["torque", "websocket", "secret"],
        required=True,
        help="Type of token to generate"
    )
    token_parser.set_defaults(func=generate_token_command)

    # Hash password command
    hash_parser = subparsers.add_parser("hash-password", help="Hash a password for secure storage")
    hash_parser.add_argument("password", help="Password to hash")
    hash_parser.add_argument(
        "--method",
        default="pbkdf2:sha256",
        choices=["pbkdf2:sha256", "scrypt:32768:8:1"],
        help="Hashing method"
    )
    hash_parser.set_defaults(func=hash_password_command)

    # Rotate key command
    rotate_parser = subparsers.add_parser("rotate-key", help="Rotate an API key")
    rotate_parser.add_argument("--current", required=True, help="Current API token")
    rotate_parser.add_argument("--grace-days", type=int, default=7, help="Days to keep old key valid")
    rotate_parser.set_defaults(func=rotate_key_command)

    # Generate secret key command
    secret_parser = subparsers.add_parser("generate-secret-key", help="Generate a Flask SECRET_KEY")
    secret_parser.set_defaults(func=generate_secret_key_command)

    # List config command
    list_parser = subparsers.add_parser("list-config", help="Show current authentication configuration")
    list_parser.set_defaults(func=list_env_vars_command)

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        sys.exit(1)

    args.func(args)


if __name__ == "__main__":
    main()
