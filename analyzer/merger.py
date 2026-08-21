import logging
import re
from collections import defaultdict
from typing import List, Optional, Iterable

from chunker import TextChunk

log = logging.getLogger(__name__)

SUMMARY_FIELDS = (
    "who", "what", "amount", "duration", "liability", "keyDates", "other"
)

def _merge_summaries(summaries: Iterable[dict]) -> dict:
    merged: dict = {}

    for field in SUMMARY_FIELDS:
        candidates = []
        for s in summaries:
            val = (s.get(field) or "").strip()
            if val:
                candidates.append(val)
        if candidates:
            merged[field] = max(candidates, key=len)

    return merged


def _dedup_pitfalls(pitfalls: List[dict]) -> List[dict]:
    seen: set = set()
    unique: List[dict] = []

    for p in pitfalls:
        quote_norm = _normalize_quote_for_dedup(p.get("quote", ""))
        if not quote_norm:
            unique.append(p)
            continue
        if quote_norm in seen:
            log.debug("Deduplicated pitfall: '%s'", p.get("title", ""))
            continue

        seen.add(quote_norm)
        unique.append(p)

    return unique

def _normalize_quote_for_dedup(quote: str) -> str:
    return re.sub(r"\s+", " ", quote.lower().strip())