# Spendly PDF parser. Build context: ./pdf-parser (contracts are NOT copied in; they are mounted at /contracts).
FROM ghcr.io/astral-sh/uv:0.12.21 AS uv

FROM python:3.12.13-slim-bookworm AS build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
# dependencies first (cached layer), then the project itself
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12.13-slim-bookworm
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin spendly
COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    SPENDLY_CONTRACTS_DIR=/contracts \
    SPENDLY_HMAC_SECRET_PATH=/run/secrets/hmac.key \
    SPENDLY_UPLOAD_DIR=/data/uploads \
    KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
    PDF_PARSER_PORT=8090
WORKDIR /app
USER spendly
EXPOSE 8090
# SIGTERM is handled by uvicorn -> graceful shutdown of the HTTP server and the Kafka worker
STOPSIGNAL SIGTERM
CMD ["spendly-pdf-parser"]
