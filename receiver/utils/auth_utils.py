"""
Authentication utilities for VoltTracker.

Provides functions for:
- Generating secure API tokens
- Rotating API keys
- Hashing passwords
- Managing multiple API keys
"""

import hashlib
import secrets
import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple
from werkzeug.security import generate_password_hash, check_password_hash

logger = logging.getLogger(__name__)


def generate_api_token(prefix: str = "vt", length: int = 32) -> str:
    """
    Generate a secure random API token.

    Args:
        prefix: Token prefix for identification (default: "vt")
        length: Length of the random part in bytes (default: 32)

    Returns:
        Token string in format: prefix_<random_hex>

    Example:
        >>> token = generate_api_token()
        >>> print(token)
        vt_a1b2c3d4e5f6...
    """
    random_part = secrets.token_hex(length)
    return f"{prefix}_{random_part}"


def generate_websocket_token(length: int = 32) -> str:
    """
    Generate a secure WebSocket authentication token.

    Args:
        length: Length of the token in bytes (default: 32)

    Returns:
        Secure random token string

    Example:
        >>> token = generate_websocket_token()
        >>> print(token)
        a1b2c3d4e5f6...
    """
    return secrets.token_hex(length)


def hash_api_key(api_key: str, method: str = "pbkdf2:sha256") -> str:
    """
    Hash an API key for secure storage.

    Args:
        api_key: The plain text API key
        method: Hashing method (default: "pbkdf2:sha256")

    Returns:
        Hashed key string

    Example:
        >>> hashed = hash_api_key("my-secret-key")
        >>> print(hashed)
        pbkdf2:sha256:...
    """
    return generate_password_hash(api_key, method=method)


def verify_api_key(api_key: str, hashed_key: str) -> bool:
    """
    Verify an API key against its hash.

    Args:
        api_key: The plain text API key to verify
        hashed_key: The hashed key to check against

    Returns:
        True if the key matches, False otherwise
    """
    return check_password_hash(hashed_key, api_key)


class APIKeyManager:
    """
    Manage multiple API keys with rotation support.

    Features:
    - Store multiple active keys
    - Track key creation dates
    - Rotate keys safely (keep old keys valid during rotation)
    - Revoke specific keys
    """

    def __init__(self):
        """Initialize the API key manager."""
        self.keys: Dict[str, Dict[str, any]] = {}

    def add_key(self, key_id: str, key_hash: str, created_at: Optional[datetime] = None,
                expires_at: Optional[datetime] = None, description: str = "") -> None:
        """
        Add a new API key.

        Args:
            key_id: Unique identifier for the key
            key_hash: Hashed key value
            created_at: When the key was created (default: now)
            expires_at: When the key expires (default: None = no expiry)
            description: Human-readable description
        """
        self.keys[key_id] = {
            "hash": key_hash,
            "created_at": created_at or datetime.utcnow(),
            "expires_at": expires_at,
            "description": description,
            "last_used": None,
        }
        logger.info(f"Added API key: {key_id} ({description})")

    def verify_key(self, key_id: str, api_key: str) -> bool:
        """
        Verify an API key.

        Args:
            key_id: The key identifier
            api_key: The plain text key to verify

        Returns:
            True if valid and not expired, False otherwise
        """
        if key_id not in self.keys:
            return False

        key_info = self.keys[key_id]

        # Check expiry
        if key_info["expires_at"] and datetime.utcnow() > key_info["expires_at"]:
            logger.warning(f"Expired API key used: {key_id}")
            return False

        # Verify hash
        if verify_api_key(api_key, key_info["hash"]):
            self.keys[key_id]["last_used"] = datetime.utcnow()
            return True

        return False

    def rotate_key(self, old_key_id: str, grace_period_days: int = 7) -> Tuple[str, str]:
        """
        Rotate an API key by creating a new one and scheduling old key for deletion.

        Args:
            old_key_id: The key to rotate
            grace_period_days: Days to keep old key valid (default: 7)

        Returns:
            Tuple of (new_key_id, new_api_key)

        Example:
            >>> manager = APIKeyManager()
            >>> new_id, new_key = manager.rotate_key("old_key_1")
            >>> print(f"New key: {new_key}")
        """
        if old_key_id not in self.keys:
            raise ValueError(f"Key {old_key_id} not found")

        # Generate new key
        new_key_id = f"{old_key_id}_rotated_{datetime.utcnow().strftime('%Y%m%d')}"
        new_api_key = generate_api_token()
        new_hash = hash_api_key(new_api_key)

        # Add new key
        self.add_key(
            new_key_id,
            new_hash,
            description=f"Rotated from {old_key_id}"
        )

        # Schedule old key for expiry
        self.keys[old_key_id]["expires_at"] = datetime.utcnow() + timedelta(days=grace_period_days)
        logger.info(f"Rotated key {old_key_id} -> {new_key_id} (grace period: {grace_period_days} days)")

        return new_key_id, new_api_key

    def revoke_key(self, key_id: str) -> bool:
        """
        Immediately revoke an API key.

        Args:
            key_id: The key to revoke

        Returns:
            True if key was revoked, False if not found
        """
        if key_id in self.keys:
            del self.keys[key_id]
            logger.info(f"Revoked API key: {key_id}")
            return True
        return False

    def list_keys(self) -> List[Dict[str, any]]:
        """
        List all API keys with metadata (excluding hashes).

        Returns:
            List of key metadata dicts
        """
        result = []
        for key_id, info in self.keys.items():
            result.append({
                "key_id": key_id,
                "created_at": info["created_at"],
                "expires_at": info["expires_at"],
                "last_used": info["last_used"],
                "description": info["description"],
                "is_expired": info["expires_at"] and datetime.utcnow() > info["expires_at"] if info["expires_at"] else False,
            })
        return result

    def cleanup_expired_keys(self) -> int:
        """
        Remove all expired keys.

        Returns:
            Number of keys removed
        """
        now = datetime.utcnow()
        expired = [
            key_id for key_id, info in self.keys.items()
            if info["expires_at"] and now > info["expires_at"]
        ]

        for key_id in expired:
            del self.keys[key_id]
            logger.info(f"Cleaned up expired key: {key_id}")

        return len(expired)


def generate_secret_key(length: int = 32) -> str:
    """
    Generate a secure Flask SECRET_KEY.

    Args:
        length: Length in bytes (default: 32)

    Returns:
        Hex-encoded random secret key
    """
    return secrets.token_hex(length)


# Singleton instance for global use
_global_key_manager: Optional[APIKeyManager] = None


def get_api_key_manager() -> APIKeyManager:
    """Get the global API key manager instance."""
    global _global_key_manager
    if _global_key_manager is None:
        _global_key_manager = APIKeyManager()
    return _global_key_manager
