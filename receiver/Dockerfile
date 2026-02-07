FROM node:20-slim AS frontend-builder
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
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

# Run with gunicorn + gevent for WebSocket support
# Note: Using 1 worker because gevent handles concurrency internally
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--worker-class", "geventwebsocket.gunicorn.workers.GeventWebSocketWorker", "--workers", "1", "--limit-request-line", "0", "app:app"]
