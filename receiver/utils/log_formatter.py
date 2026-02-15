"""
Structured JSON log formatter for VoltTracker.

Enable via environment variable: LOG_FORMAT=json

When enabled, all log output is formatted as single-line JSON objects
suitable for ingestion by log aggregators (ELK, Datadog, CloudWatch, etc.).

When not enabled (default), standard text logging is used unchanged.
"""

import json
import logging
import os
import traceback
from datetime import datetime, timezone


class JSONLogFormatter(logging.Formatter):
    """
    Format log records as single-line JSON objects.

    Output fields:
        timestamp: ISO 8601 UTC timestamp
        level: Log level name (INFO, WARNING, ERROR, etc.)
        logger: Logger name
        message: Log message
        module: Source module
        function: Source function name
        line: Source line number
        exc_info: Exception traceback (if present)
        extra: Any extra fields passed via logging kwargs
    """

    def format(self, record: logging.LogRecord) -> str:
        log_entry = {
            "timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "module": record.module,
            "function": record.funcName,
            "line": record.lineno,
        }

        if record.exc_info and record.exc_info[0] is not None:
            log_entry["exc_info"] = traceback.format_exception(*record.exc_info)

        # Include extra fields (skip standard LogRecord attributes)
        _standard = logging.LogRecord("", 0, "", 0, "", (), None).__dict__.keys()
        for key, value in record.__dict__.items():
            if key not in _standard and key not in ("message", "asctime"):
                try:
                    json.dumps(value)  # Test serializability
                    log_entry[key] = value
                except (TypeError, ValueError):
                    log_entry[key] = str(value)

        return json.dumps(log_entry, default=str)


def get_log_formatter() -> logging.Formatter:
    """
    Return the appropriate log formatter based on LOG_FORMAT env var.

    Returns:
        JSONLogFormatter if LOG_FORMAT=json, otherwise standard text formatter.
    """
    if os.environ.get("LOG_FORMAT", "").lower() == "json":
        return JSONLogFormatter()
    return logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
