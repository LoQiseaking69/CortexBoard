# CORTEX//BOARD

A slim, cyber-themed, local-first notes + lists + kanban app for Android with a **baked-in Qwen brain** and an **MCP tool bus**. Think Obsidian/Notion, minus the bloat, plus an on-device agent that actually organizes your vault.

- **Brain**: Qwen3 (GGUF) running on-device via llama.cpp — no cloud, no API key, works offline.
- **Nervous system**: everything the brain can do is an MCP tool. The same tools are exposed to external agents over a local MCP bridge, and the brain can call tools on any remote MCP server you uplink.
- **Automation**: drop raw thoughts into ⚡ capture (or share text from any app) — the auto-organizer files them into folders, tags them, writes a one-line summary, links related notes and extracts tasks.

## Features

| Area | What you get |
|---|---|
| **VAULT** | Markdown notes, `[[wikilinks]]` with backlinks + outbound links, inline `#tags`, folders, pins, FTS4 full-text search, quick capture, share-to-capture |
| **TASKS** | Lists, priorities (`!0-3`), `@list` inline syntax, due dates, task ↔ note links, AI-extracted tasks |
| **BOARD** | Multiple kanban boards (TODO / DOING / DONE) |
| **BRAIN** | Agent chat grounded in your vault (auto-retrieval of relevant notes + open tasks), live MCP tool trace, quick actions (brief, plan today, board it, link notes, weekly), persistent history, tok/s stats |
| **GRAPH** | Obsidian-style wikilink graph — force layout, pinch-zoom, tap to open, coloured by folder |
| **NEURAL OPS** | Per-note: organize (folder, tags, summary, links, tasks, auto-title for captures), summarize, clean, expand, next steps, continue writing, questions |
| **SYS** | Model library (Qwen3 0.6B / 1.7B / 4B — download, switch, delete), warm-up at launch, retrieval toggle, import any `.gguf`, ctx/threads/temp, Qwen3 reasoning toggle, auto-organizer schedule, MCP bridge, remote MCP uplinks, vault export (Obsidian-compatible markdown zip) |

## Architecture

```
Compose UI ──▶ Store (SQLite + FTS4, zero codegen)
     │
     ├──▶ Agent (Qwen ChatML + Hermes <tool_call>) ──▶ McpHub ──┬─▶ VaultMcpServer (in-process, 15 tools)
     │                                                           └─▶ RemoteMcpClient (Streamable HTTP, JSON/SSE)
     ├──▶ Organizer (+ WorkManager periodic job)
     └──▶ McpBridgeServer (127.0.0.1, bearer token) ◀── external MCP clients

LlamaEngine ──JNI──▶ cortex_jni.cpp ──▶ llama.cpp (pinned b11549, CPU backends dlopen'd per-device)
```

Design choices for "slim + fast":
- No Room/KSP/Hilt/OkHttp — plain SQLite, `HttpURLConnection`, manual DI.
- KV-cache prefix reuse in native code: multi-step agent turns only re-decode the new tokens.
- `GGML_CPU_ALL_VARIANTS` + backend dlopen: one APK picks the best ARM kernels (dotprod/i8mm/SVE) at runtime.
- GGUF is stored uncompressed in the APK and extracted once to internal storage, then mmap'd.

## MCP

**Vault tools** (used by the brain, and served by the bridge): `search_notes`, `read_note`, `create_note`, `append_to_note`, `update_note`, `list_notes`, `vault_overview`, `get_backlinks`, `create_task`, `list_tasks`, `complete_task`, `create_card`, `move_card`, `list_board`, `daily_log`.

**Bridge** (SYS → MCP BRIDGE): serves the vault at `http://127.0.0.1:8765/mcp` (Streamable HTTP, JSON responses). Loopback only, bearer token required (auto-generated, rotatable). From a PC:

```bash
adb forward tcp:8765 tcp:8765
curl -s http://127.0.0.1:8765/mcp -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

**Uplinks** (SYS → MCP UPLINKS): add any Streamable-HTTP MCP server (HTTPS only). Its tools appear to the brain as `<server>__<tool>`.

## Build

CI does everything: `.github/workflows/android.yml` installs NDK 29 + CMake, clones llama.cpp at the pinned tag, downloads the GGUF, bakes it into `assets/models/model.gguf`, builds `assembleRelease` and uploads the APK as an artifact.

**Releasing:** bump `VERSION` on `main` (e.g. `0.1.0` → `0.2.0`). CI tags `v<version>` and publishes a GitHub Release with the APK attached. Pushing a `v*` tag manually works too.

- **Default model**: `Qwen/Qwen3-0.6B-GGUF` → `Qwen3-0.6B-Q8_0.gguf` (~640 MB APK). 
- **Other models**: Actions → *Build APK* → *Run workflow* → set `model_url` / `model_name` (e.g. a Qwen3-1.7B or 4B GGUF for stronger phones).
- **Lite APK**: run the workflow with `bundle_model: false`, then IMPORT GGUF in SYS.

### Release signing (recommended)

Without secrets the APK is signed with an ephemeral debug key, so each build needs a reinstall. To get upgradeable builds, create a key once and add four repo secrets:

```bash
keytool -genkeypair -v -keystore cortex.jks -alias cortex -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 cortex.jks   # → KEYSTORE_BASE64
```

Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`cortex`), `KEY_PASSWORD`.

### Local

```bash
git clone --depth 1 --branch b11549 https://github.com/ggml-org/llama.cpp third_party/llama.cpp
curl -L -o app/src/main/assets/models/model.gguf \
  https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf
./gradlew assembleRelease
```

## Requirements

Android 9+ (API 28), arm64-v8a, ~1.5 GB free storage for the default model. 6 GB+ RAM recommended for 1.7B+ models.
