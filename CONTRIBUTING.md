# Kontribusi

## Persiapan

Gunakan Node.js 24 dan npm; jalankan `npm ci` untuk memasang dependensi yang terkunci. Native memerlukan Rust stable melalui rustup dan dependensi sistem [Tauri](https://v2.tauri.app/start/prerequisites/). Setup Android ada di [panduan Android](docs/android.md).

## Sebelum pull request

Jalankan pemeriksaan yang relevan:

```sh
npm run check
npm test
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path src-tauri/Cargo.toml --all-targets
```

Untuk perubahan Android, uji di emulator/perangkat atau buat APK debug sesuai [panduan Android](docs/android.md); jalankan tes Gradle pada [bagian Tes host dan instrumentation Android](docs/android.md#tes-host-dan-instrumentation-android). Sertakan langkah reproduksi dan pemeriksaan yang dijalankan dalam PR. Perbarui dokumentasi bila setup atau perilaku berubah.

## Jangan commit

Jangan commit kredensial signing, `local.properties`, keluaran build, atau `node_modules`. Tetap lacak `src-tauri/gen/android/`: direktori itu berisi proyek native yang dikonfigurasi untuk aplikasi ini.

## Arsitektur dan UI

- `src/model.ts` mendefinisikan state; UI di `src/main.ts` memanggil wrapper `src/ipc.ts`. Orkestrasi native ada di `src-tauri/src/native.rs`, installer/downloader di `src-tauri/src/pak/`, dan bridge Android di `src-tauri/src/android.rs`.
- Navigasi di rel kiri pada desktop dan di dock bawah pada ponsel. Tema Terang/Gelap/Sistem disimpan lokal; perubahan tema tidak menghapus draf atau menghentikan operasi. Progres tidak membangun ulang navigasi. Lihat [sistem desain](DESIGN.md) untuk token dan pola UI.
- Browser adalah simulasi, bukan bukti akses privileged atau hasil game. Ubah salah satu dari 16 skenario lewat **Perawatan → Skenario simulasi**.
- State browser memakai `wuwaidmobile.mock-state.v6`; memilih skenario mereset data contoh dan UID. UID `900123456`, versi `3.7.0`, dan konten `3.7.0-R2` hanyalah contoh. Desktop native menyediakan metadata rilis tetapi tidak menulis game; status game ditandai `not_checked`.

### Skill frontend (opsional)

Skill proyek tersedia di `.agents/skills/`. Di OMP, jalankan `/reload-plugins` (atau buka sesi baru), lalu cek tab Skills di `/extensions`.

- `/skill:impeccable clarify pesan akses file` — poles UI yang ada, terutama hierarki, kepadatan, dan teks bantuan. [Upstream `b49177d`](https://github.com/pbakaus/impeccable/tree/b49177d32788f2dc585c42831d845dacf1ebed2c/.agents/skills/impeccable).
- `/skill:frontend-design rancang ulang onboarding` — tentukan arah visual untuk layar baru atau perombakan besar. [Upstream `e4bb5d9`](https://github.com/anthropics/skills/tree/e4bb5d960d87126ff3b3a8af1a104b7db67560ba/skills/frontend-design).

Skill tidak mengubah build aplikasi. Hook dan live mode tidak dikonfigurasi. Launcher Impeccable dapat mengunduh engine terpin ke cache pengguna saat pertama dipakai dan memeriksa SHA-256 unduhan.

## Kontrak IPC

`src/ipc.ts` membuat correlation ID; caller tidak perlu membuatnya sendiri. Jangan memanggil command native ad hoc dari UI.

| Perintah | Argumen | Hasil |
|---|---|---|
| `wuwa_load` | `requestId` | `Dashboard` |
| `wuwa_set_scenario` | `scenario` | `Dashboard` (browser saja) |
| `wuwa_run` | `requestId`, `operation`, `uidText?` (`apply_uid`) | `Dashboard` |
| `wuwa_open_setup` | `target`: `game` / `shizuku` / `axmanager` | — (native) |
| `wuwa_diagnostics` | `requestId` | `DiagnosticReport` |
| `wuwa_export_diagnostics` | `action`: `save` / `share`; `content`: JSON teredaksi | `saved` / `cancelled` / `failed` / `share_opened` (Android) |
| `wuwa_trace_enabled` | — | Status recording debug native (`boolean`) |
| `wuwa_ui_trace` | `requestId`, `event`, `frontendUs` | Event tetap; recorder dinonaktifkan di release native |

Operasi native: `install`, `repair`, `uninstall`, `request_permission`, `request_root`, `request_axmanager`, `use_root`, `use_shizuku`, `use_axmanager`, `launch_game`, `apply_uid`, `restore_uid`. Mutasi bersamaan ditolak.

Status `shizuku` dan `axmanager` (`installed`, `supported`, `running`, `version`) terpisah dari pilihan `access.method` dan `access.granted`; kesiapan memakai grant metode terpilih. Server AxManager diperiksa saat metodenya dipilih, tanpa meminta izin. `game.packageName` adalah paket aktif; `patch.legacyPending` menandai instalasi sebelum Tauri. Operasi `repair` memilih migrasi offline atau pemulihan jurnal sesuai status.

Lihat [format dan privasi diagnostik](docs/android.md#diagnostik-dan-ekspor) serta [marker debug dan analisis trace](docs/android-traces.md#marker-debug) untuk kontrak laporan dan recording.
