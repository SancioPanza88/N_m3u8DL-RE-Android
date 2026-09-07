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
