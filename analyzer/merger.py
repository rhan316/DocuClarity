import logging
import re
from collections import defaultdict
from typing import List, Optional, Iterable

from chunker import TextChunk

log = logging.getLogger(__name__)

"""
Merging per-chunk LLM results into a simple coherent analysis.

When document is split into multiple chunks, each chunk produces its own
{plainText, summary, pitfalls}. This module merges them:
 - plainText: joined with double newlines (perserves chunk order)
 - summary: per-field, pick the longest non-empty value across chunks
 - pitfalls: deduplicate by normalized quote text (case-insensitive, 
    whitespace-collapsed) before quote verification runs
"""

SUMMARY_FIELDS = (
    "who", "what", "amount", "duration", "liability", "keyDates", "other"
)

def merge_results(results: List[dict]) -> dict:
    """
    Merge list of per-chunk LLM results into one combined result.
    """

    if not results:
        return {
            "plainText": "",
            "summary": {},
            "pitfalls": []
        }

    if len(results) == 1:
        # Single chunk - still run dedup for safety (no-op in practice)
        r = results[0]
        return {
            "plainText": r.get("plainText", ""),
            "summary": r.get("summary") or {},
            "pitfalls": _dedup_pitfalls(r.get("pitfalls") or []),
        }

    plain_texts = [r.get("plainText", "") for r in results]
    summaries = [r.get("summary") or {} for r in results]
    all_pitfalls: List[dict] = []

    for r in results:
        all_pitfalls.extend(r.get("pitfalls") or [])

    merged_pitfalls = _dedup_pitfalls(all_pitfalls)
    merged_summary = _merge_summaries(summaries)

    log.info(
        "Merged %d chunk results: plainText=%d chars, pitfalls=%d (was %d before dedup)",
        len(results),
        sum(len(t) for t in plain_texts),
        len(merged_pitfalls),
        len(all_pitfalls),
    )

    return {
        "plainText": "\n\n".join(plain_texts),
        "summary": merged_summary,
        "pitfalls": merged_pitfalls,
    }
    

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