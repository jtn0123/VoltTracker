"""add session_id to charging_sessions

Revision ID: a1b2c3d4e5f6
Revises: 8fddfd9e0ab8
Create Date: 2026-04-09 14:30:00.000000

Adds a nullable ``session_id`` (vehicle/Torque session UUID) column to
``charging_sessions`` so the charging-curve reconstruction query can scope
its TelemetryRaw lookup by session and avoid interleaving readings from
different vehicles. Existing rows are left NULL — the application code
falls back to the legacy time-window-only filter when ``session_id`` is
unset, so this migration is safe to apply against a populated database.
"""
from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'a1b2c3d4e5f6'
down_revision: str | Sequence[str] | None = '8fddfd9e0ab8'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Upgrade schema."""
    # CHAR(36) is the GUID() representation on non-PostgreSQL backends
    # (matches models.py GUID TypeDecorator). PostgreSQL will see this as
    # a 36-character VARCHAR which is fine — it does not require the
    # native UUID type for this column.
    op.add_column(
        'charging_sessions',
        sa.Column('session_id', sa.CHAR(length=36), nullable=True),
    )
    op.create_index(
        'ix_charging_sessions_session_id',
        'charging_sessions',
        ['session_id'],
        unique=False,
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.drop_index('ix_charging_sessions_session_id', table_name='charging_sessions')
    op.drop_column('charging_sessions', 'session_id')
