Oto zaktualizowany `project_summary.md` odzwierciedlający aktualny stan projektu — Etapy 1–6 ukończone, Etap 7 (Analiza LLM) w trakcie implementacji:

```markdown
# DocuClarity — karta projektu

## 1. Cel projektu

Zbudowanie platformy do przetwarzania i analizy polskich dokumentów urzędowych (głównie PDF), która:

- przyjmuje dokumenty przez REST API (pisma urzędowe, umowy, decyzje),
- wyciąga tekst z PDF-ów (PDFBox dla warstwy tekstowej, Tess4J OCR dla skanów),
- automatycznie analizuje wyekstrahowany tekst przez LLM (Gemma 2 27B via OpenRouter.ai),
- upraszcza język urzędniczy do zrozumiałego polskiego,
- generuje jedno-stronicowe podsumowanie dokumentu,
- wykrywa ukryte kruczki prawne (klauzule niekorzystne, automatyczne przedłużenia, rażące kary umowne),
- zapewnia wgląd w status zadania na bieżąco (SSE),
- umożliwia pobranie wszystkich wyników.

Odpowiedź na pytanie „dlaczego to robimy": wielostronicowe umowy, pisma urzędowe i decyzje administracyjne są pisane zawiłym językiem prawno-urzędniczym, który utrudnia zrozumienie istotnych zobowiązań. Najważniejsze informacje (kto, co, za ile, na jak długo, jakie kruczki) można zmieścić na jednej stronie — DocuClarity robi to automatycznie, zwracając użytkownikowi: uproszczoną wersję, kluczowe punkty i listę ryzykownych klauzul.

## 2. Problem i wartość

| Problem | Rozwiązanie w DocuClarity |
|---|---|
| Wielostronicowe pisma urzędowe, nieczytelny język | LLM (Gemma 2 27B) upraszcza język urzędniczy do zrozumiałego polskiego |
| Trudno wyłuskać najważniejsze informacje z długiego dokumentu | Automatyczne 1-stronicowe podsumowanie (kto, co, za ile, na jak długo) |
| Ukryte kruczki prawne w umowach (klauzule rażące, automatyczne przedłużenia) | Wykrywanie i wyjaśnianie ryzykownych klauzul z cytatami z oryginału |
| PDF-y bez warstwy tekstowej (skany) | Kaskada ekstrakcji: PDFBox → OCR Tess4J |
| Długi czas przetwarzania blokuje użytkownika | Przetwarzanie asynchroniczne, statusy zadań, SSE |
| Trudno ocenić jakość ekstrakcji | Scoring per strona, flaga MANUAL_REVIEW |
| Brak śladu, skąd pochodzi wynik | Wyniki każdego etapu w MinIO + metadane |
| LLM halucynuje — zmyśla kruczki lub cytat | Weryfikacja cytatów (quote_verifier.py): EXACT → NORMALIZED → FUZZY + bramka liczbowa |

## 3. Zakres

**W zakresie**

- REST API: upload plików, status zadań, pobieranie wyników, strumień SSE, manualny trigger analizy.
- Asynchroniczne przetwarzanie zadań (kolejka na Redis Streams).
- Transactional outbox (spójność zapisu stanu i publikacji zdarzeń).
- Ekstrakcja tekstu z PDF z routingiem jakościowym per strona:
  - Apache PDFBox — dokumenty z warstwą tekstową.
  - Tess4J (Tesseract OCR) — skany i strony bez tekstu.
- **Analiza LLM** (Etap 7, automatyczna po ekstrakcji):
  - Uproszczenie języka urzędniczego do zrozumiałego polskiego.
  - 1-stronicowe podsumowanie kluczowych punktów.
  - Wykrywanie kruczków prawnych z cytatami i wyjaśnieniami.
  - Weryfikacja cytatów (anti-hallucination) — 3 warstwy + bramka liczbowa.
  - Chunking długich dokumentów dla modeli z ograniczonym oknem kontekstowym.
  - Naprawa zepsutego JSON z LLM (json-repair z schema guidance).
  - Retry analizy (3 próby) + scheduler retrigu + stuck recovery.
- Dostawca LLM: Gemma 2 27B przez OpenRouter.ai.
- Zapis wyników każdego etapu w MinIO z metadanymi.

**Poza zakresem (na ten moment)**

- Tłumaczenie międzyjęzykowe (polski → angielski) — świadomie zrezygnowano.
- Stirling-PDF — odłożone; ewentualnie później.
- LLM Vision do oceny stron OCR — Etap 8 (zarezerwowany).
- Panel administracyjny, zaawansowane uprawnienia, korekta/redakcja przez użytkownika.

## 4. Architektura docelowa

```
Klient
  |
  | REST (upload / status / SSE / analysis / download)
  v
Spring Boot (backend — kontener Podman)
  |-- PostgreSQL        (metadane, statusy zadań, outbox, statusy analizy LLM)
  |-- Redis Streams     (kolejka ekstrakcji + kolejka analizy LLM)
  |-- MinIO             (pliki źródłowe + wyniki ekstrakcji + wyniki analizy)
  |
  v
Worker Java            -- ekstrakcja: PDFBox → Tess4J → zapis result.json
  |                        auto-trigger analizy LLM po COMPLETED
  v
Worker Python          -- analiza LLM (Gemma 2 27B via OpenRouter.ai)
  (analyzer/)             → chunking → LLM → quote verification → analysis.json
```

**Konteneryzacja:**
- Spring Boot + Tess4J w jednym kontenerze Podman (`Containerfile`).
- Worker Python w osobnym kontenerze (`analyzer/Dockerfile`).
- Komunikacja Java↔Python przez Redis Streams (asynchroniczna, rozłączna).
- Tesseract (eng+pol) instalowany w obrazie runtime przez apt.

## 5. Pipeline przetwarzania

```
PDF (pismo urzędowe / umowa / decyzja)
 |
 v
PDFBox (tekst per strona)
 |
 +-- tekst dobrej jakości ---------> zaakceptuj
 |
 +-- brak tekstu / słaba jakość
 v
Renderowanie strony + Tess4J (Tesseract, ~300 DPI)
 |
 +-- confidence wysokie -----------> zaakceptuj
 +-- confidence niskie / trudny layout → MANUAL_REVIEW
 |
 v
[Zapis documents/{id}/result.json w MinIO]
 |
 v
[AUTOMATYCZNY TRIGGER] Analiza LLM (Etap 7)
 |
 v
AnalysisService → Redis Stream: docuclarity.analysis.requested
 |
 v
Worker Python (analyzer/)
  |-- chunker.py (jeśli dokument > 16k znaków → podział na chunki)
  |-- analyzer.py → LLM Gemma 2 27B (OpenRouter.ai)
  |     |-- response_format: json_object
  |     |-- json-repair[schema] (naprawa zepsutego JSON)
  |-- quote_verifier.py (weryfikacja cytatów: EXACT → NORMALIZED → FUZZY + bramka liczbowa)
  |-- Zapis analysis.json do MinIO
  |-- Publikacja completion do docuclarity.analysis.completed
 |
 v
AnalysisEventConsumer (Java)
  |-- DB: analysis_status = ANALYZED / ANALYSIS_FAILED
  |-- Retry (3 próby) → re-publish do Redis Stream
  |-- SSE: powiadomienie klienta
  |
 v
[Pobieranie wyników przez GET /api/documents/{id}/analysis]
```

**Kluczowe zasady:**
- Routing per strona, nie per dokument.
- LLM jest korektorem języka, nie twórcą — NIE dodaje informacji spoza dokumentu.
- Każdy kruczek zawiera cytat z oryginału (audytowalność).
- Cytaty są weryfikowane programowo (anti-hallucination).
- MANUAL_REVIEW gdy automat nie daje pewności.

**Format przechowywania w MinIO:**
```
documents/{documentId}/source                         ← oryginalny PDF
documents/{documentId}/pages/001/final.json           ← wynik ekstrakcji per strona
documents/{documentId}/result.json                    ← podsumowanie ekstrakcji
documents/{documentId}/analysis.json                  ← wynik LLM (plainText + summary + pitfalls)
```

## 6. Stos technologiczny

| Warstwa | Technologia |
|---|---|
| Backend | Spring Boot 4.1.0 (MVC, Validation) — Java 21 |
| Dane | Spring Data JDBC + PostgreSQL + Flyway |
| Kolejka | Redis Streams (Spring Data Redis) — 2 strumienie: ekstrakcja + analiza |
| Obiekty | MinIO |
| Ekstrakcja PDF | Apache PDFBox 3.0.8 |
| OCR | Tess4J 5.20.0 + natywny Tesseract 5.x + tessdata (eng, pol) |
| **Analiza LLM** | **Gemma 2 27B via OpenRouter.ai** (REST API, response_format: json_object) |
| **Naprawa JSON** | **json-repair[schema] 0.x** (schema-guided repair) |
| Real-time | SSE (Server-Sent Events) + Spring SseEmitter |
| Worker LLM | Python 3.11 + redis + minio + httpx + json-repair |
| Testy | Testcontainers + JUnit 5 + Mockito |
| Konteneryzacja | Podman + podman-compose |

## 7. Struktura projektu

```
rhan316-docuclarity/
├── Containerfile                                -- multi-stage: eclipse-temurin:21-jdk (build) + :21-jre (runtime z tesseract)
├── podman-compose.yml                           -- serwisy: postgres, minio, redis, docuclarity, analyzer
├── .containerignore
├── build.gradle
├── settings.gradle
│
├── analyzer/                                    -- [Etap 7] worker Python (analiza LLM)
│   ├── Dockerfile                               -- python:3.11-slim
│   ├── requirements.txt                         -- redis, minio, httpx, json-repair[schema]
│   ├── worker.py                                -- główna pętla: XREADGROUP → MinIO → LLM → MinIO → completion
│   ├── analyzer.py                              -- klient OpenRouter.ai, retry, parsowanie JSON (json-repair)
│   ├── prompts.py                               -- SYSTEM_PROMPT, build_user_prompt (instrukcje dla Gemma)
│   ├── schema.py                                -- ANALYSIS_SCHEMA (JSON Schema dla json-repair)
│   ├── quote_verifier.py                        -- weryfikacja cytatów: EXACT → NORMALIZED → FUZZY + bramka liczbowa
│   └── chunker.py                               -- podział długich stron (paragraph → word overlap)
│
├── gradle/wrapper/
│
└── src/
    ├── main/
    │   ├── java/org/dar316/docuclarity/
    │   │   ├── DocuClarityApplication.java
    │   │   ├── config/
    │   │   │   ├── JacksonConfig.java            -- kwalifikowany bean appObjectMapper (Jackson 2 + JavaTimeModule)
    │   │   │   ├── MinioConfig.java              -- MinioClient bean
    │   │   │   ├── MinioProperties.java          -- docuclarity.minio.*
    │   │   │   ├── QueueConfig.java              -- RedisTemplate, TaskExecutor, StreamListenerContainer (ekstrakcja + analiza)
    │   │   │   └── ResourceInitializer.java      -- CommandLineRunner: bucket MinIO
    │   │   ├── controller/
    │   │   │   ├── DocumentController.java       -- POST /upload, GET /{id}, GET /{id}/progress (SSE), POST /{id}/analyze, GET /{id}/analysis
    │   │   │   └── GlobalExceptionHandler.java   -- HTTP 400/404/503
    │   │   ├── dto/
    │   │   │   ├── AnalysisRequest.java          -- [Etap 7] documentId, extractedTextStorageKey, requestedAt
    │   │   │   ├── AnalysisResponse.java         -- [Etap 7] REST response: status, model, storageKey
    │   │   │   ├── AnalysisResult.java           -- [Etap 7] pełny wynik LLM (plainText, summary, pitfalls, model, status)
    │   │   │   ├── DocumentProgressEvent.java    -- SSE event payload
    │   │   │   ├── DocumentResultSummary.java    -- result.json (podsumowanie ekstrakcji)
    │   │   │   ├── DocumentStatusResponse.java   -- REST response: metadane + status
    │   │   │   ├── ExtractedPageResult.java      -- final.json (wynik per strona)
    │   │   │   ├── IndividualSummary.java        -- [Etap 7] podsumowanie (who, what, amount, duration, ...)
    │   │   │   ├── LegalPitfall.java             -- [Etap 7] kruczek (title, quote, explanation, severity, pageNumber)
    │   │   │   ├── OcrException.java
    │   │   │   ├── OcrPageResult.java
    │   │   │   ├── OcrWord.java
    │   │   │   ├── PageQualityScore.java
    │   │   │   ├── PdfPageText.java
    │   │   │   ├── PdfTextExtractionException.java
    │   │   │   ├── PdfTextExtractionResult.java
    │   │   │   ├── RoutingDecision.java
    │   │   │   └── UploadResponse.java
    │   │   ├── model/
    │   │   │   ├── AnalysisStatus.java           -- [Etap 7] enum: NOT_ANALYZED/ANALYSIS_QUEUED/ANALYZING/ANALYZED/ANALYSIS_FAILED
    │   │   │   ├── Document.java                 -- encja + pola analizy (analysis_status, analysis_model, analysis_attempts, ...)
    │   │   │   ├── DocumentStatus.java           -- enum: UPLOADED/PROCESSING/COMPLETED/FAILED/MANUAL_REVIEW
    │   │   │   └── OutboxEntry.java
    │   │   ├── repository/
    │   │   │   ├── DocumentRepository.java       -- CRUD + claimForProcessing + resetStuckProcessing + claimForAnalysis + resetStuckAnalysis + findStaleAnalysisQueued
    │   │   │   ├── EntityTimestampCallback.java
    │   │   │   └── OutboxRepository.java
    │   │   ├── service/
    │   │   │   ├── AnalysisEventConsumer.java    -- [Etap 7] konsument completion → DB + retry + SSE
    │   │   │   ├── AnalysisException.java        -- [Etap 7]
    │   │   │   ├── AnalysisService.java          -- [Etap 7] publikuje żądanie analizy do Redis Stream
    │   │   │   ├── DocumentNotFoundException.java
    │   │   │   ├── DocumentProcessingException.java
    │   │   │   ├── DocumentProcessingService.java -- worker ekstrakcji + auto-trigger analizy
    │   │   │   ├── DocumentProgressService.java  -- SSE: SseEmitter per dokument
    │   │   │   ├── DocumentService.java          -- upload: MinIO → DB → outbox → kompensacja
    │   │   │   ├── DocumentUploadException.java
    │   │   │   ├── MinioStorageException.java
    │   │   │   ├── MinioStorageService.java      -- upload/delete/download/uploadJson
    │   │   │   ├── OutboxPublisher.java          -- scheduler: PENDING → Redis Streams
    │   │   │   ├── PageQualityEvaluator.java     -- scoring jakości tekstu
    │   │   │   ├── PdfTextExtractionService.java
    │   │   │   ├── StreamConsumer.java           -- konsument Redis Streams (ekstrakcja)
    │   │   │   └── Tess4jOcrService.java
    │   │   └── util/
    │   │       ├── AnalysisRetryScheduler.java   -- [Etap 7] scheduler: re-publish stale ANALYSIS_QUEUED
    │   │       ├── DocumentProcessingServiceBuilder.java
    │   │       ├── StuckAnalysisRecovery.java    -- [Etap 7] reset ANALYZING → ANALYSIS_QUEUED na starcie
    │   │       └── StuckDocumentRecovery.java     -- reset PROCESSING → UPLOADED na starcie
    │   └── resources/
    │       ├── application.properties
    │       └── db/migration/
    │           ├── V1__documents_and_outbox.sql   -- tabele documents, outbox
    │           └── V2__analysis.sql               -- [Etap 7] kolumny analizy LLM
    │
    └── test/java/org/dar316/docuclarity/
        ├── DocuClarityApplicationTests.java
        ├── QueueIntegrationTest.java
        ├── TestcontainersConfiguration.java
        ├── TestDocuClarityApplication.java
        ├── controller/
        │   └── DocumentControllerTest.java
        └── service/
            ├── DocumentProcessingServiceTest.java  -- (z mockiem AnalysisService)
            ├── DocumentServiceTest.java
            ├── MinioStorageServiceTest.java
            ├── PageQualityEvaluatorTest.java
            ├── PdfTextExtractionServiceTest.java
            └── Tess4jOcrServiceTest.java
```

## 8. Etapy realizacji

| Etap | Rezultat | Status |
|---|---|---|
| 1. Fundament | Upload pliku, zapis do MinIO, zadanie w PostgreSQL, outbox | ukończony |
| 2. Ekstrakcja PDFBox | Tekst per strona + model wyniku, testy jednostkowe | ukończony |
| 3. Scoring strony | PageQualityEvaluator — metryki tekstu, decyzja routingu | ukończony |
| 4. OCR Tess4J | OCR stron bez tekstu, confidence per słowo | ukończony |
| 5. Kolejka i worker | Redis Streams, asynchroniczne przetwarzanie, retry | ukończony |
| 6. Statusy i SSE | Powiadamianie klienta o postępie zadania (SSE) | ukończony |
| **7. Analiza LLM** | **Worker Python + Gemma 2 27B, automatyczny trigger, weryfikacja cytatów, chunking, retry** | **w trakcie** |
| 8. LLM Vision | Selektywny fallback dla trudnych stron OCR | do zrobienia |
| 9. Pobieranie wyników | Endpointy download, wersjonowanie wyników | do zrobienia |

## 9. Kryteria sukcesu (KPI)

- **Pokrycie ekstrakcji**: % stron z użytecznym tekstem bez interwencji (cel: > 95% dla PDF z warstwą tekstową).
- **Skuteczność routingu**: % stron poprawnie skierowanych do OCR.
- **Czas przetwarzania**: mediana czasu od uploadu do gotowej analizy LLM.
- **Jakość podsumowania**: subiektywna ocena 1-5 na próbce 20 dokumentów.
- **Skuteczność wykrywania kruczków**: recall (% wykrytych ryzykownych klauzul).
- **Precyzja kruczków**: precision (% wykrytych kruczków, które są rzeczywiście ryzykowne).
- **Audytowalność**: 100% kruczków ma cytat z oryginału + pole `verification` (EXACT/NORMALIZED/FUZZY).
- **Odrzucenie halucynacji**: % kruczków odrzuconych przez quote_verifier (false positives LLM).
- **Latencja SSE**: czas od zdarzenia pipeline do dostarczenia do klienta (cel: < 100ms).
- **Koszt LLM**: średni koszt analizy na dokument (USD) — optymalizacja promptu i chunkingu.

## 10. Ryzyka i ograniczenia

| Ryzyko | Mitygacja |
|---|---|
| OCR blokuje zasoby (CPU-intensive) | Przetwarzanie w workerze, bounded ThreadPoolTaskExecutor (pool=4) |
| Tess4J wymaga bibliotek natywnych | Kontener z tesseract-ocr-eng + tesseract-ocr-pol przez apt |
| Duże PDF-y → pamięć przy 300 DPI | Limity rozmiaru (100MB), przetwarzanie strona po stronie |
| Wyścigi współbieżności | Atomowy UPDATE (claimForProcessing, claimForAnalysis) |
| Awaria JVM podczas przetwarzania | StuckDocumentRecovery + StuckAnalysisRecovery na starcie |
| Wycieki pamięci SSE | ConcurrentHashMap + CopyOnWriteArrayList + auto-cleanup |
| Niespójność Jackson 2 vs 3 | Kwalifikowany bean `appObjectMapper` |
| **LLM halucynuje** | **quote_verifier.py: 3 warstwy (EXACT → NORMALIZED → FUZZY) + bramka liczbowa** |
| **LLM zwraca zepsuty JSON** | **json-repair[schema] z schema-guided repair + response_format: json_object** |
| **Długi dokument nie mieści się w oknie LLM** | **chunker.py: podział po akapitach, fallback na słowa z overlap** |
| **LLM nie wykrywa wszystkich kruczków** | **Retry (3 próby) + scheduler retrigu + możliwość manualnego re-trigger** |
| **Koszty LLM na OpenRouter.ai** | **Token limits, chunking tylko gdy potrzebny, temperature 0.2** |
| **Dane wrażliwe na zewnętrznym API** | **OpenRouter.ai polityka prywatności; rozważ self-hosted dla enterprise** |
| **Timeout LLM przy dużym dokumencie** | **Chunking, timeout 300s, retry z backoff** |

## 11. Decyzje architektoniczne

1. Tess4J zamiast Stirling-PDF — prostszy pipeline.
2. Routing jakościowy per strona, nie per dokument.
3. LLM Vision selektywnie (Etap 8, zarezerwowany).
4. Ekstrakcja zwraca wynik per strona od pierwszej implementacji.
5. MANUAL_REVIEW jako pełnoprawny wynik.
6. Wyniki wszystkich etapów w MinIO — wersjonowane.
7. Cała aplikacja Spring Boot w jednym kontenerze Podman.
8. Kwalifikowany Jackson 2 (`appObjectMapper`) — izolacja od Jackson 3.
9. Atomowy claim przetwarzania i analizy.
10. podman-compose z 5 serwisami: postgres, minio, redis, docuclarity, analyzer.
11. SSE w pamięci (ConcurrentHashMap) — single-instance.
12. Wzorzec Builder dla DocumentProcessingService.
13. **Lokalne mikroserwisy** — Java (ekstrakcja) + Python (LLM) w osobnych kontenerach, komunikacja przez Redis Streams.
14. **LLM jest korektorem języka, nie korektorem OCR** — surowy tekst z ekstrakcji jest wejściem.
15. **Język polski wyjściowy** — LLM upraszcza polski do polskiego; tłumaczenie świadomie zrezygnowano.
16. **OpenRouter.ai jako provider** — łatwa podmiana modelu bez zmian w kodzie.
17. **Automatyczny trigger analizy** — po COMPLETED system publikuje żądanie analizy bez ingerencji użytkownika.
18. **Cytaty z oryginału + weryfikacja** — każdy kruczek ma `quote` (cytat) i `verification` (poziom dopasowania: EXACT/NORMALIZED/FUZZY).
19. **Warstwowa weryfikacja cytatów** — EXACT → NORMALIZED → FUZZY (próg 0.92) + bramka liczbowa (kwoty/daty muszą istnieć w źródle).
20. **json-repair[schema]** — schema-guided naprawa zepsutego JSON z LLM, fallback po standardowym `json.loads()`.
21. **Chunking konfigurowalny** — aktywowany tylko gdy dokument przekracza 16k znaków; strategia: akapity → słowa z overlap.
22. **3-warstwowa ochrona retrigu analizy**: (1) AnalysisEventConsumer re-publish natychmiast, (2) StuckAnalysisRecovery na starcie, (3) AnalysisRetryScheduler co 10s dla stale requests.
23. **Redis Streams zamiast WebSocket/gRPC** — asynchroniczna, rozłączna komunikacja; survives restart, consumer groups, PEL.
24. **Katalog `analyzer/` zamiast `python/`** — nazwa opisuje funkcję, nie język implementacji.

## 12. Konfiguracja (application.properties)

```properties
# --- PostgreSQL ---
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/docuclarity}
spring.datasource.username=${POSTGRES_USER:docuclarity}
spring.datasource.password=${POSTGRES_PASSWORD:docuclarity}
spring.datasource.driver-class-name=org.postgresql.Driver

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# --- MinIO ---
docuclarity.minio.endpoint=${MINIO_ENDPOINT:http://localhost:9000}
docuclarity.minio.access-key=${MINIO_ACCESS_KEY:minioadmin}
docuclarity.minio.secret-key=${MINIO_SECRET_KEY:minioadmin}
docuclarity.minio.bucket=${MINIO_BUCKET:docuclarity}

# --- OCR ---
docuclarity.ocr.render-dpi=300
docuclarity.ocr.tessdata-path=${TESSDATA_PREFIX:/usr/share/tessdata}
docuclarity.ocr.language=eng+pol
docuclarity.ocr.page-seg-mode=1

# --- Scoring jakości ---
docuclarity.quality.accept-threshold=0.85
docuclarity.quality.min-word-count=5
docuclarity.quality.ideal-word-count=20
docuclarity.quality.max-replacement-ratio=0.05

# --- Kolejka ekstrakcji (Etap 5) ---
docuclarity.queue.enabled=true
docuclarity.queue.stream-key=docuclarity.documents
docuclarity.queue.consumer-group=docuclarity-workers
docuclarity.queue.consumer-name=worker-1
docuclarity.queue.publish-interval-ms=1000
docuclarity.queue.poll-timeout-ms=2000
docuclarity.queue.worker-pool-size=4
docuclarity.queue.max-processing-attempts=3
docuclarity.queue.max-publish-attempts=5

# --- Redis ---
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.timeout=3000ms
spring.data.redis.connect-timeout=3000ms
spring.data.redis.read-timeout=3000ms

# --- SSE (Etap 6) ---
docuclarity.sse.timeout-ms=300000

# --- Analiza LLM (Etap 7) ---
docuclarity.analysis.auto-trigger=true
docuclarity.analysis.request-stream-key=docuclarity.analysis.requested
docuclarity.analysis.complete-stream-key=docuclarity.analysis.completed
docuclarity.analysis.consumer-group=docuclarity-analyzer
docuclarity.analysis.consumer-name=analyzer-1
docuclarity.analysis.poll-timeout-ms=2000
docuclarity.analysis.extracted-key-template=documents/%s/result.json
docuclarity.analysis.result-key-template=documents/%s/analysis.json
docuclarity.analysis.max-attempts=3
docuclarity.analysis.retry-interval-ms=10000
docuclarity.analysis.stale-threshold-seconds=60

# --- Upload ---
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
```

## 13. Uruchomienie

```bash
# Lokalne testy (wymaga tesseract):
TESSDATA_PATH=/usr/share/tessdata ./gradlew test

# Build obrazu Java:
podman build -t docuclarity .

# Uruchomienie pełnego stosu:
export OPENROUTER_API_KEY=sk-or-v1-xxxxx
podman-compose up

# Serwisy:
#   docuclarity       — Spring Boot (port 8080)
#   docuclarity-analyzer — Python worker (LLM)
#   docuclarity-redis    — Redis
#   docuclarity-postgres — PostgreSQL
#   docuclarity-minio    — MinIO (port 9000 API, 9001 console)
```

## 14. Etap 1 — Fundament (ukończony)

- Flyway V1: tabele `documents` + `outbox` z CHECK constraints.
- MinioConfig + MinioStorageService: upload/delete/download/uploadJson.
- Document + OutboxEntry: encje Persistable z @PersistenceCreator.
- DocumentService: orkiestracja upload (MinIO → TransactionTemplate DB → kompensacja).
- DocumentController: POST /upload, GET /{id}.
- GlobalExceptionHandler: HTTP 400/404/503.

## 15. Etap 2 — Ekstrakcja PDFBox (ukończony)

- PdfTextExtractionService: ekstrakcja strona po stronie przez PDFTextStripper (PDFBox 3.x).
- PdfPageText, PdfTextExtractionResult, PdfTextExtractionException.

## 16. Etap 3 — Scoring strony (ukończony)

- PageQualityEvaluator: scoring 0–1 (wagi: słowa 0.50, alpha 0.20, U+FFFD kara 0.20, długość słowa 0.10).
- RoutingDecision: PDFBOX / OCR_REQUIRED / LLM_REVIEW / MANUAL_REVIEW.
- PageQualityScore: metryki + score + warnings + decision.

## 17. Etap 4 — OCR Tess4J (ukończony)

- Tess4jOcrService: renderowanie PDF (300 DPI) → Tesseract OCR z per-word confidence.
- OcrWord, OcrPageResult, OcrException.
- Testy gated na TESSDATA_PATH.

## 18. Etap 5 — Kolejka i worker (ukończony)

- OutboxPublisher: scheduler PENDING → XADD Redis Streams → PUBLISHED.
- StreamConsumer: consumer group → TaskExecutor → DocumentProcessingService.
- DocumentProcessingService: claim → PDFBox → routing → OCR → zapis MinIO → COMPLETED/MANUAL_REVIEW.
- QueueConfig: RedisTemplate, ThreadPoolTaskExecutor, StreamMessageListenerContainer.
- StuckDocumentRecovery: reset PROCESSING → UPLOADED na starcie.
- Retry: processingAttempts + max-processing-attempts (3).

## 19. Etap 6 — Statusy i SSE (ukończony)

- DocumentProgressService: zarządza SseEmitter per dokument, broadcast progress.
- DocumentController.streamProgress: GET /{id}/progress (text/event-stream).
- DocumentProcessingService: instrumentacja pipeline z SSE events na każdym milestone.
- DocumentProcessingServiceBuilder: fluent builder z walidacją zależności.
- Auto-cleanup: onCompletion, onTimeout, onError → removeEmitter.

## 20. Audit i uodpornienie architektury (Etap 5+)

1. Bezpieczeństwo współbieżności: `claimForProcessing` (atomic UPDATE).
2. StuckDocumentRecovery: reset PROCESSING na ApplicationReadyEvent.
3. JacksonConfig: kwalifikowany `appObjectMapper` (Jackson 2 + JavaTimeModule).
4. UTF-8 byte count w MinioStorageService.uploadJson.
5. HTTP 201 + Location header w DocumentController.upload.
6. Redis resilience: BUSYGROUP handling.
7. Podman Compose: komplet serwisów.
8. Builder pattern z walidacją null.
9. Defensive coding: handleFailure z walidacją null.
10. SSE: warunek `documentProgressService != null` dla bezpieczeństwa testów.

## 21. Etap 7 — Analiza LLM (w trakcie)

**Cel:** automatyczna analiza wyekstrahowanego tekstu przez LLM (Gemma 2 27B via OpenRouter.ai):
1. Uproszczenie języka urzędniczego do zrozumiałego polskiego.
2. 1-stronicowe podsumowanie kluczowych punktów.
3. Wykrywanie kruczków prawnych z cytatami i wyjaśnieniami.

### Zaimplementowane komponenty (Java):

- **V2__analysis.sql**: kolumny `analysis_status`, `analysis_model`, `analysis_attempts`, `analysis_completed_at`, `analysis_error_message` + indeks.
- **AnalysisStatus** (enum): NOT_ANALYZED / ANALYSIS_QUEUED / ANALYZING / ANALYZED / ANALYSIS_FAILED.
- **Document.java**: rozszerzona o pola analizy (15 parametrów w @PersistenceCreator).
- **DocumentRepository**: `claimForAnalysis`, `resetStuckAnalysis`, `findStaleAnalysisQueued`.
- **AnalysisService**: publikuje AnalysisRequest do `docuclarity.analysis.requested` po COMPLETED. Waliduje status dokumentu. Auto-trigger + manual trigger (POST /{id}/analyze).
- **AnalysisEventConsumer**: konsument `docuclarity.analysis.completed`. Zapis wyniku do MinIO + DB update. Retry (3 próby) z re-publish. SSE: ANALYZED / ANALYSIS_RETRY_QUEUED / ANALYSIS_FAILED.
- **AnalysisRetryScheduler**: @Scheduled (co 10s) re-publish stale ANALYSIS_QUEUED (> 60s).
- **StuckAnalysisRecovery**: reset ANALYZING → ANALYSIS_QUEUED na ApplicationReadyEvent.
- **QueueConfig**: `analysisStreamContainer` bean (StreamMessageListenerContainer dla completion stream).
- **DocumentProcessingService**: auto-trigger `analysisService.requestAnalysis()` po COMPLETED.
- **DocumentController**: POST /{id}/analyze (manual trigger), GET /{id}/analysis (status + storageKey).
- **DTOs**: AnalysisRequest, AnalysisResult, AnalysisResponse, IndividualSummary, LegalPitfall.

### Zaimplementowane komponenty (Python — `analyzer/`):

- **worker.py**: główna pętla XREADGROUP na `docuclarity.analysis.requested`. Pobiera result.json z MinIO → wywołuje LLM → zapisuje analysis.json → publikuje completion.
- **analyzer.py**: klient OpenRouter.ai (httpx). `response_format: json_object`. Parsowanie: `json.loads()` → `json_repair.repair_json(schema=ANALYSIS_SCHEMA)` → `json_repair.loads()`.
- **prompts.py**: SYSTEM_PROMPT (rola, format JSON, reguły wykrywania kruczków, brak halucynacji) + `build_user_prompt()`.
- **schema.py**: ANALYSIS_SCHEMA (JSON Schema dla json-repair, additionalProperties: false).
- **quote_verifier.py**: warstwowa weryfikacja cytatów:
  - Layer 1: EXACT (dosłowny podciąg)
  - Layer 2: NORMALIZED (normalizacja: spacje, myślniki, cudzysłowy, interpunkcja)
  - Layer 3: FUZZY (SequenceMatcher, próg 0.92, tylko cytaty ≥ 25 znaków)
  - Bramka liczbowa: wszystkie liczby z cytatu muszą występować w źródle (kwoty, daty, procenty)
  - Normalizacja polskich miesięcy (marca → 03)
  - Pole `verification` w każdym kruczku (EXACT/NORMALIZED/FUZZY)
- **chunker.py**: podział długich stron PDF:
  - Per-page processing (krótka strona = 1 chunk)
  - Długa strona: podział po akapitach → fallback na słowa z overlap (30 słów)
  - Limit: 16 200 znaków (~5400 tokenów, Gemma 2 27B 8k kontekst)
- **Dockerfile**: python:3.11-slim.
- **requirements.txt**: redis, minio, httpx, json-repair[schema], python-dotenv.

### Zaimplementowane komponenty (infrastruktura):

- **podman-compose.yml**: dodano serwis `analyzer` (build: ./analyzer, env: REDIS_HOST, MINIO_*, OPENROUTER_API_KEY, LLM_MODEL, LLM_TIMEOUT_SECONDS, LLM_MAX_TOKENS, LLM_TEMPERATURE).
- **application.properties**: pełna konfiguracja analizy (stream keys, consumer group, retry, scheduler, templates).

### Znane błędy do naprawy:

1. **AnalysisService.java**: literówka w `@Value` — `docularity.analysis.requested` → `docuclarity.analysis.requested`.
2. **AnalysisRetryScheduler.java**: literówka w `@Value` — `docularity.analysis.stale-threshold-seconds` → `docuclarity.analysis.stale-threshold-seconds`.
3. **chunker.py**: syntax error — `word_chunks(para, max_chars)` zamiast `word_chunks = _split_by_words(para, max_chars)`.
4. **quote_verifier.py**: błędy składni (brak dwukropka, `in None` zamiast `is None`, urwana funkcja `verify_quotes`).
5. **LegalPitfall.java**: brak pola `verification` (Python dodaje je do pitfalli, Java musi je deserializować).
6. **analysisStreamContainer** w QueueConfig: nazwa beanu może kolidować z `streamListenerContainer` (Spring może wymagać `@Qualifier`).

### 3-warstwowa ochrona retrigu analizy:

| Warstwa | Komponent | Kiedy działa |
|---|---|---|
| 1. Re-publish natychmiastowy | AnalysisEventConsumer.handleAnalysisFailure | Python zwraca ANALYSIS_FAILED |
| 2. Reset na starcie | StuckAnalysisRecovery | Python crashnie, aplikacja się restartuje |
| 3. Scheduler retrigu | AnalysisRetryScheduler (co 10s) | Dokument utknął w ANALYSIS_QUEUED > 60s |

### Przepływ komunikacji Java↔Python:

```
Java (po ekstrakcji)              Redis Stream              Python (analyzer/)
─────────────────               ──────────────              ──────────────────
DocumentProcessingService
  → COMPLETED
  → AnalysisService
     .requestAnalysis()
     → DB: ANALYSIS_QUEUED
     → XADD ──────────────→ docuclarity.analysis.requested
                                   ↓
                              worker.py: XREADGROUP
                              → MinIO: result.json
                              → chunker.py (jeśli długi)
                              → analyzer.py → LLM (OpenRouter)
                              → json-repair (naprawa JSON)
                              → quote_verifier.py (weryfikacja)
                              → MinIO: analysis.json
                              → XADD ──────────────→ docuclarity.analysis.completed
                                                              ↓
AnalysisEventConsumer ←────────────────────────────── XREADGROUP
  → DB: ANALYZED / ANALYSIS_FAILED
  → SSE: ANALYZED / ANALYSIS_RETRY_QUEUED / ANALYSIS_FAILED
  → (jeśli retry) re-publish do requested stream
```

## 22. Aktualny stan testów

Raportowany stan po Etapach 1–6 (~89 testów, 0 błędów, 10 pominiętych):

| Etap | Testy | Status |
|---|---|---|
| 1 | DocumentServiceTest (15), MinioStorageServiceTest (8), DocumentControllerTest (9) | PASS |
| 2 | PdfTextExtractionServiceTest (12) | PASS |
| 3 | PageQualityEvaluatorTest (14) | PASS |
| 4 | Tess4jOcrServiceTest (10, 10 skipped — TESSDATA_PATH) | PASS |
| 5 | DocumentProcessingServiceTest (8), QueueIntegrationTest (2), DocuClarityApplicationTests (1) | PASS |
| 6 | SSE w DocumentControllerTest, DocumentProgressService | PASS |
| **Razem** | **~89 testów, 0 failures, 10 skipped** | |

**Etap 7 (planowane testy):**
- AnalysisServiceTest — testy publikacji żądań LLM
- AnalysisEventConsumerTest — testy obsługi completion + retry
- AnalysisRetrySchedulerTest — testy schedulera retrigu
- quote_verifier_test.py — testy weryfikacji cytatów (EXACT/NORMALIZED/FUZZY/bramka liczbowa)
- chunker_test.py — testy podziału długich stron
- analyzer_test.py — testy parsowania JSON (json-repair, schema guidance)

**Uwaga:** DocumentProcessingServiceTest został zaktualizowany — dodany mock `AnalysisService` do konstruktora (10 argumentów). DocumentProcessingServiceBuilder również zaktualizowany o `analysisService`.
```
