# =============================================================================
# DocuClarity — obraz kontenera z Tesseract OCR (eng + pol)
# =============================================================================
# Multi-stage build:
#   1. builder — kompilacja jar przez Gradle (bez uruchamiania testów)
#   2. runtime — lekki obraz z JRE + tesseract-ocr + traineddata (eng, pol)
#
# Build:  podman build -t docuclarity .
# Run:    podman run --rm -p 8080:8080 docuclarity
# Compose: podman-compose up
# =============================================================================

# --- Stage 1: builder ---
FROM docker.io/library/eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Kopiujemy pliki Gradle i źródła
COPY gradle/ gradle/
COPY gradlew gradlew.bat ./
COPY build.gradle settings.gradle ./
COPY src/ src/

# Kompilujemy i pakujemy jar (bez testów — testy wymagają Tesseract + Testcontainers)
RUN ./gradlew bootJar -x test --no-daemon

# --- Stage 2: runtime ---
FROM docker.io/library/eclipse-temurin:21-jre

# Tesseract OCR z polskimi i angielskimi danymi
# eclipse-temurin bazuje na Ubuntu, więc używamy apt-get
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-eng \
        tesseract-ocr-pol \
    && rm -rf /var/lib/apt/lists/*

# tessdata instalowane przez apt w /usr/share/tesseract-ocr/5/tessdata
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

WORKDIR /app

# Kopiujemy jar ze stage builder
COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
