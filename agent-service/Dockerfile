FROM python:3.12-slim AS build
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends build-essential libpq-dev && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

FROM python:3.12-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
# libpq5 (runtime client lib) only — no compiler toolchain in the final image.
RUN apt-get update && apt-get install -y --no-install-recommends libpq5 && rm -rf /var/lib/apt/lists/*
COPY --from=build /install /usr/local
COPY app ./app
EXPOSE 8088
# Shell form so $PORT (injected by Koyeb/Render/Fly) expands at runtime.
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8088}
