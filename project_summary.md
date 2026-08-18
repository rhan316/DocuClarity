DocuClarity — karta projektu
1. Cel projektu
   Zbudowanie platformy do asynchronicznego przetwarzania i tłumaczenia dokumentów (głównie PDF), która:

przyjmuje dokumenty przez REST API,
przetwarza je w tle (ekstrakcja tekstu → tłumaczenie),
zapewnia użytkownikowi wgląd w status zadania na bieżąco (SSE),
umożliwia pobranie wyników.

Odpowiedź na pytanie „dlaczego to robimy": ręczna obsługa dokumentów (skanów, umów, pism) jest wolna i podatna na błędy; automatyczny pipeline z jakościową ekstrakcją tekstu skraca ten proces i daje powtarzalne, audytowalne wyniki.

2. Problem i wartość

Problem | Rozwiązanie w DocuClarity
---|---
PDF-y bez warstwy tekstowej (skany) | Kaskada ekstrakcji: PDFBox → OCR → LLM Vision
Długi czas przetwarzania blokuje użytkownika | Przetwarzanie asynchroniczne, statusy zadań, SSE
Trudno ocenić jakość ekstrakcji | Scoring per strona, osobne wyniki każdego etapu, flaga MANUAL_REVIEW
Brak śladu, skąd pochodzi wynik | Wyniki każdego etapu w MinIO + metadane (silnik, confidence, ostrzeżenia)

3. Zakres
   W zakresie

REST API: upload plików, status zadań, pobieranie wyników.
Asynchroniczne przetwarzanie zadań (kolejka na Redis Streams).
Transactional outbox (spójność zapisu stanu i publikacji zdarzeń).
Ekstrakcja tekstu z PDF z routingiem jakościowym per strona:
Apache PDFBox — dokumenty z warstwą tekstową.
Tess4J (Tesseract OCR) — skany i strony bez tekstu.
LLM Vision — trudne strony: tabele, formularze, pieczęcie, niski confidence OCR.
Zapis wyników każdego etapu w MinIO z metadanymi.
Tłumaczenie wyekstrahowanego tekstu (worker Python).

Poza zakresem (na ten moment)

Stirling-PDF — odłożone; ewentualnie później jako searchable PDF / preprocessing (deskew, konwersje). Podstawowy OCR realizuje Tess4J.
LLM do oceny każdej strony — świadomie odrzucone: LLM tylko jako selektywny fallback.
Korekta/redakcja dokumentów przez użytkownika, panel administracyjny, zaawansowane uprawnienia.

4. Architektura docelowa
   Klient
   |
   | REST (upload / status / SSE / download)
   v
   Spring Boot (backend — kontener Podman)
   |-- PostgreSQL        (metadane, statusy zadań, outbox)
   |-- Redis Streams     (kolejka zadań)
   |-- MinIO             (pliki źródłowe + wyniki etapów)
   |
   v
   Worker Java          -- ekstrakcja: PDFBox -> Tess4J -> (LLM Vision)
   |
   v
   Worker Python        -- tłumaczenie

Konteneryzacja:
Cała aplikacja Spring Boot z Tess4J działa w jednym kontenerze Podman.
Tesseract (eng+pol) instalowany w obrazie runtime przez apt (nie zależy od plików systemowych hosta).

5. Pipeline ekstrakcji tekstu (decyzje projektowe)
   PDF
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
   LLM Vision (selektywnie)
   |
   v
   MANUAL_REVIEW (jeśli automat nie daje pewności)

Kluczowe zasady:

Routing jest per strona, nie per dokument — strona 1 może trafić do PDFBox, strona 3 do OCR.
textPresent == true z PDFBox nie oznacza dobrej jakości — potrzebny scoring (długość, słowa, znaki zastępcze, layout).
LLM nie nadpisuje OCR — preferowany tryb to korekta/rekonstrukcja (obraz + wynik OCR), nie „trzeci OCR".
Wynik LLM musi być oznaczony; niepewne fragmenty nie mogą być po cichu „poprawione".
Czwartym legalnym wynikiem routingu jest MANUAL_REVIEW.

Format przechowywania w MinIO:
documents/{documentId}/pages/001/source.png
documents/{documentId}/pages/001/pdfbox.json
documents/{documentId}/pages/001/ocr.tsv
documents/{documentId}/pages/001/ocr.txt
documents/{documentId}/pages/001/llm.json
documents/{documentId}/pages/001/final.json

6. Stos technologiczny

Warstwa | Technologia
---|---
Backend | Spring Boot 4.1.0 (MVC, Validation, Actuator) — Java 21
Dane | Spring Data JDBC + PostgreSQL + Flyway
Kolejka | Redis Streams (Spring Data Redis)
Obiekty | MinIO
Ekstrakcja PDF | Apache PDFBox 3.0.8
OCR | Tess4J 5.20.0 + natywny Tesseract 5.5.0 + tessdata (eng, pol) — w kontenerze Podman
Tłumaczenie | Worker Python
Testy | Testcontainers + JUnit 5 + Mockito
Konteneryzacja | Podman 6.1.0 + podman-compose 1.6.0

7. Struktura projektu

src/main/java/org/dar316/docuclarity/
DocuClarityApplication.java          -- punkt wejścia Spring Boot
config/
JacksonConfig.java                 -- kwalifikowany bean appObjectMapper (Jackson 2 + JavaTimeModule) dla danych domenowych
MinioConfig.java                   -- konfiguracja klienta MinioClient
MinioProperties.java               -- właściwości MinIO (docuclarity.minio.*)
QueueConfig.java                   -- RedisTemplate, TaskExecutor, DocumentProcessingService, StreamListenerContainer
ResourceInitializer.java           -- CommandLineRunner: idempotentne tworzenie bucketu MinIO
controller/
DocumentController.java            -- REST: POST /api/documents/upload (201 Created + Location), GET /api/documents/{id}
GlobalExceptionHandler.java        -- mapowanie wyjątków na kody HTTP 400/404/503
dto/
DocumentResultSummary.java         -- record: podsumowanie wyniku przetwarzania (result.json)
DocumentStatusResponse.java        -- record: odpowiedź ze statusem dokumentu
ExtractedPageResult.java           -- record: wynik per strona (final.json)
OcrException.java                  -- wyjątek runtime OCR
OcrPageResult.java                 -- record: wynik OCR per strona
OcrWord.java                       -- record: słowo z confidence i bbox (OCR)
PageQualityScore.java              -- record: wynik oceny jakości strony (metryki + score + decyzja)
PdfPageText.java                   -- record: wynik ekstrakcji per strona (PDFBox)
PdfTextExtractionException.java    -- wyjątek runtime ekstrakcji PDF
PdfTextExtractionResult.java       -- record: wynik ekstrakcji per dokument (PDFBox)
RoutingDecision.java               -- enum: decyzja routingu per strona (PDFBOX/OCR_REQUIRED/LLM_REVIEW/MANUAL_REVIEW)
UploadResponse.java                -- record: odpowiedź po przesłaniu pliku
model/
Document.java                      -- encja Spring Data JDBC (tabela documents, implementuje Persistable)
DocumentStatus.java                -- enum statusów (UPLOADED, PROCESSING, COMPLETED, FAILED, MANUAL_REVIEW)
OutboxEntry.java                   -- encja Spring Data JDBC (tabela outbox, implementuje Persistable)
repository/
DocumentRepository.java            -- CRUD repozytorium + claimForProcessing (atomowy UPDATE) + resetStuckProcessing
EntityTimestampCallback.java       -- BeforeConvertCallback: automatyczne ustawianie created_at/updated_at
OutboxRepository.java              -- CRUD repozytorium + findPending()
service/
DocumentNotFoundException.java     -- wyjątek runtime braku dokumentu (HTTP 404)
DocumentProcessingException.java   -- wyjątek runtime błędu workera
DocumentProcessingService.java     -- worker: atomowy claim → orkiestracja PDFBox→routing→OCR→zapis MinIO
DocumentService.java               -- upload: MinIO → transakcja DB (dokument + outbox) → kompensacja
DocumentUploadException.java       -- wyjątek runtime błędu uploadu (HTTP 400)
MinioStorageException.java         -- wyjątek runtime błędu MinIO (HTTP 503)
MinioStorageService.java           -- operacje na plikach i JSON w MinIO (poprawne zliczanie bajtów UTF-8)
OutboxPublisher.java               -- scheduler: publikacja PENDING outbox na Redis Streams
PageQualityEvaluator.java          -- scoring jakości tekstu PDFBox i routing PDFBOX vs OCR_REQUIRED
PdfTextExtractionService.java      -- ekstrakcja warstwy tekstowej PDFBox
StreamConsumer.java                -- konsument Redis Streams (group docuclarity-workers) → delegacja do TaskExecutora
Tess4jOcrService.java              -- renderowanie strony PDF (300 DPI) + OCR Tess4J z per-word confidence
util/
StuckDocumentRecovery.java         -- ApplicationReadyEvent: resetowanie zadań PROCESSING do UPLOADED po restarcie aplikacji

src/test/java/org/dar316/docuclarity/
DocuClarityApplicationTests.java     -- test kontekstu Spring Boot
QueueIntegrationTest.java            -- test integracyjny Redis+Postgres+MinIO (round-trip outbox→Redis)
TestcontainersConfiguration.java     -- konfiguracja Testcontainers (PostgreSQL, Redis)
TestDocuClarityApplication.java      -- punkt wejścia do lokalnego uruchamiania testowego
controller/
DocumentControllerTest.java        -- 9 testów integracyjnych endpointów REST (MockMvc)
service/
DocumentProcessingServiceTest.java -- 8 testów jednostkowych workera (Mockito)
DocumentServiceTest.java           -- 15 testów integracyjnych serwisu uploadu
MinioStorageServiceTest.java       -- 8 testów integracyjnych operacji MinIO
PageQualityEvaluatorTest.java      -- 14 testów jednostkowych scoringu jakości strony
PdfTextExtractionServiceTest.java  -- 12 testów jednostkowych ekstrakcji PDFBox
Tess4jOcrServiceTest.java          -- 10 testów integracyjnych OCR (gated na TESSDATA_PATH)

src/main/resources/
application.properties               -- konfiguracja DB, MinIO, OCR, Redis Streams, scoringu i uploadu
db/migration/
V1__documents_and_outbox.sql       -- Flyway: tabele documents i outbox z indeksami i CHECK constraints

Pliki kontenera:
Containerfile                        -- multi-stage: eclipse-temurin:21-jdk (build) + :21-jre (runtime z tesseract eng+pol)
.containerignore                     -- wykluczenia build contextu
podman-compose.yml                   -- definicja serwisów: postgres, minio, redis, docuclarity

8. Etapy realizacji
   Cele etapów sformułowane według zasady SMART — każdy ma mierzalny rezultat i kryterium zakończenia.

Etap | Rezultat | Status
---|---|---
1. Fundament | Upload pliku, zapis do MinIO, zadanie w PostgreSQL, outbox | ukończony
2. Ekstrakcja PDFBox | Tekst per strona + model wyniku (PdfTextExtractionResult), testy jednostkowe | ukończony
3. Scoring strony | PageQualityEvaluator — metryki tekstu, decyzja PDFBOX / wymagany OCR | ukończony
4. OCR Tess4J | OCR stron bez tekstu, confidence per słowo, progi routingu | ukończony
5. Kolejka i worker | Redis Streams, asynchroniczne przetwarzanie, timeouty, retry | ukończony
6. Statusy i SSE | Powiadamianie klienta o postępie zadania | do zrobienia
7. Tłumaczenie | Integracja z workerem Python | do zrobienia
8. LLM Vision | Selektywny fallback dla trudnych stron, oznaczanie niepewnych fragmentów | do zrobienia
9. Pobieranie wyników | Endpointy download, wersjonowanie wyników | do zrobienia

9. Kryteria sukcesu (KPI)
   Propozycje wskaźników — wartości docelowe do kalibracji na rzeczywistym zbiorze dokumentów (spubl.pl):

Pokrycie ekstrakcji: % stron, dla których pipeline zwraca użyteczny tekst bez interwencji (cel: > 95% dla PDF z warstwą tekstową).
Skuteczność routingu: % stron poprawnie skierowanych do OCR/LLM (mierzone na próbce testowej).
Frakcja LLM: % stron wymagających LLM Vision (cel: minimalna — koszt i czas).
Czas przetwarzania: mediana czasu od uploadu do gotowego wyniku dla typowego dokumentu.
Audytowalność: 100% stron ma zapisany silnik ekstrakcji i metadane jakości.

10. Ryzyka i ograniczenia

Ryzyko | Mitygacja
---|---
OCR jako operacja CPU-intensive blokuje zasoby | Przetwarzanie w workerze, bounded ThreadPoolTaskExecutor (pool=4), izolacja od wątków nasłuchu i web
Tess4J wymaga bibliotek natywnych i traineddata | Kontener Podman z tesseract-ocr-eng + tesseract-ocr-pol instalowanymi przez apt; testy integracyjne gated na TESSDATA_PATH
LLM „poprawia" dane (kwoty, daty, identyfikatory) | Tryb korekty zamiast trzeciego OCR, instrukcja [ILLEGIBLE], oznaczanie zmian, zachowanie oryginalnego OCR
Duże PDF-y → pamięć przy renderowaniu 300 DPI | Limity rozmiaru (100MB), przetwarzanie strona po stronie
Confidence OCR ≠ poprawność | Łączenie z innymi metrykami (jakość obrazu, layout, heurystyki tekstu)
Zaszyfrowane/uszkodzone PDF-y | Obsługa wyjątków ekstrakcji, status błędu zadania, MANUAL_REVIEW
Wyścigi współbieżności przy podwójnym dostarczeniu zdarzeń | Atomowy UPDATE ze statusem UPLOADED (claimForProcessing) w DocumentRepository
Awaria JVM podczas przetwarzania w workerze | StuckDocumentRecovery resetujący wiszące PROCESSING na starcie kontenera

11. Decyzje architektoniczne podjęte do tej pory

1. Tess4J zamiast Stirling-PDF do podstawowego OCR — prostszy pipeline (PDFBox → Tess4J → LLM), bez dodatkowego kontenera; Stirling-PDF opcjonalnie później.
2. Routing jakościowy per strona, nie per dokument.
3. LLM Vision selektywnie — tylko przy niskim confidence lub złożonym layoucie, najlepiej jako korektor OCR, nie samodzielny trzeci OCR.
4. Ekstrakcja zwraca wynik per strona (PdfPageText) od pierwszej implementacji — ułatwia późniejszy routing bez refaktoringu.
5. MANUAL_REVIEW jako pełnoprawny wynik dla dokumentów niskiej pewności.
6. Wyniki wszystkich etapów w MinIO — nie nadpisujemy, wersjonujemy.
7. Cała aplikacja Spring Boot w jednym kontenerze Podman — Tess4J działa wewnątrz JVM przez JNA, łączy się z natywnym Tesseract zainstalowanym w obrazie.
8. Kwalifikowany Jackson 2 (`appObjectMapper`) w `JacksonConfig` — izolacja wewnętrznej serializacji MinIO/Outbox (z modułem JavaTimeModule) od auto-konfiguracji Jackson 3 w Spring Boot 4 WebMVC.
9. Atomowy claim przetwarzania — `claimForProcessing` eliminuje race condition przy wielokrotnym/współbieżnym dostarczeniu rekordów z Redis Streams.
10. podman-compose zawiera komplet serwisów: `postgres`, `minio`, `redis`, `docuclarity` z dedykowanymi sieciami i wolumenami.

12. Konfiguracja (application.properties)

# PostgreSQL
spring.datasource.url=${POSTGRES_URL:jdbc:postgresql://localhost:5432/docuclarity}
spring.datasource.username=${POSTGRES_USER:docuclarity}
spring.datasource.password=${POSTGRES_PASSWORD:docuclarity}
spring.flyway.enabled=true

# MinIO
docuclarity.minio.endpoint=${MINIO_ENDPOINT:http://localhost:9000}
docuclarity.minio.access-key=${MINIO_ACCESS_KEY:minioadmin}
docuclarity.minio.secret-key=${MINIO_SECRET_KEY:minioadmin}
docuclarity.minio.bucket=${MINIO_BUCKET:docuclarity}

# OCR (Tess4J)
docuclarity.ocr.render-dpi=300
docuclarity.ocr.tessdata-path=${TESSDATA_PREFIX:/usr/share/tessdata}
docuclarity.ocr.language=eng+pol
docuclarity.ocr.page-seg-mode=1

# Scoring jakości (PageQualityEvaluator)
docuclarity.quality.accept-threshold=0.85
docuclarity.quality.min-word-count=5
docuclarity.quality.ideal-word-count=20
docuclarity.quality.max-replacement-ratio=0.05

# Kolejka (Redis Streams)
docuclarity.queue.enabled=true
docuclarity.queue.stream-key=docuclarity.documents
docuclarity.queue.consumer-group=docuclarity-workers
docuclarity.queue.consumer-name=worker-1
docuclarity.queue.publish-interval-ms=1000
docuclarity.queue.poll-timeout-ms=2000
docuclarity.queue.worker-pool-size=4
docuclarity.queue.max-processing-attempts=3
docuclarity.queue.max-publish-attempts=5

spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.timeout=3000ms

# Upload
spring.servlet.multipart.max-file-size=100MB
spring.servlet.multipart.max-request-size=100MB

13. Uruchomienie

Lokalne testy (wymaga tesseract w systemie):
TESSDATA_PATH=/usr/share/tessdata ./gradlew test

Lokalne testy (bez OCR — testy OCR pomijane):
./gradlew test

Build obrazu kontenera:
podman build -t docuclarity .

Uruchomienie pełnego stosu przez podman-compose:
podman-compose up

Test OCR w kontenerze:
podman run --rm --entrypoint tesseract docuclarity --list-langs
podman run --rm -v /path/to/image.png:/tmp/img.png:Z --entrypoint tesseract docuclarity /tmp/img.png stdout -l eng+pol

14. Etap 1 — Fundament (ukończony)

Zaimplementowane komponenty:
- Flyway migracja V1: tabele documents (statusy: UPLOADED, PROCESSING, COMPLETED, FAILED, MANUAL_REVIEW) i outbox (statusy: PENDING, PUBLISHED, FAILED) z CHECK constraints.
- MinioProperties + MinioConfig: konfiguracja klienta MinioClient.
- MinioStorageService: upload/delete plików, idempotentna inicjalizacja bucketu.
- Document + OutboxEntry: encje Spring Data JDBC implementujące Persistable (@PersistenceCreator dla odczytu z DB).
- EntityTimestampCallback: automatyczne ustawianie created_at/updated_at.
- DocumentService: orkiestracja upload (MinIO → TransactionTemplate DB → kompensacja w razie błędu).
- DocumentController: POST /api/documents/upload (zwraca HTTP 201 Created z nagłówkiem Location), GET /api/documents/{id} (status).
- GlobalExceptionHandler: mapowanie wyjątków na HTTP 400/404/503.

15. Etap 2 — Ekstrakcja PDFBox (ukończony)

Zaimplementowane komponenty:
- PdfPageText (record): wynik ekstrakcji per strona — pageNum (1-based), text, charCount, wordCount, textPresent.
- PdfTextExtractionResult (record): wynik zbiorczy per dokument — pageCount, List<PdfPageText>, combinedText.
- PdfTextExtractionException (RuntimeException): błędy ekstrakcji PDF.
- PdfTextExtractionService (@Service): ekstrakcja strona po stronie przez PDFTextStripper i Loader.loadPDF (PDFBox 3.x API).

16. Etap 3 — Scoring strony (ukończony)

Zaimplementowane komponenty:
- RoutingDecision (enum): PDFBOX, OCR_REQUIRED, LLM_REVIEW, MANUAL_REVIEW.
- PageQualityScore (record): metryki strony, score (0–1), lista ostrzeżeń, decyzja routingu.
- PageQualityEvaluator (service): scoring tekstu (wagi: słowa 0.50, alpha 0.20, kara U+FFFD 0.20, długość słowa 0.10). Decyzja score >= 0.85 → PDFBOX, inaczej OCR_REQUIRED.

17. Etap 4 — OCR Tess4J (ukończony)

Zaimplementowane komponenty:
- OcrWord (record): słowo, confidence (0–100), bbox [x,y,w,h].
- OcrPageResult (record): wynik OCR strony, List<OcrWord>, meanConfidence, textPresent.
- OcrException (RuntimeException): błędy OCR.
- Tess4jOcrService (@Service): renderowanie strony PDF (PDFRenderer 300 DPI) → OCR Tess4J z per-word confidence.

18. Etap 5 — Kolejka i worker (ukończony)

Zaimplementowane komponenty:
- DocumentStatus (enum): silnie typowane statusy z konwersją do kodów DB.
- OutboxPublisher (@Component, @Scheduled 1s): PENDING outbox → XADD Redis Streams → PUBLISHED w DB.
- StreamConsumer (@Component): consumer group `docuclarity-workers` → delegacja do TaskExecutora → ACK po przetworzeniu.
- DocumentProcessingService (worker logic): atomowy claim stanu → PDFBox → routing → Tess4J OCR → zapis final.json i result.json w MinIO → status COMPLETED/MANUAL_REVIEW.
- QueueConfig (@Configuration): RedisTemplate, ThreadPoolTaskExecutor (worker pool=4), listener container.

19. Naprawy po weryfikacji testów Etapu 5 (2026-08-17)

1. OutboxPublisher: dodano `.withStreamKey(streamKey)` do `StreamRecords.string(...)`.
2. MinioConfig & Serialization: dodano rejestrację `JavaTimeModule` dla serializacji `Instant`.
3. DocumentProcessingServiceTest: przepisano mocki transakcyjne na `Consumer<TransactionStatus>` (Spring Boot 4.1 / Framework 7).
4. Asertywność testów: poprawiono asercję pustego tekstu w testach Mockito.

20. Audyt i uodpornienie architektury (Etap 5+)

Po kompleksowym audycie wdrożono następujące usprawnienia i poprawki błędów:

1. Bezpieczeństwo współbieżności i eliminacja Race Condition (DocumentRepository & DocumentProcessingService):
    - Wprowadzono atomową metodę `@Modifying @Query UPDATE documents SET status = 'PROCESSING', processing_attempts = processing_attempts + 1, updated_at = now() WHERE id = :id AND status = 'UPLOADED'` (`claimForProcessing`).
    - Wyeliminowano podatność typu lost-update przy ponownym/równoległym dostarczeniu rekordu ze strumienia Redis.
    - Poprawiono import adnotacji `@Param` na oficjalny `org.springframework.data.repository.query.Param`.

2. Odzyskiwanie po awarii kontenera (StuckDocumentRecovery):
    - Dodano komponent `StuckDocumentRecovery` w pakiecie `util`, który na zdarzenie `ApplicationReadyEvent` wykonuje `resetStuckProcessing()`, przywracając zadania wiszące w stanie `PROCESSING` po nagłym padzie JVM z powrotem do stanu `UPLOADED`.

3. Rozdzielenie kontekstów Jacksona (JacksonConfig):
    - Utworzono dedykowaną klasę `JacksonConfig` definiującą kwalifikowany bean `@Bean("appObjectMapper")` (Jackson 2 z `JavaTimeModule`) używany do serializacji Outbox oraz plików wynikowych JSON w MinIO.
    - Usunięto zbędny bean z `MinioConfig`, zapobiegając konfliktom z natywnym Jacksonem 3 w Spring Boot 4 WebMVC.

4. Poprawne zliczanie długości bufora UTF-8 (MinioStorageService):
    - W metodzie `uploadJson` zmieniono `content.length()` (liczba znaków) na `bytes.length` tablicy `content.getBytes(StandardCharsets.UTF_8)`, zapobiegając obcinaniu plików JSON zawierających polskie znaki diakrytyczne.

5. Spójność kontraktu REST (DocumentController):
    - Endpoint `POST /api/documents/upload` zwraca teraz poprawny kod `201 Created` wraz z nagłówkiem `Location: /api/documents/{id}` zamiast kodu `200 OK`.

6. Uzupełnienie środowiska kontenerowego (podman-compose.yml):
    - Dodano brakujący serwis `redis:latest` (port 6379) oraz skonfigurowano zmienne środowiskowe `REDIS_HOST: redis` i `REDIS_PORT: 6379` w serwisie `docuclarity`.

7. Odporność konsumenta Redis (StreamConsumer):
    - Dodano obsługę `RedisSystemException` przy tworzeniu consumer group (`BUSYGROUP` logowane jako debug, inne błędy jako ostrzeżenia).
