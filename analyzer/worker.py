"""
DocuClarity LLM Analysis Worker.

Nasłuchuje strumienia Redis `docuclarity.analysis.requested`,
pobiera wyekstrahowany tekst z MinIO, wysyła do LLM (Gemma 4 via Openrouter.ai),
zapisuje wynik do MinIO i publikuje completion event.
"""
import time
from datetime import datetime, timezone

import json

import os

import logging
import redis
from minio import Minio
from minio.error import S3Error

from analyzer import LlmClient
from analyzer.chunker import chunk_page
from analyzer.quote_verifier import verify_quotes

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
log = logging.getLogger("worker")

# Configuration
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))

MINIO_ENDPOINT = os.getenv("MINIO_ENDPOINT", "localhost:9000")
MINIO_ACCESS_KEY = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
MINIO_SECRET_KEY = os.getenv("MINIO_SECRET_KEY", "minioadmin")
MINIO_BUCKET = os.getenv("MINIO_BUCKET", "docuclarity")
MINIO_SECURE = os.getenv("MINIO_SECURE", "false").lower() == "true"

REQUEST_STREAM = os.getenv("REQUEST_STREAM", "docuclarity.analysis.requested")
COMPLETE_STREAM = os.getenv("COMPLETE_STREAM", "docuclarity.analysis.completed")
CONSUMER_GROUP = os.getenv("CONSUMER_GROUP", "docuclarity-analyzer")
CONSUMER_NAME = os.getenv("CONSUMER_NAME", "analyzer-1")
POLL_INTERVAL_MS = int(os.getenv("POLL_INTERVAL_MS", "2000"))

def get_redis_client():
    return redis.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)

def get_minio_client():
    return Minio(
        MINIO_ENDPOINT,
        access_key=MINIO_ACCESS_KEY,
        secret_key=MINIO_SECRET_KEY,
        secure=MINIO_SECURE
    )

def ensure_consumer_group(r):
    try:
        r.xgroup_create(REQUEST_STREAM, CONSUMER_GROUP, id="0", mkstream=True)
        log.info("Created consumer group %s", CONSUMER_GROUP)
    except redis.ResponseError as e:
        if "BUSYGROUP" in str(e):
            log.debug("Consumer group %s already exists", CONSUMER_GROUP)
        else:
            raise

def read_text_from_minio(client, object_key):
    """Pobiera wyekstrahowany tekst z MinIO"""
    try:
        response = client.get_object(MINIO_BUCKET, object_key)
        data = response.read()
        response.close()
        response.release_conn()
        return data.decode("utf-8")
    except S3Error as e:
        log.error("MinIO error reading %s: %s", object_key, e)
        return None
    except Exception as e:
        log.error("Unexpected error reading %s: %s", object_key, e)
        return None

def extract_text_from_json(payload):
    """Wyciąga tekst z DocumentResultSummary JSON."""
    try:
        data = json.loads(payload)
        pages = data.get("pages", [])
        texts = []

        for page in pages:
            text = page.get("text", "")
            if text:
                texts.append(text)

        return "\n\n".join(texts)
    except json.JSONDecodeError:
        return payload

def write_analysis_to_minio(client, document_id, result):
    """Zapisuje wynik analizy do MinIO"""
    object_key = f"documents/{document_id}/analysis.json"
    payload = json.dumps(result, ensure_ascii=False)
    payload_bytes = payload.encode("utf-8")
    client.put_object(
        MINIO_BUCKET,
        object_key,
        data=payload_bytes,
        length=len(payload_bytes),
        content_type="application/json"
    )

    return object_key

def publish_completion(r, document_id, status, storage_key=None, model=None, error_message=None):
    publish_analysis_completed(
        r, document_id, status, model=model, error_message=error_message
    )

def publish_success(r, document_id, analysis_result, model, storage_key):
    publish_analysis_completed(
        r, document_id, "ANALYZED",
        plain_text=analysis_result.get("plainText", ""),
        summary=analysis_result.get("summary", {}),
        pitfalls=analysis_result.get("pitfalls", []),
        model=model,
        log_message="Published success for document %s",
        log_args=(document_id,),
    )

def publish_analysis_completed(
        r,
        document_id,
        status,
        *,
        plain_text=None,
        summary=None,
        pitfalls=None,
        model=None,
        error_message=None,
        log_message="Published completion for document %s: %s",
        log_args=None,
):
    """Publikuje zdarzenie ANALYSIS_COMPLETED do Redis Stream."""
    payload = json.dumps({
        "documentId": document_id,
        "status": status,
        "plainText": plain_text,
        "summary": summary,
        "pitfalls": pitfalls,
        "model": model,
        "errorMessage": error_message,
        "completedAt": datetime.now(timezone.utc).isoformat(),
    })
    entry = {
        "eventType": "ANALYSIS_COMPLETED",
        "documentId": document_id,
        "payload": payload,
    }
    r.xadd(COMPLETE_STREAM, entry)
    log.info(log_message, *(log_args or (document_id, status)))


def process_analysis(r, minio, llm, msg_id, fields):
    """Przetwarza pojedyncze żądanie analizy."""
    try:
        payload_str = fields.get("payload")
        if not payload_str:
            log.warning("Message %s without payload, skipping", msg_id)
            return

        request = json.loads(payload_str)
        document_id = request["documentId"]
        storage_key = request["extractedTextStorageKey"]

        log.info("Analyzing document %s (storage=%s)", document_id, storage_key)

        # 1. Pobranie tekstu z MinIO
        payload = read_text_from_minio(minio, storage_key)
        if payload is None:
            publish_completion(
                r, document_id, "ANALYSIS_FAILED",
                error_message=f"Could not read {storage_key} from MinIO"
            )
            return

        text = extract_text_from_json(payload)
        if not text.strip():
            publish_completion(
                r, document_id, "ANALYSIS_FAILED",
                error_message="Extracted text is empty"
            )
            return

        # Chunk if needed
        chunks = chunk_page(text, page_num=1)
        if not chunks:
            publish_completion(r, document_id, "ANALYSIS_FAILED",
                            error_message="No text to analyze after chunking")
            return

        # Analyze each chunk, merge results
        combined_result = {"plainText": [], "summary": {}, "pitfalls": []}
        for chunk in chunks:
            result = llm.analyze(chunk.text)
            if result is None:
                publish_completion(
                    r, document_id, "ANALYSIS_FAILED",
                    error_message="LLM analysis returned None"
                )
                return

            combined_result["plainText"].append(result.get("plainText", ""))
            combined_result["pitfalls"].extend(result.get("pitfalls", []))
            if result.get("summary"):
                combined_result["summary"] = result["summary"]

        combined_result["plainText"] = "\n\n".join(combined_result["plainText"])
        combined_result = verify_quotes(combined_result, text)

        # 2. Wywołanie LLM
        result = llm.analyze(text)
        if result is None:
            publish_completion(
                r, document_id, "ANALYSIS_FAILED",
                error_message="LLM analysis returned None"
            )
            return

        # 3. Zapis do MinIO
        storage_key_result = write_analysis_to_minio(minio, document_id, result)

        # 4. Publikacja sukcesu z pełnym wynikiem
        publish_success(r, document_id, result, llm.model, storage_key_result)

        log.info("Analysis completed for document %s", document_id)

    except Exception as e:
        log.error("Error processing analysis %s: %s", msg_id, e, exc_info=True)
        try:
            request = json.loads(fields.get("payload", "{}"))
            publish_completion(
                r, request.get("documentId", "unknown"), "ANALYSIS_FAILED", error_message=str(e)
            )
        except Exception:
            log.error("Failed to publish error completion event")

def main():
    log.info("Starting DocuClarity LLM analysis worker")
    log.info("Redis: %s:%s, MinIO: %s", REDIS_HOST, REDIS_PORT, MINIO_ENDPOINT)

    r = get_redis_client()
    minio = get_minio_client()
    llm = LlmClient()

    # Ping Redis
    try:
        r.ping()
        log.info("Redis connection OK")
    except Exception as e:
        log.error("Cannot connect to Redis: %s", e)

    ensure_consumer_group(r)

    log.info("Listening on stream '%s' as '%s'", REQUEST_STREAM, CONSUMER_NAME)
    log.info("LLM model: %s", llm.model)
    log.info("Worker ready")

    while True:
        try:
            messages = r.xreadgroup(
                CONSUMER_GROUP,
                CONSUMER_NAME,
                {REQUEST_STREAM: ">"},
                count=1,
                block=POLL_INTERVAL_MS
            )

            if not messages:
                continue

            for _stream, entries in messages:
                for msg_id, fields in entries:
                    process_analysis(r, minio, llm, msg_id, fields)
                    r.xack(REQUEST_STREAM, CONSUMER_GROUP, msg_id)
        except redis.ConnectionError as e:
            log.error("Redis connection error: %s", e)
            time.sleep(5)
        except Exception as e:
            log.error("Unexpected error in main loop: %s", e, exc_info=True)
            time.sleep(5)

if __name__ == "__main__":
    main()
