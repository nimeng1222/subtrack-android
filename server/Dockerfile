FROM python:3.12-slim
WORKDIR /app
COPY app.py templates.json /app/
RUN useradd --uid 10001 --create-home subtrack && mkdir /data && chown subtrack /data
USER 10001
ENV SUBTRACK_DATA=/data/sync.sqlite SUBTRACK_BIND=0.0.0.0
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz',timeout=3)"
CMD ["python", "app.py"]
