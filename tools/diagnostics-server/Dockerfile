FROM python:3.12-slim

WORKDIR /app
COPY server.py /app/server.py

ENV DIAGNOSTICS_UPLOAD_DIR=/data/uploads
EXPOSE 8080

CMD ["python", "/app/server.py"]
