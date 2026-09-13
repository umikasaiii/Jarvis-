<#
.SYNOPSIS
  JARVIS Implementation Master Plan — PASSAGGIO 14B. ONE-COMMAND Windows
  runner for real EmbeddingGemma artifact qualification + frozen-encoder
  embedding generation + Learned Head training + export.

.DESCRIPTION
  Designed for a weak Windows 10 laptop (the project's own reference
  hardware: AMD 3020e, 2 cores/2 threads, 8 GB RAM, ~5.88 GB usable) — CPU
  only, no Conda, no Docker, no admin rights, pinned dependencies, a plain
  Python virtual environment.

  Runs, IN ORDER, stopping immediately (fail-closed) on the first failure:
    1. dependency + venv preflight
    2. artifact presence check (you must have already placed the two real
       files under -ModelDir — see README.md for the official source)
    3. artifact_manifest.py    — hash + record provenance
    4. tokenizer_qualification.py  — TOKENIZER_GATE
    5. encoder_qualification.py    — ENCODER_GATE
    6. preflight.py                — full fail-closed preflight
    7. generate_embeddings.py      — real embeddings, TRAIN/VALIDATION/TEST only (never BLIND)
    8. export.py --real             — frozen-encoder head training + export (REAL_TRAINED, CalibrationStatus=PENDING)
    9. run_test_protocol.py         — the ONE-TIME TEST evaluation
   10. build_bundle.py              — verification bundle
   11. write_receipt.py             — the final PASSAGGIO_14_REAL_EXECUTION receipt

  NOTHING here ever calibrates confidence/OOD thresholds or touches BLIND —
  that is PASSAGGIO 15's scope, deliberately untouched by this script.

.PARAMETER ModelDir
  Directory containing the two real artifact files you downloaded from the
  official source (see README.md's "Acquiring the real artifact" section).
  Expected filenames: a `.tflite` model file and a SentencePiece `.model`
  tokenizer file — pass their exact names via -ModelFileName/-TokenizerFileName
  if they differ from the defaults below.

.PARAMETER OutputDir
  Where every report/manifest/export this run produces is written. Created
  if missing. Never inside the git repository's tracked tree by default —
  point it at `tools/semantic_classifier/real_run/` (gitignored) or anywhere
  else you like.

.PARAMETER Resume
  Skip steps whose output file already exists in -OutputDir (embedding
  generation is ALSO naturally resumable at the per-example level via its
  own on-disk cache, regardless of this flag).

.PARAMETER Force
  Passed through to the TEST protocol step only — allows re-evaluating a
  DIFFERENT frozen candidate after a genuine fix/re-train. Never use this to
  retry the SAME candidate hoping for a better TEST number (§17).

.EXAMPLE
  # First time, from the repository root, PowerShell:
  cd tools\semantic_classifier
  .\run_real_training.ps1 -ModelDir "C:\Users\you\Downloads\embeddinggemma-300m" -OutputDir ".\real_run"

.EXAMPLE
  # Resuming after an interrupted run:
  .\run_real_training.ps1 -ModelDir "C:\Users\you\Downloads\embeddinggemma-300m" -OutputDir ".\real_run" -Resume
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ModelDir,

    [string]$OutputDir = ".\real_run",
    [string]$ModelFileName = "embeddinggemma-300M_seq256_mixed-precision.tflite",
    [string]$TokenizerFileName = "tokenizer.model",
    [string]$OfficialSource = "https://huggingface.co/litert-community/embeddinggemma-300m",
    [string]$VenvDir = ".\.venv_pass14b",
    [switch]$Resume,
    [switch]$Force,
    [int]$Threads = 2,          # § §15 — conservative default for a 2-core/2-thread laptop; forwarded only where a script actually reads it
    [int]$BatchSize = 1         # § §15 — the .tflite graph is a single-example forward pass by contract; kept as a documented, honest no-op flag, not fake flexibility
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) {
    Write-Host ""
    Write-Host "=== $msg ===" -ForegroundColor Cyan
}

function Assert-LastExitCodeZero($stepName) {
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAILED at step: $stepName (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit 1
    }
}

# --- 0. Locate a real Python interpreter (no Conda) ---
Write-Step "0/11 — locating Python"
$python = $null
foreach ($candidate in @("py -3", "python3", "python")) {
    $parts = $candidate.Split(" ")
    $exe = $parts[0]
    if (Get-Command $exe -ErrorAction SilentlyContinue) {
        $python = $candidate
        break
    }
}
if (-not $python) {
    Write-Host "No Python interpreter found on PATH (tried py -3, python3, python). Install Python 3.10+ from python.org and re-run." -ForegroundColor Red
    exit 1
}
Write-Host "Using: $python"

# --- 1. Virtual environment (no Conda, no admin) ---
Write-Step "1/11 — virtual environment"
if (-not (Test-Path $VenvDir)) {
    Invoke-Expression "$python -m venv `"$VenvDir`""
    Assert-LastExitCodeZero "venv creation"
}
$venvPython = Join-Path $VenvDir "Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
    Write-Host "Expected venv Python at $venvPython not found." -ForegroundColor Red
    exit 1
}

# --- 2. Pinned dependencies (§8: pinned, conservative, CPU-only) ---
Write-Step "2/11 — dependencies (pinned, CPU-only)"
$requirements = @(
    "numpy>=1.26,<2.0",
    "scikit-learn>=1.3,<1.6",
    "sentencepiece>=0.2.0,<0.3.0",
    "protobuf>=4.25,<6.0",
    "ai-edge-litert>=1.0.0,<2.0.0"
)
& $venvPython -m pip install --quiet --upgrade pip
& $venvPython -m pip install --quiet $requirements
Assert-LastExitCodeZero "dependency install"

# --- 3. Artifact presence check (§7 — acquisition itself stays manual/official) ---
Write-Step "3/11 — real artifact presence"
$modelPath = Join-Path $ModelDir $ModelFileName
$tokenizerPath = Join-Path $ModelDir $TokenizerFileName
if (-not (Test-Path $modelPath) -or -not (Test-Path $tokenizerPath)) {
    Write-Host "Real artifact files not found:" -ForegroundColor Red
    Write-Host "  model:     $modelPath"
    Write-Host "  tokenizer: $tokenizerPath"
    Write-Host ""
    Write-Host "Download the real EmbeddingGemma artifact from the OFFICIAL source before running this script:"
    Write-Host "  $OfficialSource"
    Write-Host "See README.md's 'Acquiring the real artifact' section for exact file names and licensing notes."
    Write-Host "Never use a third-party mirror/reupload (§7)."
    exit 1
}
Write-Host "Found model:     $modelPath"
Write-Host "Found tokenizer: $tokenizerPath"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$manifestPath = Join-Path $OutputDir "artifact_manifest.json"
$tokenizerReportPath = Join-Path $OutputDir "tokenizer_qualification_report.json"
$encoderReportPath = Join-Path $OutputDir "encoder_qualification_report.json"
$embedCachePath = Join-Path $OutputDir "embedding_cache.real.json"
$embedReportPath = Join-Path $OutputDir "embedding_generation_report.json"
$thresholdsPath = Join-Path $OutputDir "thresholds.json"
$headWeightsPath = Join-Path $OutputDir "head_weights.json"
$testReportPath = Join-Path $OutputDir "test_protocol_report.json"
$bundlePath = Join-Path $OutputDir "verification_bundle"

function Step-Skippable($outputPath) {
    return ($Resume -and (Test-Path $outputPath))
}

# --- 4. Artifact manifest (hash + provenance) ---
Write-Step "4/11 — artifact manifest"
if (Step-Skippable $manifestPath) {
    Write-Host "SKIPPED (Resume, already exists): $manifestPath"
} else {
    & $venvPython artifact_manifest.py --model-path "$modelPath" --tokenizer-path "$tokenizerPath" `
        --official-source "$OfficialSource" --out "$manifestPath"
    Assert-LastExitCodeZero "artifact_manifest.py"
}

# --- 5. TOKENIZER_GATE ---
Write-Step "5/11 — TOKENIZER_QUALIFICATION gate"
if (Step-Skippable $tokenizerReportPath) {
    Write-Host "SKIPPED (Resume, already exists): $tokenizerReportPath"
} else {
    & $venvPython tokenizer_qualification.py --manifest "$manifestPath" --out "$tokenizerReportPath"
    Assert-LastExitCodeZero "tokenizer_qualification.py (TOKENIZER_GATE)"
}

# --- 6. ENCODER_GATE ---
Write-Step "6/11 — ENCODER_QUALIFICATION gate"
if (Step-Skippable $encoderReportPath) {
    Write-Host "SKIPPED (Resume, already exists): $encoderReportPath"
} else {
    & $venvPython encoder_qualification.py --manifest "$manifestPath" --tokenizer-report "$tokenizerReportPath" --out "$encoderReportPath"
    Assert-LastExitCodeZero "encoder_qualification.py (ENCODER_GATE)"
}

# --- 7. Full fail-closed preflight ---
Write-Step "7/11 — preflight"
& $venvPython preflight.py --manifest "$manifestPath" --tokenizer-report "$tokenizerReportPath" `
    --encoder-report "$encoderReportPath" --output-dir "$OutputDir" --embedding-cache "$embedCachePath"
Assert-LastExitCodeZero "preflight.py"

# --- 8. Real embedding generation (TRAIN/VALIDATION/TEST only) ---
Write-Step "8/11 — real embedding generation (train/validation/test — BLIND is never touched)"
& $venvPython generate_embeddings.py --manifest "$manifestPath" --cache "$embedCachePath" --out "$embedReportPath"
Assert-LastExitCodeZero "generate_embeddings.py"

# --- 9. Frozen-encoder Learned Head training + export ---
Write-Step "9/11 — training + export (REAL_TRAINED, CalibrationStatus=PENDING)"
if (Step-Skippable $headWeightsPath) {
    Write-Host "SKIPPED (Resume, already exists): $headWeightsPath"
} else {
    & $venvPython export.py --real --manifest "$manifestPath" --tokenizer-report "$tokenizerReportPath" `
        --encoder-report "$encoderReportPath" --out-dir "$OutputDir" --cache "$embedCachePath"
    Assert-LastExitCodeZero "export.py --real"
}

# --- 10. ONE-TIME TEST protocol ---
Write-Step "10/11 — ONE-TIME TEST protocol"
$testArgs = @("run_test_protocol.py", "--manifest", "$manifestPath", "--cache", "$embedCachePath",
              "--thresholds", "$thresholdsPath", "--head-weights", "$headWeightsPath", "--out", "$testReportPath")
if ($Force) { $testArgs += "--force" }
& $venvPython @testArgs
Assert-LastExitCodeZero "run_test_protocol.py"

# --- 11. Bundle + receipt ---
Write-Step "11/11 — verification bundle + receipt"
& $venvPython build_bundle.py --source-dir "$OutputDir" --bundle-dir "$bundlePath"
Assert-LastExitCodeZero "build_bundle.py"
& $venvPython write_receipt.py --source-dir "$OutputDir" --bundle-path "$bundlePath" `
    --out-json (Join-Path $OutputDir "receipt.json") --out-txt (Join-Path $OutputDir "receipt.txt")
Assert-LastExitCodeZero "write_receipt.py"

Write-Host ""
Write-Host "=== DONE — see $OutputDir\receipt.txt ===" -ForegroundColor Green
Get-Content (Join-Path $OutputDir "receipt.txt")
