"""
Chunking tekstu PDF przed wysłaniem do LLM.

Gemma 2 27B ma kontekst 8192 tokenów (~16000 znaków dla polskiego).
Pojedyncza strona z małą czcionką lub gęstą tabelą może ten limit przekroczyć
chunking pozwala dostarczyć pełny tekst modelowi.

Strategia:
 - per-page - każda strona analizowana osobno
 - krótka strona - jeden chunk, jeden call do LLM
 - długa strona
    a) podział po akapitach (preferowane - nie tnie zdań)
    b) jeśli pojedynczy akapit > limit -> podział po słowach z overlap
"""

import logging
import re
from dataclasses import dataclass
from typing import List, Optional

log = logging.getLogger(__name__)

# Polski: ~3 znaki na token (bezpieczny margines)
CHARS_PER_TOKEN = 3

#Gemma 2 27B = 8192 tokenów , ~5400 pozostawiamy na input
DEFAULT_MAX_CHARS = 5400 * CHARS_PER_TOKEN # 16 200 znaków

#Overlap przy word-based split - ostatnie N słów chunka jest
#pierwszymi N słów następnego (zapobiega utracie kontekstu na granicy)
WORD_OVERLAP = 30

_PARA_SPLIT_RE = re.compile(r"\n\s*\n")
_WIN_LINEEND_RE = re.compile(r"\r\n|\r")

@dataclass
class TextChunk:
    """Pojedynczy fragment tekstu przygotowany dla LLM."""
    text: str
    chunk_id: int               # globalny, w ramach dokumentu
    page_num: int               # 1-based
    chunk_in_page: int          # 1-based, w ramach strony
    total_chunks_in_page: int   # ile chunków w sumie na tej stronie
    char_start: int             # offset w oryginale
    char_end: int

    @property
    def token_estimate(self) -> int:
        return len(self.text) // CHARS_PER_TOKEN

    @property
    def needs_chunking_reason(self) -> str:
        if self.total_chunks_in_page == 1:
            return ""
        return (
            f"page={self.page_num} ({self.chunk_in_page}/"
            f"{self.total_chunks_in_page}, {self.token_estimate} tokens)"
        )


def chunk_page(
        text: str,
        page_num: int,
        max_chars: int = DEFAULT_MAX_CHARS
) -> List[TextChunk]:
    """
    Dzieli tekst pojedynczej strony na chunki mieszczące się w oknie LLM.
    :param text:
    :param page_num:
    :param max_chars:
    :return:  Lista TextChunk. Dla krótkiej strony: 1 chunk.
        Dla bardzo długiej (mała czcionka, gęsty layout): wiele chunków
    """

    text = _WIN_LINEEND_RE.sub("\n", text).strip()
    if not text:
        return []

    if len(text) <= max_chars:
        return [TextChunk(
            text=text,
            chunk_id=1,
            page_num=page_num,
            chunk_in_page=1,
            total_chunks_in_page=1,
            char_start=0,
            char_end=len(text),
        )]

    # Strona za długa - najpierw próbuj podział po akapitach
    paragraphs = _split_paragraphs(text)
    chunks: List[TextChunk] = []
    buffer: List[str] = []
    buffer_len: int = 0

    for para in paragraphs:
        para_len = len(para) + 2

        # Pojedynczy akapit przekracza limit - word-based z overlap
        if para_len > max_chars:
            if buffer:
                chunks.append(_flush(buffer, page_num, len(chunks) + 1))
                buffer, buffer_len = [], 0

            word_chunks = _split_by_words(para, max_chars)

            for wc_text in word_chunks(para, max_chars)
                chunks.append(TextChunk(
                    text=wc_text,
                    chunk_id=len(chunks) + 1,
                    page_num=page_num,
                    chunk_in_page=len(chunks) + 1,
                    total_chunks_in_page=0,
                    char_start=0,
                    char_end=len(wc_text),
                ))
            continue

        if buffer_len + para_len > max_chars:
            chunks.append(_flush(buffer, page_num, len(chunks) + 1))

        total = len(chunks)

        for c in chunks:
            c.total_chunks_in_page = total

        log.info(
            "Page %d chunked: %d chunks (original %d chars, max %d per chunk",
            page_num, total, len(text), max_chars,
        )
        return chunks