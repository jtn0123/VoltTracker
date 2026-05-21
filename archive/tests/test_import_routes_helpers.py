"""
Direct tests for the helpers PR #45 extracted from
``receiver/routes/import_routes.py`` to drop ``import_csv()``'s
cognitive complexity. The existing ``test_import_hardening.py`` only
hits the helpers transitively via the public ``/api/import/csv``
endpoint and doesn't reach the file-too-large branch, the
existing-trip branch, or the backup-IO error branch — those were the
last 30 uncovered lines on this PR per SonarCloud.
"""

import time
from unittest.mock import MagicMock, patch

from routes.import_routes import (
    _backup_csv,
    _check_file_size,
    _handle_existing_trip,
    _handle_no_records,
)


class TestCheckFileSize:
    def test_returns_none_for_small_file(self):
        # Real Config.MAX_CSV_FILE_SIZE is multiple MB, so 100 bytes is well
        # below the limit.
        err, msg = _check_file_size(b"x" * 100, "tiny.csv")
        assert err is None
        assert msg is None

    def test_returns_error_for_oversized_file(self):
        # Patch Config.MAX_CSV_FILE_SIZE to a tiny ceiling so we don't have to
        # allocate megabytes. _check_file_size does `from config import
        # Config as AppConfig` *inside* the function, so we patch the
        # attribute on the underlying class — that's what the function-local
        # import resolves to.
        from config import Config as AppConfig
        original_limit = AppConfig.MAX_CSV_FILE_SIZE
        try:
            AppConfig.MAX_CSV_FILE_SIZE = 100
            err, msg = _check_file_size(b"x" * 500, "huge.csv")
        finally:
            AppConfig.MAX_CSV_FILE_SIZE = original_limit

        assert err == "file_too_large"
        assert "MB" in msg


class TestBackupCsv:
    def test_writes_csv_content_to_backup_dir(self, tmp_path):
        with patch("routes.import_routes.CSV_BACKUP_DIR", tmp_path):
            _backup_csv("col1,col2\n1,2\n", "IMP-ABC123", "data.csv")
        # The backup file is named with a timestamp prefix; verify SOMETHING
        # was written to the patched dir.
        files = list(tmp_path.iterdir())
        assert len(files) == 1
        # Filename ends with the sanitized original filename.
        assert files[0].name.endswith("_data.csv")
        # Contents preserved verbatim.
        assert files[0].read_text() == "col1,col2\n1,2\n"

    def test_strips_unsafe_filename_chars(self, tmp_path):
        with patch("routes.import_routes.CSV_BACKUP_DIR", tmp_path):
            _backup_csv("body", "IMP-ABC", "evil/../path with spaces.csv")
        files = list(tmp_path.iterdir())
        assert len(files) == 1
        # No slashes, no spaces — only [a-zA-Z0-9._-] kept.
        name = files[0].name
        assert "/" not in name
        assert " " not in name

    def test_swallows_io_errors_silently(self):
        # Point CSV_BACKUP_DIR at a path that can't be created.
        with patch("routes.import_routes.CSV_BACKUP_DIR") as mock_dir:
            mock_dir.mkdir.side_effect = PermissionError("nope")
            # Should NOT raise — backup is best-effort.
            _backup_csv("body", "IMP-ABC", "data.csv")


class TestHandleExistingTrip:
    def test_returns_success_response_with_existing_trip_id(self, app):
        # `app` fixture from conftest.py provides Flask app context — needed
        # because _handle_existing_trip ultimately calls jsonify().
        existing_trip = MagicMock(id=42)
        stats = {"duplicates_removed": 0, "parsed_rows": 10}
        import_event = {"_start_time": time.time(), "first_error": None}

        with app.app_context(), \
             patch("routes.import_routes.record_import") as mock_record, \
             patch("routes.import_routes.build_import_response") as mock_build:
            mock_build.return_value = ({"status": "success", "trip_id": 42}, 200)
            _, code = _handle_existing_trip(
                MagicMock(), "IMP-ABC", import_event, stats, [], 10,
                existing_trip, "session-uuid", "data.csv", "hash", 1024,
            )
            mock_record.assert_called_once()
            mock_build.assert_called_once()

        assert code == 200
        assert import_event["trip_id"] == 42
        assert import_event["session_id"] == "session-uuid"
        assert import_event["success"] is True

    def test_marks_status_partial_when_duplicates_removed(self, app):
        existing_trip = MagicMock(id=99)
        stats = {"duplicates_removed": 5, "parsed_rows": 10}
        import_event = {"_start_time": time.time()}

        with app.app_context(), \
             patch("routes.import_routes.record_import") as mock_record, \
             patch("routes.import_routes.build_import_response") as mock_build:
            mock_build.return_value = ({}, 200)
            _handle_existing_trip(
                MagicMock(), "IMP-XYZ", import_event, stats, [], 10,
                existing_trip, "session-uuid-2", "data.csv", "hash", 1024,
            )

        # record_import is called with positional args
        # (db, import_code, status, ...), so the status is at index 2.
        assert mock_record.call_args.args[2] == "partial"


class TestHandleNoRecords:
    def test_uses_all_duplicates_reason_when_dedup_removed_rows(self):
        stats = {"duplicates_removed": 5}
        import_event = {"_start_time": time.time()}

        with patch("routes.import_routes._fail_import") as mock_fail:
            mock_fail.return_value = ({}, 400)
            _handle_no_records(MagicMock(), "IMP-A", import_event, stats,
                               "data.csv", "hash", 1024)

        # _fail_import called with reason="all_duplicates"
        assert mock_fail.call_args.args[3] == "all_duplicates"
        # message contains the all-duplicates phrasing
        assert "All records already imported" in mock_fail.call_args.args[4]

    def test_uses_no_valid_rows_reason_when_no_dedup(self):
        # No "failure_reason" key at all → .get() falls back to "no_valid_rows".
        # (Setting failure_reason=None in the dict would NOT trigger the
        # default; that's a Python dict.get gotcha worth pinning in a test.)
        stats = {"duplicates_removed": 0, "columns_detected": []}
        import_event = {"_start_time": time.time()}

        with patch("routes.import_routes._fail_import") as mock_fail, \
             patch("routes.import_routes.get_failure_suggestion", return_value="hint"):
            mock_fail.return_value = ({}, 400)
            _handle_no_records(MagicMock(), "IMP-B", import_event, stats,
                               "data.csv", "hash", 1024)

        assert mock_fail.call_args.args[3] == "no_valid_rows"

    def test_uses_parser_failure_reason_when_provided(self):
        stats = {"duplicates_removed": 0, "failure_reason": "missing_timestamp_column",
                 "columns_detected": ["foo", "bar"]}
        import_event = {"_start_time": time.time()}

        with patch("routes.import_routes._fail_import") as mock_fail, \
             patch("routes.import_routes.get_failure_suggestion", return_value="hint"):
            mock_fail.return_value = ({}, 400)
            _handle_no_records(MagicMock(), "IMP-C", import_event, stats,
                               "data.csv", "hash", 1024)

        assert mock_fail.call_args.args[3] == "missing_timestamp_column"
