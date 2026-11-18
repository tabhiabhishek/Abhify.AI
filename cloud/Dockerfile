FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 abhify && mkdir -p /data && chown abhify /data
COPY app ./app
USER abhify
ENV ABHIFY_CLOUD_DATA_DIR=/data
EXPOSE 8100
# Plain HTTP inside the private Docker network only; Caddy terminates HTTPS in front of it.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100", "--proxy-headers", "--forwarded-allow-ips", "*"]
