FROM node:20.19-slim AS frontend-builder
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
# --ignore-scripts hardens the build by suppressing arbitrary postinstall
# scripts from transitive dependencies (sonar:S6505). Verified locally:
# vite/vitest/eslint/typescript don't need any install scripts, and the
# rolldown native binaries ship as precompiled binaries via npm
# optionalDependencies, not via postinstall.
RUN npm ci --ignore-scripts
COPY frontend/ .
RUN npm run build

FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code
COPY . .

# Copy built frontend assets from builder stage
COPY --from=frontend-builder /static/js/dist/ /app/static/js/dist/

# Create logs directory
RUN mkdir -p /app/logs

# Expose port
EXPOSE 8080

# R12: Copy migration runner and make executable
COPY run_migrations.sh /app/run_migrations.sh
RUN chmod +x /app/run_migrations.sh

ENTRYPOINT ["/app/run_migrations.sh"]

# Run with gunicorn + gevent for WebSocket support
# Note: Using 1 worker is intentional for this single-user application.
# gevent handles concurrency via greenlets internally. Multiple workers
# would break APScheduler (duplicate jobs) and WebSocket state sharing.
# If scaling to multiple users, switch to external scheduler (Celery Beat)
# and sticky sessions or a message broker for WebSocket.
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--worker-class", "geventwebsocket.gunicorn.workers.GeventWebSocketWorker", "--workers", "1", "--limit-request-line", "0", "app:app"]
