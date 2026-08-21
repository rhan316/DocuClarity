"""
Weryfikacja cytatów w wykrytych kruczkach prawnych (anti-hallucination).

Strategia warstwowa - od najsurowszego do najbardziej wyrozumiałego:

1. EXACT -> dosłowny podciąg w oryginale (zero transformacji)
2. NORMALIZED -> podciąg po normalizacji (spacje, myślniki, cudzysłowy,
        wielkość liter; wariant rozszerzony: bez interpunkcji)
3. FUZZY - dopsaowanie przybliżone, akceptowane WYŁĄCZNIE gdy różnice są niewielkie
        (wysoki próg, tylko długie cytaty)

Dodatkowo: bramka liczbowa - wszystkie liczby w ciągu (kwoty, daty, procenty)
muszą występować w źródle. Chroni przed najgroźniejszym przypadkiem:
    cytatem prawie identycznym, ale z podmienioną kwotą.

Każdy zaakceptowalny kruczek dostaje pole 'verification` (EXACT, NORMALIZED, FUZZY)
    audiotywność wiarogodności cytatu downstream.

Zasada bezpieczeństwa: przy wątpliwościach ODRZUCAMY. Odrzucenie prawdziwego kruczka
jest kosztowne, ale bezpiezczne; zaakceptowanie zmyślonego - niebiezpieczne.
"""

import logging
import re
from difflib import SequenceMatcher
from typing import Optional

log = logging.getLogger(__name__)

# --- Poziomy weryfikacji (zapisywane jako pitfall["verification"]
EXACT = "EXACT"
NORMALIZED = "NORMALIZED"
FUZZY = "FUZZY"

# Próg fuzzy - wysoki, bo akceptujemy tylko drobne różnice (OCR, literówki)
# 0.85 byłoby zbyt pobłaźliwe: zmyslony cytat może być podobny w 85% do oryginału,
# zwierając inną kwotę czy termin.
DEFAULT_FUZZY_THRESHOLD = 0.92

# Krótsze cytaty nie przechodzą warstwy fuzzy - przy 20 znakach jedno słowo
# różnicy to już istotna zmiana treści. Muszą matchować determistycznie.
MIN_FUZZY_QUOTE_LENGTH = 25

# Normalizacja znaków typowych dla OCR / różnych edytorów
_DASHES = "\u2013\u2014\u2212"
_QUOTES = "\u201e\u201d\u00ab\u00bb\u201a\u2018\u2019\u201c"  # „ ” « » ‚ ' ' "
_PUNCT_RE = re.compile(r"[^\w\s]", re.UNICODE)
_NUMBER_RE = re.compile(r"\d+")
_POLISH_MONTHS = {
    "stycznia": "01",   "styczen": "01",    "sty": "01",
    "lutego": "02",     "luty": "02",       "lut": "02",
    "marca": "03",      "marzec": "03",     "mar": "03",
    "kwietnia": "04",   "kwiecien": "04",   "kwi": "04",
    "maja": "05",       "maj": "05",
    "czerwca": "06",    "czerwiec": "06",   "cze": "06",
    "lipca": "07",      "lipiec": "07",     "lip": "07",
    "sierpnia": "08",   "sierpien": "08",   "sie": "08",
    "wrzesnia": "09",   "wrzesien": "09",   "wrz": "09",
    "pazdziernika": "10","pazdziernik": "10","paz": "10",
    "listopada": "11",  "listopad": "11",   "lis": "11",
    "grudnia": "12",    "grudzien": "12",   "gru": "12",
}
_MONTH_RE = re.compile(
    r'\b(' + '|'.join(re.escape(m) for m in _POLISH_MONTHS) + r')\b',
    re.IGNORECASE
)

def verify_quotes(
        result: dict,
        source_text: str,
        fuzzy_threshold: float = DEFAULT_FUZZY_THRESHOLD,
        min_fuzzy_length: int = MIN_FUZZY_QUOTE_LENGTH,
) -> dict:
    """
    Filtruje kruczki - zostawia tylko te, których cytat istnieje w oryginale

    :param result: wynik LLM (plainText, summary, pitfalls)
    :param source_text: oryginalny tekst wyekstrahowany z PDF
    :param fuzzy_threshold: próg prawdopobieństwa dla warstwy FUZZY (0-1)
    :param min_fuzzy_length: minimalna długość cytatu dla warstwy FUZZY
    :return: wynik z przefiltrowanych pitfalls; każdy ma pole 'verification`
    """
    pitfalls = result.get("pitfalls") or []
    if not pitfalls:
        return result

    if not source_text or not source_text.strip():
        log.warning("Empty source text - rejecting all %d pitfalls", len(pitfalls))
        result["pitfalls"] = []
        return result

    source_norm = _normalize(source_text)
    source_letters = _strip_punctuation(source_norm)

    verified = []
    for i, pitfall in enumerate(pitfalls, 1):
        title = pitfall.get("title", "untitled")
        quote = (pitfall.get("quote") or "").strip()

        if not quote:
            log.warning("Pitfall #%d ('%s'): empty quote - rejected", i, title)
            continue

        level = _match_level(
            quote, source_text, source_norm, source_letters, fuzzy_threshold, min_fuzzy_length
        )

        if level is None:
            best = _best_similarity(_normalize(quote), source_norm)
            log.warning("Pitfall #%d ('%s'): quote NOT found in source "
                        "(best similarity %.2f, fuzzy threshold %.2f) - rejected",
                        i, title, best, fuzzy_threshold,
                        )
            continue

        if not _numbers_match(quote, source_text):
            log.warning(
                "Pitfall #%d ('%s'): verified as %s but contains numbers "
                "absent from source (hallucinated amount/date?) - rejected",
                i, title, level
            )
            continue

        pitfall["verification"] = level
        verified.append(pitfall)
        log.debug("Pitfall #%d ('%s') verified as %s", i, title, level)

    log.info(
        "Quote verification: %d/%d accepted, %d rejected",
        len(verified), len(pitfalls), len(pitfalls) - len(verified),)

    result["pitfalls"] = verified
    return result


def _match_level(
        quote, source_raw, source_norm, source_letters,
        fuzzy_threshold, min_fuzzy_length
) -> Optional[str]:
    """Przechodzi warstwy od najsurowszej. Zwraca poziom lub None"""

    # Warstwa 1: dosłowny ciąg (zero transformacji)
    if quote in source_raw:
        return EXACT

    # Warstwa 2: dopasowanie po normalizacji
    quote_norm = _normalize(quote)
    if quote_norm and quote_norm in source_norm:
        return NORMALIZED

    # Warstwa 2b: normalizacja + usunięcie interpunkcji
    # Łapie przypadki typu "1.000zł vs 1 000zł", różne przecinki itp.
    quote_letters = _strip_punctuation(quote_norm)
    if quote_letters and quote_letters in source_letters:
        return NORMALIZED

    # Warstwa 3: fuzzy - wyłącznie drobne różnice, tylko długie cytaty
    if len(quote_norm) >= MIN_FUZZY_QUOTE_LENGTH:
        if _fuzzy_contains(quote_norm, source_norm, fuzzy_threshold):
            return FUZZY

    return None


# Light normalization
def _normalize(text: str) -> str:
    text = re.sub(r"\s+", " ", text)

    for ch in _DASHES:
        text = text.replace(ch, "-")

    for ch in _QUOTES:
        text = text.replace(ch, '"')

    return text.lower().strip()

# Aggressive normalization
def _strip_punctuation(text: str) -> str:
    text = _PUNCT_RE.sub(" ", text)
    return re.sub(r"\s+", " ", text).strip()

# Bramka liczbowa - ochrona przed podmianą kwot/dat/procentów
def _numbers_match(quote: str, source: str) -> bool:
    """
    Wszystkie sekwencje cyfr z cytatu muszą występować w źródle.
    Polskie nazwy miesięcy są normalizowane na numbery przed ekstrakcją
    (15 marca 2024 -> 15 03 2024 -> ["15", "03", "2024"]

    :param quote:
    :param source:
    :return:
    """
    quote_norm = _normalize_months(quote.lower())
    source_norm = _normalize_months(source.lower())

    quote_numbers = _NUMBER_RE.findall(quote_norm)
    if not quote_numbers:
        return True
    source_numbers = set(_NUMBER_RE.findall(source_norm))

    return all(n in source_numbers for n in quote_numbers)

def _fuzzy_contains(quote: str, source: str, threshold: float) -> bool:
    """
    Sliding window po źródle. Najpierw tanie górne ograniczenia
    (real_quick_ratio / quick_ratio), pełne ratio tylko gdy są obiecujące

    :param quote:
    :param source:
    :param threshold:
    :return:
    """

    if len(quote) >= len(source):
        return SequenceMatcher(None, quote, source).ratio() >= threshold

    window = len(quote)
    step = max(window // 4, 1)
    matcher = SequenceMatcher(None, quote) # seq1 ustawione raz

    i = 0
    while i + window <= len(source):
        matcher.set_seq2(source[i:i + window])
        if (matcher.real_quick_ratio() >= threshold
            and matcher.quick_ratio()  >= threshold
            and matcher.ratio() >= threshold):
            return True

        i += step

    # Ostatnie okno - gdy step nie wyrównał się z końcem źródła
    matcher.set_seq2(source[-window:])
    return (matcher.real_quick_ratio() >= threshold
            and matcher.quick_ratio() >= threshold
            and matcher.ratio() >= threshold
            )

def _normalize_months(text: str) -> str:
    """Zastępuje polski nazwy miesięcy ich numerami (marca -> 03)."""
    return _MONTH_RE.sub(
        lambda m: _POLISH_MONTHS[m.group().lower()], text
    )

def _best_similarity(quote: str, source: str) -> float:
    """For logs and testing"""
    if not quote or not source:
        return 0.0

    window = min(len(quote), len(source))
    step = max(window // 4, 1)
    best = 0.0

    for i in range(0, max(len(source) - window + 1, 1), step):
        best = max(best, SequenceMatcher(None, quote, source[i:i + window]).ratio())

    return best
