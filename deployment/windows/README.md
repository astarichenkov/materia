# Windows deployment — Materia (Days 27–29)

## Prerequisites

| Tool | Notes |
|------|-------|
| JDK 25 | set `JAVA_HOME` |
| FFmpeg | `FFMPEG_PATH` / `FFPROBE_PATH` or on `PATH` |
| whisper.cpp | `WHISPER_PATH` (`whisper-cli.exe`) + `WHISPER_MODEL_PATH` |
| Ollama | `qwen3:4b`, embedding model (e.g. `nomic-embed-text`) |

## Reproducible tool install

```powershell
./scripts/install-day27-tools.ps1          # FFmpeg + whisper.cpp + model
winget install Ollama.Ollama               # Ollama separately
ollama pull qwen3:4b
```

Verified on this machine: FFmpeg 9.0.2 at
`C:\tools\ffmpeg\ffmpeg-9.0.2-essentials_build\bin`, whisper.cpp b5454 at
`C:\tools\whisper\bin\Release\whisper-cli.exe`, model
`C:\tools\whisper\models\ggml-small.bin`.

## Environment variables

```
FFMPEG_PATH=C:\tools\ffmpeg\bin\ffmpeg.exe
FFPROBE_PATH=C:\tools\ffmpeg\bin\ffprobe.exe
WHISPER_PATH=C:\tools\whisper\whisper-cli.exe
WHISPER_MODEL_PATH=C:\tools\whisper\ggml-large-v3-turbo.bin
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_CHAT_MODEL=qwen3:4b
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
LECTURE_STORAGE_ROOT=C:\lecture-processor\data
```

> Disk note: Ollama + `qwen3:4b` need ≈4 GB free. Check with
> `[math]::Round((Get-PSDrive C).Free/1GB,2)` before installing.

## Run

```powershell
./deployment/windows/run-backend.ps1
```

Then open http://localhost:8080.

## GPU/CPU notes (RTX 3050 Mobile, 4 GB VRAM)

- Avoid running Whisper and Ollama GPU workloads simultaneously on 4 GB VRAM.
- Prefer whisper.cpp with a quantized large-v3-turbo model, or medium
  multilingual, and run it before/after Ollama rather than concurrently.
- CPU fallback always works; pass `--no-gpu`/equivalent whisper.cpp flags via
  `WHISPER_PATH` if needed.
