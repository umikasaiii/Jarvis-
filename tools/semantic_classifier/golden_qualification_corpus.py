"""§ JARVIS Implementation Master Plan — PASSAGGIO 14B §11/§12. The FIXED
deterministic golden corpus tokenizer/encoder qualification runs against.

Fixed means: never regenerated, never sampled randomly, same list every run
— so a qualification report is directly comparable run-to-run and machine-
to-machine (the whole point of a qualification GATE, not a benchmark).
Deliberately small (representative, not exhaustive): ordinary Italian
sentences plus the specific edge cases §11/§12 call out (empty string,
very long input that must truncate at [MAX_SEQUENCE_LENGTH], accented/NFC-
sensitive text, punctuation-only, emoji/non-ASCII, a string composed only of
whitespace).
"""
from __future__ import annotations

MAX_SEQUENCE_LENGTH = 256  # § SemanticEncoderContract.CURRENT / EmbeddingGemmaEngine.kt's own SEQUENCE_LENGTH constant

GOLDEN_TEXTS: list[str] = [
    "Che tempo fa domani?",
    "Ricordami di comprare il latte",
    "Quanti impegni ho questa settimana?",
    "Accendi la torcia",
    "Quanta RAM ha questo telefono?",
    "Buongiorno",
    "",  # empty string — must never crash the tokenizer/encoder
    "   ",  # whitespace-only
    "È già mezzanotte, che ore sono a Roma?",  # NFC-sensitive accented characters (è, à)
    "Perché non funziona l'automazione della pioggia?",
    "!!! ??? ...",  # punctuation-only
    "😀 meteo domani 🌧️",  # emoji / non-ASCII
    "Ciao, come stai oggi? Volevo sapere se hai controllato l'agenda per la prossima settimana, "
    "in particolare l'appuntamento di giovedì pomeriggio, e se il meteo previsto per il weekend "
    "cambia qualcosa nei piani, dato che avevamo parlato di un'eventuale gita fuori porta e non "
    "vorrei ritrovarmi impreparato all'ultimo momento con la pioggia o il freddo improvviso.",  # long, exceeds a short max
    "A" * 2000,  # forces truncation at MAX_SEQUENCE_LENGTH regardless of tokenizer efficiency
    "test",
    "Attiva modalità pro",
    "1234567890",
    "il gatto è sul tavolo",
    "Il Gatto È Sul Tavolo",  # casing must be preserved, never lowercased silently
]


def golden_index(text: str) -> int:
    """Stable position of [text] in [GOLDEN_TEXTS] — used to key qualification
    report rows so two runs can be diffed by index, not by re-matching text."""
    return GOLDEN_TEXTS.index(text)
