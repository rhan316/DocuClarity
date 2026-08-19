"""
JSON Schema dla odpowiedzi LLM.
Używana przez json-repair[schema] do naprawy i walidacji
oraz przez analyzer.py do weryfikacji struktury wyniku.
"""

ANALYSIS_SCHEMA = {
    "type": "object",
    "properties": {
        "plainText": {
            "type": "string",
            "description": "Uproszczona wersja dokumentu w zrozumiałym polskim"
        },
        "summary": {
            "type": "object",
            "properties": {
                "who":        {"type": "string"},
                "what":       {"type": "string"},
                "amount":     {"type": "string"},
                "duration":   {"type": "string"},
                "liability":  {"type": "string"},
                "keyDates":   {"type": "string"},
                "other":      {"type": "string"}
            },
            "additionalProperties": False
        },
        "pitfalls": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title":       {"type": "string"},
                    "quote":       {"type": "string"},
                    "explanation": {"type": "string"},
                    "severity": {
                        "type": "string",
                        "enum": ["LOW", "MEDIUM", "HIGH", "CRITICAL"]
                    },
                    "pageNumber": {"type": ["integer", "null"]}
                },
                "required": ["title", "quote", "explanation", "severity"]
            }
        }
    },
    "required": ["plainText", "summary", "pitfalls"],
    "additionalProperties": False
}
