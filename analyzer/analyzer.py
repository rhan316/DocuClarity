"""
Klient LLM (OpenRouter.ai) i parser odpowiedzi.
"""

import json
import logging
import os
from datetime import datetime, timezone
from typing import Optional

import httpx
from schema import ANALYSIS_SCHEMA
import json_repair

from prompts import SYSTEM_PROMPT, build_user_prompt

log = logging.getLogger(__name__)

class LlmClient:
    """Klient OpenRouter.ai dla modelu Gemma 4."""

    def __init__(self):
        self.api_url = os.getenv(
            "LLM_API_URL",
            "https://openrouter.ai/api/v1/chat/completions"
        )
        self.api_key = os.getenv("OPENROUTER_API_KEY", "")
        self.model = os.getenv("LLM_MODEL", "google/gemma-2-27b-it")
        self.timeout = int(os.getenv("LLM_TIMEOUT_SECONDS", "300"))
        self.max_tokens = int(os.getenv("LLM_MAX_TOKENS", "4096"))
        self.temperature = float(os.getenv("LLM_TEMPERATURE", "0.2"))

        if not self.api_key:
            log.warning("OPENROUTER_API_KEY not set — LLM calls will fail")

    def analyze(self, extracted_text: str) -> Optional[dict]:
        """
        Wysyła tekst do LLM i zwraca sparsowany wynik analizy.
        :param extracted_text:
        :return: dict z polami: plainText, summary, pitfalls, None gdy błąd
        """

        if not extracted_text or not extracted_text.strip():
            log.error("Empty extracted text, skipping LLM analysis")
            return None

        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(extracted_text)}
        ]

        log.info("Sending LLM request to %s (model=%s, text_len=%d chars)",
                self.api_url, self.model, len(extracted_text)
                )

        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.post(
                    self.api_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                        "HTTP-Referer": "https://docuclarity.local",
                        "X-Title": "DocuClarity",
                    },
                    json={
                        "model": self.model,
                        "messages": messages,
                        "max_tokens": self.max_tokens,
                        "temperature": self.temperature,
                        "response_format": {"type": "json_object"},
                    },
                )
                response.raise_for_status()

                data = response.json()
                content = data["choices"][0]["message"]["content"]
                log.info("LLM response received (content_len=%d)", len(content))

                return self._parse_response(content)

        except httpx.TimeoutException:
            log.error("LLM request timed out after %ds", self.timeout)
            return None
        except httpx.HTTPStatusError as e:
            log.error("LLM API error %d: %s", e.response.status_code, e.response.text[:500])
            return None
        except Exception as e:
            log.error("LLM request failed: %s", e, exc_info=True)
            return None

    def _parse_response(self, content: str) -> Optional[dict]:
        # 1. Standard JSON
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            log.info("Standard JSON parse failed, using json_repair fallback")

        # 2. json-repair z schema guidance
        try:
            data = json_repair.repair_json(
                content,
                schema=ANALYSIS_SCHEMA,
                return_objects=True,
                schema_repair_mode="salvage",
                ensure_ascii=False
            )
            if data:
                log.info("json_repair parsed malformed JSON with schema guidance")
                return data
        except Exception as e:
            log.error("json_repair with schema failed: %s", e)

        # 3. json_repair bez schema (last resort)
        try:
            data = json_repair.loads(content)
            if data:
                log.warning("json_repair parsed without schema - structure may be incomplete")
                return data
        except Exception as e:
            log.error("json_repair without schema also failed: %s", e)

        return None

