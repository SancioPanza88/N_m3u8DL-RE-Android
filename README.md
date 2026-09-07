# N_m3u8DL-RE Android — Chromebook Plus

Port Android (Kotlin) del downloader **N_m3u8DL-RE** di [nilaoda](https://github.com/nilaoda/N_m3u8DL-RE) (licenza MIT),
ottimizzato per **Chromebook Plus** e con sfruttamento dell'**accelerazione hardware**.

> Originale: `nilaoda/N_m3u8DL-RE` — tool cross-platform DASH/HLS/MSS (VOD + live).
> Questo repo è un port indipendente per Android/ChromeOS, non affiliato all'autore originale.

## Perché Chromebook Plus

I Chromebook Plus (Intel Core i3 12ª gen+, AMD Ryzen 3 7000+, MediaTek Kompanio 520+, 8GB RAM)
espongono via ARCVM:

- **Decoder HW** H.264 / HEVC / VP9 / AV1 (`MediaCodec.isHardwareAccelerated`, API 29+ con fallback <29)
- **Multi-core aggressivo**: thread di download di default = `max(nCPU, 8)` (cfr. `--thread-count` originale)
- **Schermi grandi + tastiera/mouse**: activity `resizeable`, `configChanges` completo, IME action, deep-link http(s)
- **Zero ricodifica**: merge TS binario + `MediaMuxer` (nessun carico GPU), preview via **Media3 ExoPlayer** con decoder HW prioritario
- **Background affidabile**: `WorkManager` (`DownloadWorker`) che sopravvive al resize della finestra

## Funzioni (parità con l'originale, best-effort)

- HLS master/media (`#EXT-X-STREAM-INF`, `#EXTINF`, `#EXT-X-KEY AES-128`, `#EXT-X-MAP`, live/VOD)
- `--auto-select` best-bandwidth, `--thread-count`, `--download-retry-count`, `-H` headers
- `--custom-hls-key/iv` (AES-128 CBC), IV di default = sequence number (RFC HLS)
- `--binary-merge`, `--skip-merge`, DASH MPD base (`SegmentTemplate`/`SegmentList`)
- `HwCapDetector.probe()` → report nel pannello "Verifica HW Chromebook"

## Mux automatico (video + audio + sottotitoli)

I flussi tipo vixcloud arrivano con **video, audio e sottotitoli separati**: l'app li
scarica tutti e li unisce da sola, senza farlo a mano:

- spunta **"Unisci automaticamente video + audio + sottotitoli in un unico file"**
  (attiva di default) → ottieni `<nome>.mp4` + `<nome>.it.srt` fianco a fianco
  (tutti i player li caricano in automatico)
- audio: traccia del gruppo del video, sottotitoli: preferenza **italiano**
- mux via `MediaMuxer` **senza ricodifica** (veloce, non scalda il Chromebook);
  se un codec non è supportato, tiene comunque i file separati (`*_video.ts`,
  `*_audio.ts`) invece di fallire
- se spegni la spunta → modalità "file separati" come il programma per PC

## Tabella parità opzioni (stesse flag dell'originale)

Ogni campo della UI riporta la flag CLI; il pulsante **"Comando CLI"** genera il
comando `N_m3u8DL-RE` equivalente. Mappatura completa:

| Originale | Android | Note |
|---|---|---|
| `<input>`, `--base-url`, `-H`, `--http-request-timeout`, `--task-start-at`, `--append-url-params` | uguali | `--custom-proxy` supportato (HTTP) |
| `--save-name`, `--save-pattern`, `--sub-format`, `--write-meta-json`, `--no-log`, `--log-level` | uguali | pattern con `<SaveName> <Resolution> <Bandwidth> <Codecs> <Language> <MediaType> <GroupId> <Ext>` |
| `--thread-count`, `--download-retry-count`, `-mt`, `-R`, `--skip-download`, `--skip-merge`, `--check-segments-count`, `--binary-merge`, `--del-after-done`, `--custom-range`, `--ad-keyword`, `--allow-hls-multi-ext-map` | uguali | range `0-10`/`10-`/`-99` e `MM:SS-MM:SS` |
| `--auto-select`, `-sv/-sa/-ss`, `-dv/-da/-ds`, `--sub-only` | uguali | select/drop = regex su banda/ris/codec/lingua/nome/url |
| `--auto-subtitle-fix`, `--live-fix-vtt-by-audio` | uguali | fix VTT normalizza timestamp (best effort) |
| `--key`, `--key-text-file`, `--custom-hls-method`, `--custom-hls-key`, `--custom-hls-iv` | uguali | DASH CENC Widevine/PlayReady non supportati su Android senza licenza (limite noto) |
| `--live-perform-as-vod`, `--live-real-time-merge`, `--live-keep-segments`, `--live-record-limit`, `--live-wait-time`, `--live-take-count` | uguali | pipe-mux ffmpeg non disponibile su Android (merge TS diretto) |
| `-M format=mp4`, `keep`, `skip_sub`, `--mux-import` | uguali | `format=mkv`/ffmpeg/mkvmerge → fallback mp4 HW; subs come sidecar `.srt/.vtt` |
| `--force-ansi-console`, `--no-ansi-color`, `--ffmpeg-binary-path`, `--decryption-engine`, `--use-ffmpeg-concat-demuxer`, `--live-pipe-mux`, `--ui-language`, `--urlprocessor-args`, `--morehelp`, `--disable-update-check` | — | non applicabili su Android (no terminale ANSI/ffmpeg esterno/update check: già disabilitato) |

## Build

[![Android CI (Chromebook Plus)](https://github.com/SancioPanza88/N_m3u8DL-RE-Android/actions/workflows/build.yml/badge.svg)](https://github.com/SancioPanza88/N_m3u8DL-RE-Android/actions/workflows/build.yml)

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

APK debug: `app/build/outputs/apk/debug/*.apk` (anche come artifact CI).
Requisiti: JDK 17, Android SDK 34, Gradle 8.7 (wrapper incluso).

## Licenza

MIT — vedi [LICENSE](LICENSE). Crediti all'autore originale `nilaoda/N_m3u8DL-RE`.
