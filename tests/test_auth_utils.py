"""
Tests for authentication utilities.

Tests authentication functions including:
- API token generation
- WebSocket token generation
- API key hashing and verification
- APIKeyManager class
- Key rotation and expiration
"""

from datetime import datetime, timedelta, timezone
import pytest


class TestTokenGeneration:
    """Tests for token generation functions."""

    def test_generate_api_token_default(self):
        """Generate API token with default parameters."""
        from utils.auth_utils import generate_api_token

        token = generate_api_token()

        assert token.startswith("vt_")
        assert len(token) > 3
        # Default length is 32 bytes = 64 hex chars + 3 for "vt_"
        assert len(token) == 67

    def test_generate_api_token_custom_prefix(self):
        """Generate API token with custom prefix."""
        from utils.auth_utils import generate_api_token

        token = generate_api_token(prefix="test")

        assert token.startswith("test_")
        assert len(token) > 5

    def test_generate_api_token_custom_length(self):
        """Generate API token with custom length."""
        from utils.auth_utils import generate_api_token

        token = generate_api_token(length=16)

        assert token.startswith("vt_")
        # 16 bytes = 32 hex chars + 3 for "vt_"
        assert len(token) == 35

    def test_generate_api_token_uniqueness(self):
        """Generated tokens should be unique."""
        from utils.auth_utils import generate_api_token

        tokens = [generate_api_token() for _ in range(10)]

        assert len(tokens) == len(set(tokens))

    def test_generate_websocket_token_default(self):
        """Generate WebSocket token with default parameters."""
        from utils.auth_utils import generate_websocket_token

        token = generate_websocket_token()

        # 32 bytes = 64 hex chars
        assert len(token) == 64
        assert all(c in "0123456789abcdef" for c in token)

    def test_generate_websocket_token_custom_length(self):
        """Generate WebSocket token with custom length."""
        from utils.auth_utils import generate_websocket_token

        token = generate_websocket_token(length=16)

        # 16 bytes = 32 hex chars
        assert len(token) == 32

    def test_generate_secret_key_default(self):
        """Generate Flask secret key with default parameters."""
        from utils.auth_utils import generate_secret_key

        key = generate_secret_key()

        # 32 bytes = 64 hex chars
        assert len(key) == 64
        assert all(c in "0123456789abcdef" for c in key)

    def test_generate_secret_key_custom_length(self):
        """Generate Flask secret key with custom length."""
        from utils.auth_utils import generate_secret_key

        key = generate_secret_key(length=64)

        # 64 bytes = 128 hex chars
        assert len(key) == 128


class TestAPIKeyHashing:
    """Tests for API key hashing and verification."""

    def test_hash_api_key_basic(self):
        """Hash an API key."""
        from utils.auth_utils import hash_api_key

        api_key = "my-secret-key"
        hashed = hash_api_key(api_key)

        assert hashed != api_key
        assert hashed.startswith("pbkdf2:sha256:")
        assert len(hashed) > 50

    def test_hash_api_key_different_inputs_produce_different_hashes(self):
        """Different keys produce different hashes."""
        from utils.auth_utils import hash_api_key

        hash1 = hash_api_key("key1")
        hash2 = hash_api_key("key2")

        assert hash1 != hash2

    def test_hash_api_key_same_input_produces_different_salts(self):
        """Same key hashed twice produces different hashes (due to salt)."""
        from utils.auth_utils import hash_api_key

        hash1 = hash_api_key("same-key")
        hash2 = hash_api_key("same-key")

        # Different salts mean different hashes
        assert hash1 != hash2

    def test_verify_api_key_correct(self):
        """Verify correct API key."""
        from utils.auth_utils import hash_api_key, verify_api_key

        api_key = "correct-key"
        hashed = hash_api_key(api_key)

        assert verify_api_key(api_key, hashed) is True

    def test_verify_api_key_incorrect(self):
        """Verify incorrect API key."""
        from utils.auth_utils import hash_api_key, verify_api_key

        api_key = "correct-key"
        hashed = hash_api_key(api_key)

        assert verify_api_key("wrong-key", hashed) is False

    def test_verify_api_key_empty_string(self):
        """Verify empty string as key."""
        from utils.auth_utils import hash_api_key, verify_api_key

        hashed = hash_api_key("real-key")

        assert verify_api_key("", hashed) is False


class TestAPIKeyManager:
    """Tests for APIKeyManager class."""

    def test_init_empty(self):
        """Initialize empty APIKeyManager."""
        from utils.auth_utils import APIKeyManager

        manager = APIKeyManager()

        assert len(manager.keys) == 0

    def test_add_key_basic(self):
        """Add a key to manager."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        manager.add_key("key1", key_hash, description="Test key")

        assert "key1" in manager.keys
        assert manager.keys["key1"]["hash"] == key_hash
        assert manager.keys["key1"]["description"] == "Test key"
        assert manager.keys["key1"]["last_used"] is None

    def test_add_key_with_expiry(self):
        """Add a key with expiration date."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")
        expires_at = datetime.now(timezone.utc) + timedelta(days=30)

        manager.add_key("key1", key_hash, expires_at=expires_at)

        assert manager.keys["key1"]["expires_at"] == expires_at

    def test_add_key_with_created_at(self):
        """Add a key with custom created_at date."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")
        created_at = datetime.now(timezone.utc) - timedelta(days=5)

        manager.add_key("key1", key_hash, created_at=created_at)

        assert manager.keys["key1"]["created_at"] == created_at

    def test_verify_key_valid(self):
        """Verify valid API key."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        api_key = "test-key-123"
        key_hash = hash_api_key(api_key)

        manager.add_key("key1", key_hash)

        assert manager.verify_key("key1", api_key) is True
        assert manager.keys["key1"]["last_used"] is not None

    def test_verify_key_invalid(self):
        """Verify invalid API key."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        api_key = "test-key-123"
        key_hash = hash_api_key(api_key)

        manager.add_key("key1", key_hash)

        assert manager.verify_key("key1", "wrong-key") is False

    def test_verify_key_nonexistent(self):
        """Verify key that doesn't exist."""
        from utils.auth_utils import APIKeyManager

        manager = APIKeyManager()

        assert manager.verify_key("nonexistent", "any-key") is False

    def test_verify_key_expired(self):
        """Verify expired API key."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        api_key = "test-key-123"
        key_hash = hash_api_key(api_key)
        expires_at = datetime.now(timezone.utc) - timedelta(days=1)

        manager.add_key("key1", key_hash, expires_at=expires_at)

        assert manager.verify_key("key1", api_key) is False

    def test_verify_key_updates_last_used(self):
        """Verify key updates last_used timestamp."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        api_key = "test-key-123"
        key_hash = hash_api_key(api_key)

        manager.add_key("key1", key_hash)
        assert manager.keys["key1"]["last_used"] is None

        manager.verify_key("key1", api_key)
        assert manager.keys["key1"]["last_used"] is not None

    def test_rotate_key_basic(self):
        """Rotate an API key."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        old_key = "old-key-123"
        old_hash = hash_api_key(old_key)

        manager.add_key("old_key", old_hash)

        new_key_id, new_api_key = manager.rotate_key("old_key")

        # New key should exist
        assert new_key_id in manager.keys
        assert new_key_id.startswith("old_key_rotated_")

        # Old key should still exist but have expiry set
        assert "old_key" in manager.keys
        assert manager.keys["old_key"]["expires_at"] is not None

        # New key should be verifiable
        assert manager.verify_key(new_key_id, new_api_key) is True

    def test_rotate_key_custom_grace_period(self):
        """Rotate key with custom grace period."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        old_key = "old-key-123"
        old_hash = hash_api_key(old_key)

        manager.add_key("old_key", old_hash)

        grace_days = 14
        new_key_id, new_api_key = manager.rotate_key("old_key", grace_period_days=grace_days)

        # Old key should expire in 14 days
        expires_at = manager.keys["old_key"]["expires_at"]
        expected_expiry = datetime.now(timezone.utc) + timedelta(days=grace_days)

        # Allow 1 second tolerance
        assert abs((expires_at - expected_expiry).total_seconds()) < 1

    def test_rotate_key_nonexistent(self):
        """Rotate nonexistent key raises ValueError."""
        from utils.auth_utils import APIKeyManager

        manager = APIKeyManager()

        with pytest.raises(ValueError, match="not found"):
            manager.rotate_key("nonexistent")

    def test_revoke_key_basic(self):
        """Revoke an API key."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        manager.add_key("key1", key_hash)
        assert "key1" in manager.keys

        result = manager.revoke_key("key1")

        assert result is True
        assert "key1" not in manager.keys

    def test_revoke_key_nonexistent(self):
        """Revoke nonexistent key returns False."""
        from utils.auth_utils import APIKeyManager

        manager = APIKeyManager()

        result = manager.revoke_key("nonexistent")

        assert result is False

    def test_list_keys_empty(self):
        """List keys when empty."""
        from utils.auth_utils import APIKeyManager

        manager = APIKeyManager()

        keys = manager.list_keys()

        assert keys == []

    def test_list_keys_with_data(self):
        """List keys with data."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        manager.add_key("key1", key_hash, description="Test key 1")
        manager.add_key("key2", key_hash, description="Test key 2")

        keys = manager.list_keys()

        assert len(keys) == 2
        assert all("key_id" in k for k in keys)
        assert all("created_at" in k for k in keys)
        assert all("description" in k for k in keys)
        assert all("is_expired" in k for k in keys)
        # Hash should not be in the list
        assert all("hash" not in k for k in keys)

    def test_list_keys_shows_expired_status(self):
        """List keys shows correct expired status."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        # Add expired key
        expired_date = datetime.now(timezone.utc) - timedelta(days=1)
        manager.add_key("expired_key", key_hash, expires_at=expired_date)

        # Add valid key
        future_date = datetime.now(timezone.utc) + timedelta(days=1)
        manager.add_key("valid_key", key_hash, expires_at=future_date)

        # Add key with no expiry
        manager.add_key("no_expiry_key", key_hash)

        keys = manager.list_keys()
        keys_dict = {k["key_id"]: k for k in keys}

        assert keys_dict["expired_key"]["is_expired"] is True
        assert keys_dict["valid_key"]["is_expired"] is False
        assert keys_dict["no_expiry_key"]["is_expired"] is False

    def test_cleanup_expired_keys_removes_expired(self):
        """Cleanup removes expired keys."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        # Add expired keys
        expired_date = datetime.now(timezone.utc) - timedelta(days=1)
        manager.add_key("expired1", key_hash, expires_at=expired_date)
        manager.add_key("expired2", key_hash, expires_at=expired_date)

        # Add valid key
        future_date = datetime.now(timezone.utc) + timedelta(days=1)
        manager.add_key("valid", key_hash, expires_at=future_date)

        # Add key with no expiry
        manager.add_key("no_expiry", key_hash)

        count = manager.cleanup_expired_keys()

        assert count == 2
        assert "expired1" not in manager.keys
        assert "expired2" not in manager.keys
        assert "valid" in manager.keys
        assert "no_expiry" in manager.keys

    def test_cleanup_expired_keys_no_expired(self):
        """Cleanup with no expired keys."""
        from utils.auth_utils import APIKeyManager, hash_api_key

        manager = APIKeyManager()
        key_hash = hash_api_key("test-key")

        future_date = datetime.now(timezone.utc) + timedelta(days=1)
        manager.add_key("valid", key_hash, expires_at=future_date)

        count = manager.cleanup_expired_keys()

        assert count == 0
        assert "valid" in manager.keys


class TestGlobalAPIKeyManager:
    """Tests for global API key manager singleton."""

    def test_get_api_key_manager_returns_instance(self):
        """Get global API key manager."""
        from utils.auth_utils import get_api_key_manager

        manager = get_api_key_manager()

        assert manager is not None

    def test_get_api_key_manager_singleton(self):
        """Global manager is a singleton."""
        from utils.auth_utils import get_api_key_manager

        manager1 = get_api_key_manager()
        manager2 = get_api_key_manager()

        assert manager1 is manager2
