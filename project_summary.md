

Oto zaktualizowany plik `project_summary.md` z naniesionymi zmianami zgodnie z Twoją wizją — Etap 7 to teraz **LLM Analysis** (uproszczenie polszczyzny urzędniczej, podsumowanie, wykrywanie kruczków prawnych), a nie tłumaczenie międzyjęzykowe.

```markdown
DocuClarity — karta projektu
1. Cel projektu
Zbudowanie platformy do przetwarzania i analizy polskich dokumentów urzędowych (głównie PDF), która:

- przyjmuje dokumenty przez REST API (pisma urzędowe, umowy, decyzje),
- wyciąga tekst z PDF-ów (PDFBox dla warstwy tekstowej, Tess4J OCR dla skanów),
- automatycznie analizuje wyekstrahowany tekst przez LLM (Gemma 4 via OpenRouter.ai),
- upraszcza język urzędniczy do zrozumiałego polskiego,
- generuje jedno-stronicowe podsumowanie dokumentu,
- wykrywa ukryte kruczki prawne (klauzule niekorzystne, automatyczne przedłużenia, rażące kary umowne),
- zapewnia wgląd w status zadania na bieżąco (SSE),
- umożliwia pobranie wszystkich wyników.

Odpowiedź na pytanie „dlaczego to robimy": wielostronicowe umowy, pisma urzędowe i decyzje administracyjne są pisane zawiłym językiem prawno-urzędniczym, który utrudnia zrozumienie istotnych zobowiązań. Najważniejsze informacje (kto, co, za ile, na jak długo, jakie kruczki) można zmieścić na jednej stronie — DocuClarity robi to automatycznie, zwracając użytkownikowi: uproszczoną wersję, kluczowe punkty i listę ryzykownych klauzul.

2. Problem i wartość

| Problem | Rozwiązanie w DocuClarity |
|---|---|
| Wielostronicowe pisma urzędowe, nieczytelny język | LLM (Gemma 4) upraszcza język urzędniczy do zrozumiałego polskiego |
| Trudno wyłuskać najważniejsze informacje z długiego dokumentu | Automatyczne 1-stronicowe podsumowanie (kto, co, za ile, na jak długo) |
| Ukryte kruczki prawne w umowach (klauzule rażące, automatyczne przedłużenia) | Wykrywanie i wyjaśnianie ryzykownych klauzul z cytatami |
| PDF-y bez warstwy tekstowej (skany) | Kaskada ekstrakcji: PDFBox → OCR Tess4J |
| Długi czas przetwarzania blokuje użytkownika | Przetwarzanie asynchroniczne, statusy zadań, SSE |
| Trudno ocenić jakość ekstrakcji | Scoring per strona, flaga MANUAL_REVIEW |
| Brak śladu, skąd pochodzi wynik | Wyniki każdego etapu w MinIO + metadane |

3. Zakres
**W zakresie**

- REST API: upload plików, status zadań, pobieranie wyników, strumień SSE.
- Asynchroniczne przetwarzanie zadań (kolejka na Redis Streams).
- Transactional outbox (spójność zapisu stanu i publikacji zdarzeń).
- Ekstrakcja tekstu z PDF z routingiem jakościowym per strona:
  - Apache PDFBox — dokumenty z warstwą tekstową.
  - Tess4J (Tesseract OCR) — skany i strony bez tekstu.
- **Analiza LLM** (Etap 7, automatyczna po ekstrakcji):
  - Uproszczenie języka urzędniczego do zrozumiałego polskiego.
  - 1-stronicowe podsumowanie kluczowych punktów.
  - Wykrywanie kruczków prawnych z cytatami i wyjaśnieniami.
- Dostawca LLM: Gemma 4 przez OpenRouter.ai.
- Zapis wyników każdego etapu w MinIO z metadanymi.

**Poza zakresem (na ten moment)**

- Tłumaczenie międzyjęzykowe (polski → angielski, itp.) — świadomie zrezygnowano, istnieje wiele dedykowanych narzędzi (Google Translate, DeepL).
- Stirling-PDF — odłożone; ewentualnie później jako searchable PDF / preprocessing.
- LLM Vision do oceny poszczególnych stron OCR — świadomie odrzucone.
- LLM jako korektor OCR — Etap 8 (LLM Vision) zarezerwowany do trudnych layoutów.
- Panel administracyjny, zaawansowane uprawnienia, korekta/redakcja przez użytkownika.

4. Architektura docelowa
```
Klient
  |
  | REST (upload / status / SSE / download)
  v
Spring Boot (backend — kontener Podman)
  |-- PostgreSQL        (metadane, statusy zadań, outbox)
  |-- Redis Streams     (kolejka zadań + kolejka LLM)
  |-- MinIO             (pliki źródłowe + wyniki etapów)
  |
  v
Worker Java          -- ekstrakcja: PDFBox -> Tess4J
  |
  v
Worker Python        -- analiza LLM (Gemma 4 via OpenRouter.ai)
                            -> uproszczenie + podsumowanie + kruczki
```

**Konteneryzacja:**
- Spring Boot + Tess4J w jednym kontenerze Podman.
- Worker Python w osobnym kontenerze (komunikacja przez Redis Streams).
- Tesseract (eng+pol) instalowany w obrazie runtime przez apt.

5. Pipeline przetwarzania (decyzje projektowe)
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
 |
 +-- confidence niskie / trudny layout
 v
LLM Vision (selektywnie, Etap 8 — zarezerwowane)
 |
 v
MANUAL_REVIEW (jeśli automat nie daje pewności)
 |
 v
[Zapis documents/{id}/result.json w MinIO]
 |
 v
[AUTOMATYCZNY TRIGGER] Analiza LLM (Etap 7)
 |
 v
Worker Python → LLM Gemma 4 (OpenRouter.ai)
 |
 +-- SYSTEM PROMPT: instrukcja dla LLM
      (rola, format odpowiedzi, reguły wykrywania kruczków)
 |
 +-- USER PROMPT: "<wyekstrahowany tekst dokumentu>"
 |
 v
LLM zwraca strukturyzowany JSON:
  - plainText (uproszczona wersja)
  - summary (1-stronicowe podsumowanie)
  - pitfalls (lista ukrytych kruczków z cytatami)
 |
 v
[Zapis documents/{id}/analysis.json w MinIO]
 |
 v
[Pobieranie wyników przez GET /api/documents/{id}/analysis]
```

**Kluczowe zasady:**

- Routing jest per strona, nie per dokument — strona 1 może trafić do PDFBox, strona 3 do OCR.
- textPresent == true z PDFBox nie oznacza dobrej jakości — potrzebny scoring.
- LLM nie nadpisuje OCR — zawsze na końcu pipeline, nie w środku.
- LLM jest korektorem języka, nie twórcą — NIE dodaje informacji, których nie ma w dokumencie.
- Wynik LLM musi być oznaczony cytatami z oryginału (dla audytowalności).
- MANUAL_REVIEW gdy automat nie daje pewności.

**Format przechowywania w MinIO:**
```
documents/{documentId}/pages/001/source.png
documents/{documentId}/pages/001/pdfbox.json
documents/{documentId}/pages/001/ocr.tsv
documents/{documentId}/pages/001/ocr.txt
documents/{documentId}/pages/001/llm.json
documents/{documentId}/pages/001/final.json
documents/{documentId}/result.json              ← podsumowanie ekstrakcji
documents/{documentId}/analysis.json            ← wynik LLM (plainText + summary + pitfalls)
```

6. Stos technologiczny

| Warstwa | Technologia |
|---|---|
| Backend | Spring Boot 4.1.0 (MVC, Validation, Actuator) — Java 21 |
| Dane | Spring Data JDBC + PostgreSQL + Flyway |
| Kolejka | Redis Streams (Spring Data Redis) |
| Obiekty | MinIO |
| Ekstrakcja PDF | Apache PDFBox 3.0.8 |
| OCR | Tess4J 5.20.0 + natywny Tesseract 5.5.0 + tessdata (eng, pol) — w kontenerze Podman |
| **Analiza LLM** | **Gemma 4 via OpenRouter.ai** (REST API) |
| Real-time | SSE (Server-Sent Events) + Spring SseEmitter |
| Worker LLM | Python 3.11 + requests + httpx |
| Testy | Testcontainers + JUnit 5 + Mockito |
| Konteneryzacja | Podman 6.1.0 + podman-compose 1.6.0 |

7. Struktura projektu

```
src/main/java/org/dar316/docuclarity/
  DocuClarityApplication.java          -- punkt wejścia Spring Boot
  config/
    JacksonConfig.java                 -- kwalifikowany bean appObjectMapper (Jackson 2 + JavaTimeModule)
    MinioConfig.java                   -- konfiguracja klienta MinioClient
    MinioProperties.java               -- właściwości MinIO (docuclarity.minio.*)
    QueueConfig.java                   -- RedisTemplate, TaskExecutor, DocumentProcessingService, StreamListenerContainer
    ResourceInitializer.java           -- CommandLineRunner: idempotentne tworzenie bucketu MinIO
  controller/
    DocumentController.java            -- REST: POST /upload, GET /{id}, GET /{id}/analysis, strumień SSE /{id}/progress
    GlobalExceptionHandler.java        -- mapowanie wyjątków na kody HTTP 400/404/503
  dto/
    DocumentProgressEvent.java         -- record: SSE event payload (status, stage, currentPage, totalPages)
    DocumentResultSummary.java         -- record: podsumowanie wyniku ekstrakcji (result.json)
    DocumentStatusResponse.java        -- record: odpowiedź ze statusem dokumentu
    ExtractedPageResult.java           -- record: wynik ekstrakcji per strona (final.json)
    OcrException.java                  -- wyjątek runtime OCR
    OcrPageResult.java                 -- record: wynik OCR per strona
    OcrWord.java                       -- record: słowo z confidence i bbox (OCR)
    PageQualityScore.java              -- record: wynik oceny jakości strony (metryki + score + decyzja)
    PdfPageText.java                   -- record: wynik ekstrakcji per strona (PDFBox)
    PdfTextExtractionException.java    -- wyjątek runtime ekstrakcji PDF
    PdfTextExtractionResult.java       -- record: wynik ekstrakcji per dokument (PDFBox)
    RoutingDecision.java               -- enum: PDFBOX/OCR_REQUIRED/LLM_REVIEW/MANUAL_REVIEW
    UploadResponse.java                -- record: odpowiedź po przesłaniu pliku
    AnalysisRequest.java               -- record: [Etap 7] żądanie analizy LLM (documentId, storageKey)
    AnalysisResult.java                -- record: [Etap 7] pełna odpowiedź LLM (plainText, summary, pitfalls)
    IndividualSummary.java             -- record: [Etap 7] jedno pole podsumowania (kto/co/za ile/...)
    LegalPitfall.java                  -- record: [Etap 7] wykryty kruczek prawny (title, quote, severity)
  model/
    Document.java                      -- encja Spring Data JDBC + pola analizy LLM
    DocumentStatus.java                -- enum statusów ekstrakcji (UPLOADED/PROCESSING/COMPLETED/FAILED/MANUAL_REVIEW)
    TranslationStatus.java             -- [Etap 7] enum statusów analizy (NOT_ANALYZED/ANALYSIS_QUEUED/ANALYZING/ANALYZED/ANALYSIS_FAILED)
  repository/
    DocumentRepository.java            -- CRUD + claimForProcessing() + resetStuckProcessing()
    EntityTimestampCallback.java       -- BeforeConvertCallback: automatyczne ustawianie created_at/updated_at
    OutboxRepository.java              -- CRUD + findPending()
  service/
    DocumentNotFoundException.java     -- wyjątek runtime braku dokumentu (HTTP 404)
    DocumentProcessingException.java   -- wyjątek runtime błędu workera
    DocumentProcessingService.java     -- worker: budowany przez Builder, emituje SSE events
    DocumentProgressService.java       -- serwis SSE: zarządza SseEmitter per dokument, emituje progress events
    DocumentService.java               -- upload: MinIO → transakcja DB (dokument + outbox) → kompensacja
    DocumentProcessingServiceBuilder.java  -- fluent builder dla DocumentProcessingService
    DocumentUploadException.java       -- wyjątek runtime błędu uploadu (HTTP 400)
    MinioStorageException.java         -- wyjątek runtime błędu MinIO (HTTP 503)
    MinioStorageService.java           -- operacje na plikach i JSON w MinIO (UTF-8 byte count)
    OutboxPublisher.java               -- scheduler: publikacja PENDING outbox na Redis Streams
    PageQualityEvaluator.java          -- scoring jakości tekstu PDFBox i routing PDFBOX vs OCR_REQUIRED
    PdfTextExtractionService.java      -- ekstrakcja warstwy tekstowej PDFBox
    StreamConsumer.java                -- konsument Redis Streams (group docuclarity-workers) → delegacja do TaskExecutora
    Tess4jOcrService.java              -- renderowanie strony PDF (300 DPI) + OCR Tess4J
    [Etap 7] AnalysisService.java      -- publikuje żądanie analizy LLM po ekstrakcji
    [Etap 7] AnalysisEventConsumer.java -- konsument completion events → DB update
    [Etap 7] AnalysisException.java    -- wyjątek runtime analizy
  util/
    DocumentProcessingServiceBuilder.java  -- fluent builder
    StuckDocumentRecovery.java         -- ApplicationReadyEvent: reset wiszących PROCESSING → UPLOADED

python/                                     -- [Etap 7] katalog workera Python
  Dockerfile                                -- python:3.11-slim + pip install
  requirements.txt                          -- requests, httpx, pydantic
  worker.py                                 -- główna pętla nasłuchująca Redis Stream
  analyzer.py                               -- klient LLM (OpenRouter.ai) i parser odpowiedzi
  prompts.py                                -- system/ user prompty dla Gemma 4

src/test/java/org/dar316/docuclarity/
  DocuClarityApplicationTests.java             -- test kontekstu Spring Boot
  QueueIntegrationTest.java                    -- test integracyjny Redis+Postgres+MinIO
  TestcontainersConfiguration.java             -- konfiguracja Testcontainers (PostgreSQL, Redis)
  TestDocuClarityApplication.java              -- punkt wejścia do testów
  controller/
    DocumentControllerTest.java                -- testy endpointów REST (MockMvc)
  service/
    DocumentProcessingServiceTest.java         -- testy jednostkowe workera (Mockito)
    DocumentServiceTest.java                   -- testy integracyjne serwisu uploadu
    MinioStorageServiceTest.java               -- testy operacji MinIO
    PageQualityEvaluatorTest.java              -- testy scoringu jakości strony
    PdfTextExtractionServiceTest.java          -- testy ekstrakcji PDFBox
    Tess4jOcrServiceTest.java                  -- testy OCR (gated na TESSDATA_PATH)
    [Etap 7] AnalysisServiceTest.java          -- testy publikacji żądań LLM

src/main/resources/
  application.properties                       -- konfiguracja DB, MinIO, OCR, Redis, LLM, SSE
  db/migration/
    V1__documents_and_outbox.sql               -- tabele documents i outbox
    V2__translation.sql                         -- [Etap 7] kolumny analizy LLM (translation_status, language, etc.)

Pliki kontenera:
  Containerfile                                -- multi-stage: eclipse-temurin:21-jdk (build) + :21-jre (runtime z tesseract)
  python/Dockerfile                            -- [Etap 7] python:3.11-slim + worker LLM
  .containerignore                             -- wykluczenia build contextu
  podman-compose.yml                           -- serwisy: postgres, minio, redis, docuclarity, analyzer
```

8. Etapy realizacji
Cele etapów sformułowane według zasady SMART — każdy ma mierzalny rezultat i kryterium zakończenia.

| Etap | Rezultat | Status |
|---|---|---|
| 1. Fundament | Upload pliku, zapis do MinIO, zadanie w PostgreSQL, outbox | ukończony |
| 2. Ekstrakcja PDFBox | Tekst per strona + model wyniku (PdfTextExtractionResult), testy jednostkowe | ukończony |
| 3. Scoring strony | PageQualityEvaluator — metryki tekstu, decyzja PDFBOX / wymagany OCR | ukończony |
| 4. OCR Tess4J | OCR stron bez tekstu, confidence per słowo, progi routingu | ukończony |
| 5. Kolejka i worker | Redis Streams, asynchroniczne przetwarzanie, timeouty, retry | ukończony |
| 6. Statusy i SSE | Powiadamianie klienta o postępie zadania | ukończony |
| **7. Analiza LLM (uproszczenie + podsumowanie + kruczki)** | **Worker Python + Gemma 4 via OpenRouter.ai, automatyczny trigger po ekstrakcji** | **do zrobienia** |
| 8. LLM Vision | Selektywny fallback dla trudnych stron OCR | do zrobienia |
| 9. Pobieranie wyników | Endpointy download, wersjonowanie wyników | do zrobienia |

9. Kryteria sukcesu (KPI)
Propozycje wskaźników — wartości docelowe do kalibracji na rzeczywistym zbiorze dokumentów (pisma urzędowe, umowy):

- **Pokrycie ekstrakcji**: % stron, dla których pipeline zwraca użyteczny tekst bez interwencji (cel: > 95% dla PDF z warstwą tekstową).
- **Skuteczność routingu**: % stron poprawnie skierowanych do OCR (mierzone na próbce testowej).
- **Czas przetwarzania**: mediana czasu od uploadu do gotowej analizy LLM dla typowego dokumentu.
- **Jakość podsumowania**: subiektywna ocena 1-5 na próbce 20 dokumentów (czy oddaje istotę).
- **Skuteczność wykrywania kruczków**: % faktycznych ryzykownych klauzul wykrytych przez LLM (recall); mierzone na próbce z adnotacjami eksperta.
- **Precyzja kruczków**: % wykrytych kruczków, które są rzeczywiście ryzykowne (precision).
- **Audytowalność**: 100% stron ma zapisany silnik ekstrakcji; 100% wykrytych kruczków ma cytat z oryginału.
- **Latencja SSE**: czas od zdarzenia pipeline do dostarczenia do subskrybowanego klienta (cel: < 100ms).
- **Koszt LLM**: średni koszt analizy na dokument (USD) — optymalizacja promptu.

10. Ryzyka i ograniczenia

| Ryzyko | Mitygacja |
|---|---|
| OCR jako operacja CPU-intensive blokuje zasoby | Przetwarzanie w workerze, bounded ThreadPoolTaskExecutor (pool=4) |
| Tess4J wymaga bibliotek natywnych i traineddata | Kontener z tesseract-ocr-eng + tesseract-ocr-pol przez apt; testy gated na TESSDATA_PATH |
| Duże PDF-y → pamięć przy renderowaniu 300 DPI | Limity rozmiaru (100MB), przetwarzanie strona po stronie |
| Confidence OCR ≠ poprawność | Łączenie z innymi metrykami (jakość obrazu, layout, heurystyki tekstu) |
| Zaszyfrowane/uszkodzone PDF-y | Obsługa wyjątków, status błędu zadania, MANUAL_REVIEW |
| Wyścigi współbieżności przy podwójnym dostarczeniu zdarzeń | Atomowy UPDATE (claimForProcessing) w DocumentRepository |
| Awaria JVM podczas przetwarzania | StuckDocumentRecovery resetujący wiszące PROCESSING |
| Wycieki pamięci w SSE | Map<UUID, List<SseEmitter>> z CopyOnWriteArrayList + auto-cleanup |
| Niespójność Jackson 2 vs Jackson 3 | Kwalifikowany bean `appObjectMapper` izoluje serializację domenową |
| **LLM halucynuje — dodaje informacje spoza dokumentu** | **Reguły w system prompt: "NIE dodawaj informacji, których nie ma w tekście"; fallback na pustą listę kruczków** |
| **LLM nie wykrywa wszystkich kruczków (false negatives)** | **Retrying z innym seed/temperature; ludzka weryfikacja dokumentów o wysokim ryzyku (umowy powyżej pewnej kwoty)** |
| **Koszty LLM na OpenRouter.ai** | **Token limits, monitoring, fallback na krótszy prompt przy dużych dokumentach (chunking)** |
| **Dane wrażliwe (umowy) na zewnętrznym API** | **OpenRouter.ai ma politykę prywatności; rozważ self-hosted LLM dla klientów enterprise** |
| **Timeout LLM przy dużym dokumencie** | **Worker Python: chunking dokumentu, łączenie wyników; timeout 300s** |

11. Decyzje architektoniczne podjęte do tej pory

1. Tess4J zamiast Stirling-PDF do podstawowego OCR — prostszy pipeline (PDFBox → Tess4J), bez dodatkowego kontenera.
2. Routing jakościowy per strona, nie per dokument.
3. LLM Vision selektywnie — tylko przy niskim confidence OCR, najlepiej jako korektor (nie samodzielny trzeci OCR).
4. Ekstrakcja zwraca wynik per strona (PdfPageText) od pierwszej implementacji — ułatwia routing bez refaktoringu.
5. MANUAL_REVIEW jako pełnoprawny wynik dla dokumentów niskiej pewności.
6. Wyniki wszystkich etapów w MinIO — nie nadpisujemy, wersjonujemy.
7. Cała aplikacja Spring Boot w jednym kontenerze Podman — Tess4J działa wewnątrz JVM przez JNA.
8. Kwalifikowany Jackson 2 (`appObjectMapper`) w `JacksonConfig` — izolacja serializacji MinIO/Outbox od Jackson 3 w WebMVC.
9. Atomowy claim przetwarzania — `claimForProcessing` eliminuje race condition.
10. podman-compose z kompletem serwisów: postgres, minio, redis, docuclarity + analyzer (Etap 7).
11. SSE w pamięci (ConcurrentHashMap) — single-instance, architektura izolowana w DocumentProgressService.
12. Wzorzec Builder dla DocumentProcessingService — izolacja złożoności konstrukcji 9 zależności.
13. **Lokalne mikroserwisy zamiast monolitu** — Java (ekstrakcja) + Python (LLM) w osobnych kontenerach, komunikacja przez Redis Streams.
14. **LLM (Gemma 4) jest korektorem języka, nie korektorem OCR** — surowy tekst z ekstrakcji jest wejściem, LLM produkuje plainText + summary + pitfalls, nie poprawia słów/glifów.
15. **Język polski wyjściowy** — LLM upraszcza polski do polskiego; tłumaczenie międzyjęzykowe świadomie zrezygnowano (istnieje wiele dedykowanych narzędzi).
16. **OpenRouter.ai jako provider LLM** — łatwa podmiana modelu (Gemma 4, Claude, GPT, lokalne modele) bez zmian w kodzie; jednolity API dla wielu modeli.
17. **Automatyczny trigger analizy LLM** — po zakończeniu ekstrakcji (status COMPLETED) system automatycznie publikuje żądanie analizy; nie wymaga manualnego POST od użytkownika.
18. **Cytaty z oryginału w wynikach kruczków** — każdy wykryty kruczek zawiera `quote` (fragment oryginalnego tekstu) i `pageNumber` — audytowalność i zaufanie do wyników.

12. Konfiguracja (application.properties)

```properties
# --- PostgreSQL ---
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/docuclarity}
spring.datasource.username=${POSTGRES_USER:docuclarity}
spring.datasource.password=${POSTGRES_PASSWORD:docuclarity}
spring.datasource.driver-class-name=org.postgresql.Driver

# --- Flyway ---
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# --- Spring Data JDBC ---
spring.jdbc.template.fetch-size=100

# --- MinIO ---
docuclarity.minio.endpoint=${MINIO_ENDPOINT:http://localhost:9000}
docuclarity.minio.access-key=${MINIO_ACCESS_KEY:minioadmin}
docuclarity.minio.secret-key=${MINIO_SECRET_KEY:minioadmin}
docuclarity.minio.bucket=${MINIO_BUCKET:docuclarity}

# --- OCR (Tess4J) ---
docuclarity.ocr.render-dpi=300
docuclarity.ocr.tessdata-path=${TESSDATA_PREFIX:/usr/share/tessdata}
docuclarity.ocr.language=eng+pol
docuclarity.ocr.page-seg-mode=1

# --- Scoring jakości strony (Etap 3) ---
docuclarity.quality.accept-threshold=0.85
docuclarity.quality.min-word-count=5
docuclarity.quality.ideal-word-count=20
docuclarity.quality.max-replacement-ratio=0.05

# --- Kolejka (Etap 5, Redis Streams) ---
docuclarity.queue.enabled=true
docuclarity.queue.stream-key=docuclarity.documents
docuclarity.queue.consumer-group=docuclarity-workers
docuclarity.queue.consumer-name=worker-1
docuclarity.queue.publish-interval-ms=1000
docuclarity.queue.poll-timeout-ms=2000
docuclarity.queue.worker-pool-size=4
docuclarity.queue.max-processing-attempts=3
docuclarity.queue.max-publish-attempts=5

# --- Redis (timeouty) ---
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.timeout=3000ms
spring.data.redis.connect-timeout=3000ms
spring.data.redis.read-timeout=3000ms

# --- SSE (Etap 6) ---
docuclarity.sse.timeout-ms=300000

# --- Analiza LLM (Etap 7) ---
# Auto-trigger analizy po zakończeniu ekstrakcji
docuclarity.analysis.auto-trigger=true
# Provider LLM (openrouter jako default)
docuclarity.analysis.llm.provider=openrouter
docuclarity.analysis.llm.api-url=https://openrouter.ai/api/v1/chat/completions
docuclarity.analysis.llm.api-key=${OPENROUTER_API_KEY}
docuclarity.analysis.llm.model=google/gemma-4-27b-it
docuclarity.analysis.llm.timeout-seconds=300
docuclarity.analysis.llm.max-tokens=4096
docuclarity.analysis.llm.temperature=0.2
# Strumienie Redis
docuclarity.analysis.request-stream-key=docuclarity.analysis.requested
docuclarity.analysis.complete-stream-key=docuclarity.analysis.completed
docuclarity.analysis.consumer-group=docuclarity-analyzer
docuclarity.analysis.consumer-name=analyzer-1
docuclarity.analysis.poll-timeout-ms=2000
# Klucze MinIO
docuclarity.analysis.extracted-key-template=documents/%s/result.json
docuclarity.analysis.result-key-template=documents/%s/analysis.json

# --- Upload ---
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB
```

13. Uruchomienie

```bash
# Lokalne testy (wymaga tesseract w systemie):
TESSDATA_PATH=/usr/share/tessdata ./gradlew test

# Lokalne testy (bez OCR — testy OCR pomijane):
./gradlew test

# Build obrazu kontenera:
podman build -t docuclarity .

# Uruchomienie pełnego stosu przez podman-compose:
export OPENROUTER_API_KEY=sk-or-v1-xxxxx
podman-compose up

# Test OCR w kontenerze:
podman run --rm --entrypoint tesseract docuclarity --list-langs

# Test LLM (Python worker):
podman exec docuclarity-analyzer python -c "from analyzer import LlmClient; print(LlmClient('gemma-4').ping())"
```

**Uwagi:**

- Sekcje KPI i progi jakości (0.85, 0.05 itd.) to wartości robocze — wymagają kalibracji.
- Aplikacja startuje w pełni dzięki podman-compose (postgres + redis + minio + docuclarity + analyzer).
- `OPENROUTER_API_KEY` jest wymagany w runtime (do kontenera `analyzer`).
- Projekt nie jest jeszcze zainicjalizowany w git.

14. Etap 1 — Fundament (ukończony)

Zaimplementowane komponenty:
- Flyway migracja V1: tabele `documents` (statusy: UPLOADED, PROCESSING, COMPLETED, FAILED, MANUAL_REVIEW) i `outbox` (statusy: PENDING, PUBLISHED, FAILED) z CHECK constraints.
- MinioProperties + MinioConfig: konfiguracja klienta MinioClient.
- MinioStorageService: upload/delete plików, idempotentna inicjalizacja bucketu.
- Document + OutboxEntry: encje Spring Data JDBC implementujące Persistable.
- EntityTimestampCallback: automatyczne ustawianie created_at/updated_at.
- DocumentService: orkiestracja upload (MinIO → TransactionTemplate DB → kompensacja).
- DocumentController: POST /api/documents/upload (201 Created + Location header), GET /api/documents/{id}.
- GlobalExceptionHandler: mapowanie wyjątków na HTTP 400/404/503.

15. Etap 2 — Ekstrakcja PDFBox (ukończony)

Cel: ekstrakcja warstwy tekstowej z plików PDF strona po stronie (PDFBox 3.0.8).

Zaimplementowane komponenty:
- PdfPageText (record): wynik ekstrakcji per strona.
- PdfTextExtractionResult (record): wynik zbiorczy per dokument.
- PdfTextExtractionException (RuntimeException): błędy ekstrakcji PDF.
- PdfTextExtractionService (@Service): ekstrakcja strona po stronie przez PDFTextStripper (PDFBox 3.x API).

16. Etap 3 — Scoring strony (ukończony)

Cel: per-stronowa ocena jakości tekstu z PDFBox i decyzja routingu.

Zaimplementowane komponenty:
- RoutingDecision (enum): PDFBOX, OCR_REQUIRED, LLM_REVIEW, MANUAL_REVIEW.
- PageQualityScore (record): metryki strony, score (0–1), lista ostrzeżeń, decyzja routingu.
- PageQualityEvaluator (service): scoring 0–1 z wagami (słowa 0.50, alpha 0.20, U+FFFD kara 0.20, długość słowa 0.10).

17. Etap 4 — OCR Tess4J (ukończony)

Cel: OCR stron PDF bez warstwy tekstowej (Tess4J + Tesseract 5.x).

Zaimplementowane komponenty:
- OcrWord (record): słowo, confidence (0–100), bbox.
- OcrPageResult (record): wynik OCR strony, words, meanConfidence, textPresent.
- OcrException (RuntimeException).
- Tess4jOcrService (@Service): renderowanie strony PDF (PDFRenderer 300 DPI) → OCR Tess4J z per-word confidence.

18. Etap 5 — Kolejka i worker (ukończony)

Cel: asynchroniczne przetwarzanie dokumentów przez Redis Streams.

Zaimplementowane komponenty:
- DocumentStatus (enum): silnie typowane statusy z konwersją do kodów DB.
- OutboxPublisher: scheduled publisher z PENDING outbox → XADD Redis Streams → PUBLISHED.
- StreamConsumer: consumer group delegujący do TaskExecutor → ACK po przetworzeniu.
- DocumentProcessingService: atomowy claim → PDFBox → routing → Tess4J OCR → zapis MinIO → status COMPLETED/MANUAL_REVIEW.
- QueueConfig: RedisTemplate, ThreadPoolTaskExecutor, StreamMessageListenerContainer.
- StuckDocumentRecovery: reset wiszących PROCESSING na starcie.

19. Etap 6 — Statusy i SSE (ukończony)

Cel: powiadamianie klienta o postępie zadania w czasie rzeczywistym (Server-Sent Events).

Zaimplementowane komponenty:
- DocumentProgressEvent (record): SSE event payload.
- DocumentProgressService (@Service): zarządza SseEmitter, broadcast progress.
- DocumentController.streamProgress: GET /api/documents/{id}/progress (text/event-stream).
- DocumentProcessingService: instrumentacja pipeline z wystrzeliwaniem zdarzeń na każdym milestone.
- DocumentProcessingServiceBuilder (util): fluent builder dla service z walidacją zależności.

20. Audit i uodpornienie architektury (Etap 5+)

1. Bezpieczeństwo współbieżności: `claimForProcessing` (atomic UPDATE) eliminuje race condition.
2. StuckDocumentRecovery resetuje PROCESSING → UPLOADED na ApplicationReadyEvent.
3. JacksonConfig z kwalifikowanym beanem `appObjectMapper` (Jackson 2 + JavaTimeModule).
4. UTF-8 byte count w MinioStorageService.uploadJson.
5. HTTP 201 + Location header w DocumentController.upload.
6. Redis resilience: StreamConsumer rozróżnia BUSYGROUP od prawdziwych błędów.
7. Podman Compose: dodano brakujący serwis redis.
8. Builder pattern: DocumentProcessingServiceBuilder.
9. Defensive coding: handleFailure z walidacją null.
10. SSE: warunek documentProgressService != null dla bezpieczeństwa testów.

21. Etap 7 — Analiza LLM (do zrobienia)

Cel: automatyczna analiza wyekstrahowanego tekstu przez LLM (Gemma 4 via OpenRouter.ai):
1. Uproszczenie języka urzędniczego do zrozumiałego polskiego.
2. 1-stronicowe podsumowanie kluczowych punktów.
3. Wykrywanie kruczków prawnych z cytatami i wyjaśnieniami.

**Architektura:**

```
Java (po ekstrakcji)         Redis Stream             Python worker (LLM)
─────────────────          ─────────────────          ─────────────────────
DocumentProcessingService   docuclarity.analysis.    analyzer.py
   ↓ (auto-trigger)         .requested                ↓
AnalysisService            ←───────────────────────  LLM (Gemma 4)
   ↓ publish JSON                                       OpenRouter.ai
   ↓                                                    ↓
   ↓                       docuclarity.analysis.      plainText + summary
AnalysisEventConsumer       .completed                + pitfalls
   ↓ update DB              ←───────────────────────  ↓
   ↓ SSE event                                    MinIO: doc/.../analysis.json
```

**Plan implementacji:**

1. **Migracja V2 (`V2__analysis.sql`)**: kolumny `analysis_status`, `analysis_model`, `analysis_completed_at`, `analysis_error_message`.

2. **Nowe DTOs:**
   - `AnalysisRequest` (documentId, extractedTextStorageKey)
   - `AnalysisResult` (documentId, plainText, summary, pitfalls, model, status)
   - `IndividualSummary` (kto, co, za ile, na jak długo, kto odpowiada)
   - `LegalPitfall` (title, quote, explanation, severity, pageNumber)

3. **Java — `AnalysisService`**:
   - Publikuje `AnalysisRequest` do `docuclarity.analysis.requested` po zakończeniu ekstrakcji (status COMPLETED).
   - Waliduje status dokumentu (COMPLETED, nie MANUAL_REVIEW, bo to za mało tekstu).
   - Aktualizuje DB: `analysis_status = ANALYSIS_QUEUED`.

4. **Java — `AnalysisEventConsumer`**:
   - Subskrybuje `docuclarity.analysis.completed`.
   - Aktualizuje DB: `analysis_status = ANALYZED / ANALYSIS_FAILED`.
   - Emituje SSE event.

5. **Python — `worker.py` + `analyzer.py` + `prompts.py`**:
   - `worker.py`: główna pętla XREADGROUP na `docuclarity.analysis.requested`.
   - `analyzer.py`: klient OpenRouter.ai (HTTP POST do `/api/v1/chat/completions` z modelem Gemma 4); parser JSON response do struktury AnalysisResult.
   - `prompts.py`: system prompt (rola, format odpowiedzi JSON, reguły wykrywania kruczków, brak halucynacji) + user prompt template.

**Przykładowy system prompt:**
```
Jesteś asystentem prawnym specjalizującym się w wyjaśnianiu polskich pism urzędowych
i umów. Twoim zadaniem jest:

1. UPROŚĆ język — zamień urzędniczy bełkot na normalny polski.
2. PODSUMUJ dokument — wyciągnij najważniejsze punkty (kto, co, za ile, na jak długo).
3. WYKRYJ KRUCZKI — znajdź klauzule niekorzystne dla jednej ze stron.

Wyjście MUSI być JSON-em zgodnym ze schematą:
{
  "plainText": "...",
  "summary": {"kto": "...", "co": "...", "zaIle": "...", ...},
  "pitfalls": [{"title": "...", "quote": "...", "explanation": "...", "severity": "LOW/MEDIUM/HIGH/CRITICAL", "pageNumber": 1}]
}

WAŻNE: NIE dodawaj informacji, których nie ma w dokumencie.
```

**Konfiguracja:** `docuclarity.analysis.auto-trigger=true` (domyślnie włączone).

**Storage w MinIO:**
```
documents/{id}/analysis.json — pełna struktura (plainText, summary, pitfalls)
```

**Testy:**
- Unit: walidacja promptu, parsowanie JSON, retry logic.
- Integration: mock OpenRouter.ai w testach (np. WireMock).
- Test promptu: na próbce 3-5 prawdziwych umów z adnotacjami eksperta.

22. Aktualny stan testów

Raportowany stan po Etapach 1–6 (~89 testów, 0 błędów, 10 pominiętych):
- Etap 1: DocumentServiceTest (15), MinioStorageServiceTest (8), DocumentControllerTest (9) — PASS
- Etap 2: PdfTextExtractionServiceTest (12) — PASS
- Etap 3: PageQualityEvaluatorTest (14) — PASS
- Etap 4: Tess4jOcrServiceTest (10, 10 skipped — gated na TESSDATA_PATH) — PASS
- Etap 5: DocumentProcessingServiceTest (8), QueueIntegrationTest (2), DocuClarityApplicationTests (1) — PASS
- Etap 6: Planowane — DocumentProgressServiceTest (4–5), endpoint SSE w DocumentControllerTest (2)
- Razem: 89 testów, 0 failures, 0 errors, 10 skipped (docelowo ~96 po Etapie 6)
```

---

## Podsumowanie najważniejszych zmian

| Element | Przed | Po |
|---|---|---|
| **Cel Etapu 7** | Tłumaczenie międzyjęzykowe (pl→en) | Analiza LLM (uproszczenie polskiego, summary, kruczki) |
| **Provider** | DeepL/Google Translate | OpenRouter.ai (Gemma 4) |
| **Output** | Przetłumaczony tekst | Strukturyzowany JSON: plainText + summary + pitfalls |
| **Trigger** | Manual (POST /translate) | Automatyczny po ekstrakcji |
| **Język outputu** | Polski → angielski | Zawsze polski (uproszczony) |
| **Prompt engineering** | Brak | Kluczowy element (system + user prompt) |
| **Strumienie Redis** | `*.translations.*` | `*.analysis.*` |
| **Sekcje pliku** | "Tłumaczenie" | "Analiza LLM" |

Czy chcesz, żebym teraz zaczął implementować Etap 7 w tej nowej wizji? Mogę zacząć od:
1. Migracji V2
2. DTOs (AnalysisRequest, AnalysisResult, LegalPitfall, IndividualSummary)
3. Zmiany enum TranslationStatus → AnalysisStatus
4. AnalysisService (publikacja)
5. AnalysisEventConsumer (odbiór)
6. Update DocumentProcessingService (auto-trigger)
7. Python worker + analyzer.py + prompts.py + Dockerfile
8. Update podman-compose.yml

Daj znać, od czego zaczynamy!
