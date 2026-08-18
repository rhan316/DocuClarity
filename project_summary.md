DocuClarity — karta projektu
1. Cel projektu
Zbudowanie platformy do asynchronicznego przetwarzania i tłumaczenia dokumentów (głównie PDF), która:

przyjmuje dokumenty przez REST API,
przetwarza je w tle (ekstrakcja tekstu → tłumaczenie),
zapewnia użytkownikowi wgląd w status zadania na bieżąco (SSE),
umożliwia pobranie wyników.

Odpowiedź na pytanie „dlaczego to robimy": ręczna obsługa dokumentów (skanów, umów, pism) jest wolna i podatna na błędy; automatyczny pipeline z jakościową ekstrakcją tekstu skraca ten proces i daje powtarzalne, audytowalne wyniki.
2. Problem i wartość



Problem
Rozwiązanie w DocuClarity



PDF-y bez warstwy tekstowej (skany)
Kaskada ekstrakcji: PDFBox → OCR → LLM Vision



Długi czas przetwarzania blokuje użytkownika
Przetwarzanie asynchroniczne, statusy zadań, SSE



Trudno ocenić jakość ekstrakcji
Scoring per strona, osobne wyniki każdego etapu, flaga MANUAL_REVIEW



Brak śladu, skąd pochodzi wynik
Wyniki każdego etapu w MinIO + metadane (silnik, confidence, ostrzeżenia)



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



Warstwa
Technologia



Backend
Spring Boot 4.1.0 (MVC, Validation, Actuator) — Java 21



Dane
Spring Data JDBC + PostgreSQL + Flyway



Kolejka
Redis Streams (Spring Data Redis)



Obiekty
MinIO



Ekstrakcja PDF
Apache PDFBox 3.0.8



OCR
Tess4J 5.20.0 + natywny Tesseract 5.5.0 + tessdata (eng, pol) — w kontenerze Podman



Tłumaczenie
Worker Python



Testy
Testcontainers + JUnit 5



Konteneryzacja
Podman 6.1.0 + podman-compose 1.6.0



7. Struktura projektu

src/main/java/org/dar316/docuclarity/
  DocuClarityApplication.java          -- punkt wejścia Spring Boot
  dto/
    PdfPageText.java                   -- record: wynik ekstrakcji per strona (PDFBox)
    PdfTextExtractionResult.java       -- record: wynik ekstrakcji per dokument (PDFBox)
    PdfTextExtractionException.java    -- wyjątek runtime ekstrakcji PDF
    OcrWord.java                       -- record: słowo z confidence i bbox (OCR)
    OcrPageResult.java                 -- record: wynik OCR per strona
    OcrException.java                  -- wyjątek runtime OCR
    RoutingDecision.java               -- enum: decyzja routingu per strona (PDFBOX/OCR_REQUIRED/LLM_REVIEW/MANUAL_REVIEW)
    PageQualityScore.java              -- record: wynik oceny jakości strony (metryki + score + decyzja)
  service/
    PdfTextExtractionService.java      -- ekstrakcja tekstu z PDF przez PDFBox (byte[] / InputStream)
    Tess4jOcrService.java              -- OCR: render strony PDF → Tesseract z per-word confidence
    PageQualityEvaluator.java          -- scoring jakości strony, decyzja PDFBOX vs OCR_REQUIRED
    DocumentProcessingService.java     -- worker: orkiestracja PDFBox→routing→OCR→zapis wyników
    OutboxPublisher.java               -- scheduler: publikacja wpisów outbox na Redis Streams
    StreamConsumer.java                -- konsument Redis Streams (consumer group) → delegacja do workera
    DocumentProcessingException.java   -- wyjątek runtime workera
  config/
    QueueConfig.java                   -- RedisTemplate, TaskExecutor, DocumentProcessingService, StreamListenerContainer

src/test/java/org/dar316/docuclarity/
  DocuClarityApplicationTests.java             -- test kontekstu Spring
  TestcontainersConfiguration.java              -- konfiguracja Testcontainers (PostgreSQL, Redis)
  TestDocuClarityApplication.java              -- punkt wejścia dla testów z Testcontainers
  service/
    PdfTextExtractionServiceTest.java           -- 12 testów jednostkowych (PDFBox)
    Tess4jOcrServiceTest.java                   -- 10 testów integracyjnych (OCR, gated na TESSDATA_PATH)
    PageQualityEvaluatorTest.java              -- 14 testów jednostkowych (scoring jakości strony)
  service/
    DocumentProcessingServiceTest.java        -- testy jednostkowe workera (Mockito)
    QueueIntegrationTest.java                 -- test integracyjny Redis+Postgres+MinIO (round-trip)

src/main/resources/
  application.properties                         -- konfiguracja (OCR: DPI, tessdata, jezyk, PSM)

Pliki kontenera:
  Containerfile                                  -- multi-stage: eclipse-temurin:21-jdk (build) + :21-jre (runtime z tesseract-ocr-eng+pol)
  .containerignore                               -- pliki wykluczone z build context
  podman-compose.yml                             -- definicja serwisu docuclarity

8. Etapy realizacji
Cele etapów sformułowane według zasady SMART — każdy ma mierzalny rezultat i kryterium zakończenia.



Etap
Rezultat
Status



1. Fundament
Upload pliku, zapis do MinIO, zadanie w PostgreSQL, outbox
ukończony


2. Ekstrakcja PDFBox
Tekst per strona + model wyniku (PdfTextExtractionResult), testy jednostkowe
ukończony


3. Scoring strony
PageQualityEvaluator — metryki tekstu, decyzja PDFBOX / wymagany OCR
ukończony


4. OCR Tess4J
OCR stron bez tekstu, confidence per słowo, progi routingu
ukończony


5. Kolejka i worker
Redis Streams, asynchroniczne przetwarzanie, timeouty, retry
ukończony


6. Statusy i SSE
Powiadamianie klienta o postępie zadania
do zrobienia


7. Tłumaczenie
Integracja z workerem Python
do zrobienia


8. LLM Vision
Selektywny fallback dla trudnych stron, oznaczanie niepewnych fragmentów
do zrobienia


9. Pobieranie wyników
Endpointy download, wersjonowanie wyników
do zrobienia



9. Kryteria sukcesu (KPI)
Propozycje wskaźników — wartości docelowe do kalibracji na rzeczywistym zbiorze dokumentów (spubl.pl):

Pokrycie ekstrakcji: % stron, dla których pipeline zwraca użyteczny tekst bez interwencji (cel: > 95% dla PDF z warstwą tekstową).
Skuteczność routingu: % stron poprawnie skierowanych do OCR/LLM (mierzone na próbce testowej).
Frakcja LLM: % stron wymagających LLM Vision (cel: minimalna — koszt i czas).
Czas przetwarzania: mediana czasu od uploadu do gotowego wyniku dla typowego dokumentu.
Audytowalność: 100% stron ma zapisany silnik ekstrakcji i metadane jakości.

10. Ryzyka i ograniczenia



Ryzyko
Mitygacja



OCR jako operacja CPU-intensive blokuje zasoby
Przetwarzanie w workerze, limity równoległości, timeouty, retry tylko dla błędów przejściowych



Tess4J wymaga bibliotek natywnych i traineddata w środowisku
Kontener Podman z tesseract-ocr-eng + tesseract-ocr-pol instalowanymi przez apt; testy integracyjne gated na TESSDATA_PATH



LLM „poprawia" dane (kwoty, daty, identyfikatory)
Tryb korekty zamiast trzeciego OCR, instrukcja [ILLEGIBLE], oznaczanie zmian, zachowanie oryginalnego OCR



Duże PDF-y → pamięć przy renderowaniu 300 DPI
Limity rozmiaru/liczby stron, przetwarzanie strona po stronie



Confidence OCR ≠ poprawność
Łączenie z innymi metrykami (jakość obrazu, layout, heurystyki tekstu)



Zaszyfrowane/uszkodzone PDF-y
Obsługa wyjątków ekstrakcji, status błędu zadania, MANUAL_REVIEW



11. Decyzje architektoniczne podjęte do tej pory

Tess4J zamiast Stirling-PDF do podstawowego OCR — prostszy pipeline (PDFBox → Tess4J → LLM), bez dodatkowego kontenera; Stirling-PDF opcjonalnie później.
Routing jakościowy per strona, nie per dokument.
LLM Vision selektywnie — tylko przy niskim confidence lub złożonym layoucie, najlepiej jako korektor OCR, nie samodzielny trzeci OCR.
Ekstrakcja zwraca wynik per strona (PdfPageText) od pierwszej implementacji — ułatwia późniejszy routing bez refaktoringu.
MANUAL_REVIEW jako pełnoprawny wynik dla dokumentów niskiej pewności.
Wyniki wszystkich etapów w MinIO — nie nadpisujemy, wersjonujemy.
Cała aplikacja Spring Boot w jednym kontenerze Podman — Tess4J działa wewnątrz JVM przez JNA, łączy się z natywnym Tesseract zainstalowanym w obrazie. Nie ma osobnego mikserwisu OCR — to najprostsze i najbardziej spójne podejście dla aktualnego etapu.
tessdata-path konfigurowalny przez zmienną TESSDATA_PREFIX (kontener: /usr/share/tesseract-ocr/5/tessdata, lokalnie: /usr/share/tessdata).
Język OCR domyślnie eng+pol — obsługiwane zarówno dokumenty angielskie jak i polskie ze znakami diakrytycznymi.
Testy OCR gated na zmienną TESSDATA_PATH — nie zawodzą na CI bez Tesseract.

12. Konfiguracja OCR (application.properties)

docuclarity.ocr.render-dpi=300           # DPI renderowania strony PDF do obrazu
docuclarity.ocr.tessdata-path=${TESSDATA_PREFIX:/usr/share/tessdata}  # ścieżka tessdata
docuclarity.ocr.language=eng+pol         # język OCR (kody Tesseract, '+' dla wielu)
docuclarity.ocr.page-seg-mode=1          # tryb segmentacji (1 = automatic with OSD)

13. Uruchomienie

Lokalne testy (wymaga tesseract w systemie):
  TESSDATA_PATH=/usr/share/tessdata ./gradlew test

Lokalne testy (bez OCR — testy OCR pomijane):
  ./gradlew test

Build obrazu kontenera:
  podman build -t docuclarity .

Uruchomienie przez podman-compose:
  podman-compose up

Test OCR w kontenerze:
  podman run --rm --entrypoint tesseract docuclarity --list-langs
  podman run --rm -v /path/to/image.png:/tmp/img.png:Z --entrypoint tesseract docuclarity /tmp/img.png stdout -l eng+pol

Uwagi

Sekcje KPI i progi jakości (0.85, 0.60 itd.) to wartości robocze — wymagają kalibracji na realnym korpusie dokumentów.
Aplikacja nie wystartuje w pełni bez PostgreSQL i Redis (Etap 1 i 5 nieukończone). Gdy te zależności będą gotowe, podman-compose.yml należy rozszerzyć o serwisy postgres i redis.
Projekt nie jest jeszcze zainicjalizowany w git.

14. Etap 1 — Fundament (ukończony)

Zaimplementowane komponenty:
- Flyway migracja V1: tabele documents (statusy: UPLOADED, PROCESSING, COMPLETED, FAILED, MANUAL_REVIEW) i outbox (statusy: PENDING, PUBLISHED, FAILED) z CHECK constraints zamiast enumów PostgreSQL (kompatybilność z Spring Data JDBC).
- MinioProperties + MinioConfig: bean MinioClient + ObjectMapper (Jackson 2 wymagany przez MinIO SDK; Spring Boot 4 domyślnie używa Jackson 3).
- MinioStorageService: upload/delete plików, idempotentna inicjalizacja bucketu.
- Document + OutboxEntry: encje Spring Data JDBC implementujące Persistable (isNew() kontroluje INSERT vs UPDATE; @PersistenceCreator dla konstruktora odczytu z DB).
- EntityTimestampCallback: BeforeConvertCallback automatycznie ustawia created_at/updated_at przed zapisem.
- DocumentService: orkiestracja upload (MinIO → DB transaction → kompensacja). TransactionTemplate zamiast @Transactional (self-invocation problem). Wzorzec Transactional Outbox — dokument i outbox w jednej transakcji.
- DocumentController: POST /api/documents/upload (multipart), GET /api/documents/{id} (status).
- GlobalExceptionHandler: mapowanie wyjątków na HTTP 400/404/503.

Testy (32 nowe, łącznie 55 w projekcie):
- DocumentServiceTest (15 testów): happy path upload, walidacja wejścia, wyszukiwanie, pełny stan (DB + outbox + MinIO), kompensacja.
- MinioStorageServiceTest (8 testów): inicjalizacja bucketu, upload, delete, edge cases.
- DocumentControllerTest (9 testów): endpointy REST, kody HTTP, obsługa błędów.

Konfiguracja:
- application.properties: PostgreSQL, MinIO, Flyway, multipart (100MB limit).
- podman-compose.yml: serwisy postgres + minio + docuclarity z volumes.
- Testcontainers: MinIOContainer + @DynamicPropertySource w testach.

Decyzje:
- CHECK constraints zamiast enumów PostgreSQL — Spring Data JDBC mapuje String na varchar, natywne enumy wymagałyby custom converterów.
- TEXT zamiast JSONB dla payload outbox — ten sam powód.
- TransactionTemplate zamiast @Transactional — self-invocation w Spring AOP omija proxy.
- Persistable z @PersistenceCreator — pozwala na ustawienie ID przed save (storageKey zawiera UUID) bez konwersji INSERT na UPDATE.

15. Etap 2 — Ekstrakcja PDFBox (ukończony)

Cel: ekstrakcja warstwy tekstowej z plików PDF strona po stronie przy użyciu Apache PDFBox 3.0.8,
zgodnie z sekcją 5 karty projektu (PDFBox jako pierwszy etap kaskady ekstrakcji).

Zaimplementowane komponenty:
- PdfPageText (record): wynik ekstrakcji per strona — pageNum (1-based), text, charCount,
  wordCount, textPresent (czy strona zawiera jakikolwiek tekst).
- PdfTextExtractionResult (record): wynik zbiorczy per dokument — pageCount, List<PdfPageText>,
  combinedText (tekst wszystkich stron z textPresent połączony separatorami \n\n).
- PdfTextExtractionException (RuntimeException): błędy ekstrakcji (uszkodzony PDF, zaszyfrowany
  bez hasła, błędy wejścia/wyjścia).
- PdfTextExtractionService (@Service): ekstrakcja przez PDFTextStripper.
  Dwa przeładowania: extractText(byte[]) oraz extractText(InputStream).
  Przetwarzanie strona po stronie — dla każdej strony ustawiany setStartPage/setEndPage na ten sam
  indeks, strip() zwraca tekst jednej strony. Liczenie słów przez split po \s+.
  PDDocument ładowany przez Loader.loadPDF (PDFBox 3.x API), zamykany przez try-with-resources.

Testy (12 jednostkowych, łącznie 67 w projekcie):
- PdfTextExtractionServiceTest (bez Springa/TC, PDF-y generowane w pamięci przez PDFBox):
  Ekstrakcja z tablicy bajtów: pojedyncza strona z tekstem, wiele stron, pusta strona (textPresent=false),
  plik mieszany (strona z tekstem + pusta), null, pusta tablica, niepoprawne dane (nie PDF).
  Ekstrakcja z InputStream: strumień w pamięci, plik z dysku (@TempDir), null stream.
  Metryki tekstu: liczba słów i znaków, nadmiarowe spacje (split \s+ liczy poprawnie).

Decyzje:
- Ekstrakcja per strona od pierwszej implementacji — ułatwia routing jakościowy (Etap 3) bez refaktoringu.
- textPresent informuje tylko o obecności tekstu, nie o jego jakości — ocena jakości należy do
  PageQualityEvaluator (Etap 3), zgodnie z zasadą "textPresent == true ≠ dobra jakość".
- PDFBox 3.x API: Loader.loadPDF zamiast PDDocument.load, Standard14Fonts.FontName w PDType1Font.
- Brak zależności od Springa w testach — testy czystej logiki w izolacji.

16. Etap 3 — Scoring strony (ukończony)

Cel: per-stronowa ocena jakości tekstu z PDFBox i decyzja routingu (bez OCR vs wymagany OCR),
zgodnie z sekcją 5 karty projektu (textPresent ≠ dobra jakość → potrzebny scoring).

Zaimplementowane komponenty:
- RoutingDecision (enum): PDFBOX, OCR_REQUIRED, LLM_REVIEW (zarezerwowane, Etap 8), MANUAL_REVIEW (zarezerwowane).
- PageQualityScore (record): metryki strony (charCount, wordCount, replacementCharCount, alphaRatio,
  avgWordLength), złożony wynik score (0–1), lista ostrzeżeń oraz decyzja routingu.
- PageQualityEvaluator (service): scoring tekstu z PDFBox.
  Czynniki (każdy 0–1, wagi): liczba słów * 0.50, proporcja znaków alfanumerycznych * 0.20,
  kara za znaki zastępcze U+FFFD * 0.20, średnia długość słowa * 0.10.
  Brak tekstu (textPresent=false) wymusza score=0 → OCR_REQUIRED.
  Decyzja: score >= acceptThreshold → PDFBOX, w przeciwnym razie OCR_REQUIRED.
  Konstruktor 4-argumentowy (Spring @Value) + bezargumentowy (domyślne progi, dla testów jednostkowych).
- Konfiguracja progów w application.properties (docuclarity.quality.*):
  accept-threshold=0.85, min-word-count=5, ideal-word-count=20, max-replacement-ratio=0.05
  (wartości robocze — wymagają kalibracji na realnym korpusie).

Testy (14 nowych, łącznie 81 w projekcie):
- PageQualityEvaluatorTest: pusta strona, dobry tekst (PDFBOX), tekst z U+FFFD (obniżony score + warning),
  próg decyzji (custom acceptThreshold), ostrzeżenie "Za mało słów", alphaRatio, avgWordLength,
  evaluate(PdfTextExtractionResult) wsadowo, null page/result, walidacja zakresów konstruktora,
  monotoniczność kary za U+FFFD.

Decyzje:
- LLM_REVIEW / MANUAL_REVIEW są w enumie, ale decyduje o nich wyższa warstwa pipeline (po OCR
  lub na podstawie confidence) — Etap 3 zwraca tylko PDFBOX / OCR_REQUIRED.
- Progi jakości skonfigurowane przez @Value (jak OCR), nie przez @ConfigurationProperties — spójnie
  z istniejącymi serwisami. Wartości to punkt wyjścia do kalibracji.

17. Etap 4 — OCR Tess4J (ukończony)

Cel: OCR stron PDF bez warstwy tekstowej (lub o niskiej jakości) przy użyciu Tess4J (Tesseract 5.x),
zgodnie z sekcją 5 karty projektu (renderowanie strony → Tesseract → per-word confidence).

Zaimplementowane komponenty:
- OcrWord (record): pojedyncze słowo rozpoznane przez Tesseract — text, confidence (0–100),
  bbox [x, y, width, height] (może być null).
- OcrPageResult (record): wynik OCR per strona — pageNum (1-based), text (pełny tekst),
  List<OcrWord>, meanConfidence (średnia wszystkich słów, 0 gdy brak), textPresent.
- OcrException (RuntimeException): błędy OCR (uszkodzony PDF, błąd Tesseract, nullowe wejście).
- Tess4jOcrService (@Service): renderowanie strony PDF → Tesseract OCR.
  Konstruktor 4-argumentowy (@Value): renderDpi (300), tessdataPath (/usr/share/tessdata),
  language (eng), pageSegMode (1 = automatic with OSD).
  Trzy metody publiczne:
    ocrPage(byte[] pdfBytes, int pageIndex) — render strony z PDF + OCR.
    ocrPage(InputStream, int pageIndex) — jw. ze strumieniem.
    ocrImage(BufferedImage, int pageNum) — OCR bezpośrednio na obrazie (bez renderowania PDF).
  Renderowanie: PDFRenderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB).
  OCR: ITesseract.doOCR(image) dla pełnego tekstu + getWords(image, PageIteratorLevel.WORD)
  dla per-word confidence. Puste słowa odfiltrowywane. meanConfidence = suma / liczba słów.

Testy (10 integracyjnych, łącznie 91 w projekcie):
- Tess4jOcrServiceTest (gated na TESSDATA_PATH — pomijane bez Tesseract):
  OCR na obrazie: tekst rozpoznany (case-insensitive), pusty obraz (textPresent false lub brak słów),
  per-word confidence w zakresie 0–100, null obraz → wyjątek.
  OCR na stronie PDF: strona z tekstem (render + OCR), indeks poza zakresem, null PDF,
  pusty PDF, niepoprawne dane, ujemny indeks.
  Obrazy testowe generowane w pamięci przez Java2D (wysoki kontrast, duża czcionka).

Decyzje:
- Tess4J 5.20.0 + natywny Tesseract 5.5.0 w kontenerze Podman (apt: tesseract-ocr-eng + tesseract-ocr-pol).
  Brak osobnego mikserwisu OCR — działa wewnątrz JVM przez JNA.
- Testy gated na TESSDATA_PATH — nie zawodzą na CI bez Tesseract.
- tessdata-path konfigurowalny przez TESSDATA_PREFIX (kontener: /usr/share/tesseract-ocr/5/tessdata,
  lokalnie: /usr/share/tessdata). Język domyślnie eng+pol.
- Routing jakościowy (kiedy używać OCR) realizowany przez wyższą warstwę (PageQualityEvaluator /
  DocumentProcessingService), nie przez ten serwis — serwis tylko wykonuje OCR na żądanie.

18. Etap 5 — Kolejka i worker (ukończony)

Cel: asynchroniczne przetwarzanie dokumentów przez Redis Streams (Transactional Outbox → Redis Streams → worker),
z timeoutami i retry (zgodnie z Etapem 5 karty projektu).

Zaimplementowane komponenty:
- DocumentStatus (enum): UPLOADED/PROCESSING/COMPLETED/FAILED/MANUAL_REVIEW + konwersja do/z kodu tekstowego
  (kolumna documents.status pozostaje TEXT + CHECK constraint, zgodnie z decyzją Etapu 1). Document zyskał
  getStatusEnum()/setStatus(DocumentStatus).
- OutboxPublisher (@Component, @Scheduled fixedDelay 1s): odczyt outbox PENDING → XADD na Redis Streams
  (StreamRecords.string) → oznaczenie PUBLISHED w transakcji DB (TransactionTemplate). Błąd publikacji
  zwiększa attempts; po max-publish-attempts (5) wpis FAILED. Idempotentne (ponowna publikacja PENDING).
- StreamConsumer (@Component): konsument consumer group (XREADGROUP) → delegacja do DocumentProcessingService
  przez TaskExecutor (OCR/Tesseract CPU-bound izolowane od wątku nasłuchu). Po udanym procesie ACK; brak ACK
  przy błędzie → Redis ponownie dostarcza (retry na poziomie streamu).
- DocumentProcessingService (worker logic, bean przez new w QueueConfig): pobiera PDF z MinIO →
  PdfTextExtractionService (PDFBox) → PageQualityEvaluator per strona → dla OCR_REQUIRED: Tess4jOcrService.
  Zapisuje documents/{id}/pages/{nnn}/final.json (ExtractedPageResult) i documents/{id}/result.json
  (DocumentResultSummary). Status: COMPLETED (wszystkie strony tekstowe) lub MANUAL_REVIEW (OCR nie dał tekstu).
  Używa TransactionTemplate (brak self-invocation, jak DocumentService). Idempotentność: COMPLETED pomijany.
- DocumentProcessingException (RuntimeException) — błędy workera.
- QueueConfig (@Configuration, @EnableScheduling, @ConditionalOnProperty docuclarity.queue.enabled):
  RedisTemplate<String,String> (StringRedisSerializer, bean "queueRedisTemplate"), TaskExecutor "processingTaskExecutor"
  (bounded pool, worker-pool-size=4), DocumentProcessingService bean, StreamMessageListenerContainer (start przy
  starcie). Cała sekcja wyłączana przez docuclarity.queue.enabled=false (np. testy uploadu bez Redis).

Retry / timeouty:
- retry przetwarzania: processingAttempts + max-processing-attempts (3); przekroczenie → status FAILED z errorMessage.
- przed FAILED worker wraca do UPLOADED (OutboxPublisher ponownie opublikuje zdarzenie) — retry przez kolejkę.
- Redis: spring.data.redis.timeout/connect-timeout/read-timeout = 3000ms (zapobiegają zawieszeniu przy martwym Redis).
- OCR izolowany w ThreadPoolTaskExecutor (bounded), by nie blokować wątków web/stream.

Testy:
- DocumentProcessingServiceTest (Mockito, bez Springa/TC): routing PDFBOX vs OCR_REQUIRED, MANUAL_REVIEW
  przy braku tekstu OCR, błąd OCR → MANUAL_REVIEW, nieistniejący dokument → wyjątek, idempotentność COMPLETED,
  retry: powrót do UPLOADED (attempts<max) vs FAILED (attempts>=max).
- QueueIntegrationTest (Testcontainers Postgres+Redis+MinIO): upload → outbox PENDING → OutboxPublisher →
  wpis PUBLISHED + rekord w Redis Streams (round-trip outbox→Redis).
- DocuClarityApplicationTests (contextLoads) weryfikuje start pełnego kontekstu z kolejką.

Decyzje:
- Worker działa w TEJ SAMEJ aplikacji Spring Boot (zgodnie z kartą: "Cała aplikacja w jednym kontenerze Podman").
  Konsument i publisher to @Componenty; brak osobnego procesu w Etapie 5 (możliwe do wydzielenia później).
- Użyto blokującego RedisTemplate (spring-data-redis 4.1) + StreamMessageListenerContainer — stabilne API;
  poprawiono sygnatury pod 4.1.0 (receive(Consumer, StreamOffset, listener), StreamRecords.string).
- Wyniki etapów w MinIO wersjonowane (final.json per strona), zgodnie z sekcją 5 i decyzją "nie nadpisujemy".

19. Naprawy po weryfikacji testów Etapu 5 (2026-08-17)

Po uruchomieniu pełnej suity testów wykryto i naprawiono 4 błędy w kodzie produkcyjnym i testach:

1. OutboxPublisher — brak streamKey w StreamRecords (błąd produkcyjny):
   StreamRecords.string(fields) było tworzone bez przypisanego stream key, przez co Redis
   odrzucał operację XADD. Outbox status pozostawał PENDING (testy integracyjne zawodziły).
   Naprawa: .withStreamKey(streamKey) w wywołaniu.

2. MinioConfig — brak JavaTimeModule w ObjectMapper (błąd produkcyjny):
   Bean ObjectMapper (Jackson 2, wymagany przez MinIO SDK) tworzył new ObjectMapper() bez
   rejestracji JavaTimeModule, więc Instant finishedAt w DocumentResultSummary nie serializował
   się. Wyjątek trafiał do handleFailure(), która ustawiała status UPLOADED zamiast
   COMPLETED/MANUAL_REVIEW. Naprawa: dodano jackson-datatype-jsr310 do build.gradle +
   registerModule(new JavaTimeModule()) w bean ObjectMapper i w teście.

3. DocumentProcessingServiceTest — deprecated TransactionCallbackWithoutResult (błąd testów):
   Spring Framework 7 (Spring Boot 4.1) deprecjonuje TransactionCallbackWithoutResult —
   doInTransactionWithoutResult() ma protected access, a executeWithoutResult() przyjmuje teraz
   Consumer<TransactionStatus>. Naprawa: mock przepisany na Consumer<TransactionStatus>;
   invokeCallback() propaguje wyjątki bezpośrednio zamiast owijać je w RuntimeException.

4. DocumentProcessingServiceTest — błędna assercja pustego tekstu (błąd testów):
   OcrRequiredNoTextTest asserting assertFalse(pageJson.contains("\"text\":\"\"")) było błędne —
   pusty tekst jest poprawnie serializowany przez Jackson jako "text":"". Poprawiono na assertTrue.

Zmienione pliki:
- src/main/java/org/dar316/docuclarity/service/OutboxPublisher.java
- src/main/java/org/dar316/docuclarity/config/MinioConfig.java
- build.gradle (dodana zależność com.fasterxml.jackson.datatype:jackson-datatype-jsr310)
- src/test/java/org/dar316/docuclarity/service/DocumentProcessingServiceTest.java

Pełny wynik testów (79 testów, 0 błędów, 10 pominiętych):
- Etap 1: DocumentServiceTest (15), MinioStorageServiceTest (8), DocumentControllerTest (9) — PASS
- Etap 2: PdfTextExtractionServiceTest (12) — PASS
- Etap 3: PageQualityEvaluatorTest (14) — PASS
- Etap 4: Tess4jOcrServiceTest (10, 10 skipped — gated na TESSDATA_PATH) — PASS
- Etap 5: DocumentProcessingServiceTest (8), QueueIntegrationTest (2), DocuClarityApplicationTests (1) — PASS
- Razem: 79 testów, 0 failures, 0 errors, 10 skipped
