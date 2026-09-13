# Real EmbeddingGemma artifact — where it goes

§ JARVIS Implementation Master Plan — PASSAGGIO 14B.

This directory (`tools/semantic_classifier/models/`) is where you place the
**two real files** `run_real_training.ps1` needs. Everything in it except
this README is gitignored — these are large binaries and never belong in
the repository.

## What to download

Official source (per this project's established assumption since FASE
2A.11 — **verify this is still the current publisher-listed EmbeddingGemma
LiteRT/TFLite artifact before downloading; this environment has no network
access to re-confirm it, so it is not independently re-verified here**):

```
https://huggingface.co/litert-community/embeddinggemma-300m
```

Do **not** use a mirror, reupload, or unofficial conversion (§7).

You need exactly two files from that repository:

1. **The `.tflite` model file** — the LiteRT-format encoder (per the
   project's established naming convention, something like
   `embeddinggemma-300M_seq256_mixed-precision.tflite`; if the repo lists a
   different exact filename or several precision variants, pick one and
   pass it explicitly via `-ModelFileName` — pick the CPU-appropriate
   variant since this laptop has no GPU/NPU delegate assumed).
2. **The SentencePiece tokenizer file** — typically `tokenizer.model` in
   repositories of this shape. If the repo instead only exposes a
   `tokenizer.json`/`tokenizer_config.json` (a `transformers`-style
   tokenizer rather than a bare `.model` file), `tokenizer_qualification.py`
   as written expects the bare SentencePiece `.model` — check the repo's
   file list for one before running; if truly absent, this is a real gap to
   report back rather than to route around by substituting a different
   tokenizer.

If either file requires accepting a license/gated-access agreement on
Hugging Face: accept it there in your own account first (never embed any
token/credential in this repository or in any manifest file it produces).

## Where to put them

```
tools/semantic_classifier/models/
├── README.md                                          (this file, tracked)
├── embeddinggemma-300M_seq256_mixed-precision.tflite   (you place this — gitignored)
└── tokenizer.model                                     (you place this — gitignored)
```

Then run, from `tools/semantic_classifier/`:

```powershell
.\run_real_training.ps1 -ModelDir ".\models" -OutputDir ".\real_run"
```

If your files have different names, pass `-ModelFileName`/`-TokenizerFileName`
explicitly — see `run_real_training.ps1`'s own help (`Get-Help
.\run_real_training.ps1 -Full`).
