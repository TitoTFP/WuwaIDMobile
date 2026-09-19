<div align="center">
  <img src="app/src/main/res/drawable-nodpi/app_icon.png" width="112" alt="WuwaID Mobile">

# WuwaID Mobile

  **Pasang patch Bahasa Indonesia Wuthering Waves langsung dari Android.**

  Root • Shizuku • Verifikasi checksum • Instalasi transaksional

  [Unduh APK](https://github.com/TitoTFP/WuwaIDMobile/releases/latest) · [Patch WuwaID](https://github.com/TitoTFP/WuwaID)
</div>

## Tentang

WuwaID Mobile adalah launcher Android komunitas untuk memasang, memperbarui, memeriksa, dan menghapus patch Bahasa Indonesia pada Wuthering Waves Global. Aplikasi mengambil patch resmi WuwaID dari GitHub Releases, memverifikasinya, lalu menulis file melalui root atau Shizuku.

## Fitur utama

- **Root atau Shizuku** — root dipakai langsung bila tersedia, dengan fallback ke Shizuku.
- **Instalasi aman** — ukuran dan SHA-256 diverifikasi sebelum file dipasang.
- **Transaksional** — PAK, SIG, dan mount dipasang dengan backup serta rollback jika proses gagal.
- **Status jelas** — menampilkan backend aktif, versi resource, hash patch, konflik, dan rilis terbaru.
- **Pemulihan otomatis** — koneksi Shizuku pulih setelah timeout, binder mati, atau service dimulai ulang.
- **Update aplikasi** — APK pembaruan diverifikasi sebelum installer Android dibuka.
- **Root helper terbatas** — akses dibatasi ke direktori aplikasi/game dan tidak mengikuti symlink.

## Persyaratan

- Wuthering Waves Global (`com.kurogame.wutheringwaves.global`) atau Galaxy Store (`com.kurogame.wutheringwaves.samsung`).
- Android dengan ABI `arm64-v8a`.
- Salah satu akses berikut:
  - root dan izin root untuk WuwaID Mobile; atau
  - Shizuku aktif dengan izin untuk WuwaID Mobile.
- Data game sudah selesai diunduh.

## Instalasi

1. Unduh APK terbaru dari [GitHub Releases](https://github.com/TitoTFP/WuwaIDMobile/releases/latest).
2. Pasang dan buka WuwaID Mobile.
3. Berikan izin root atau Shizuku.
4. Tutup Wuthering Waves.
5. Tekan tombol instal/perbarui patch.
6. Jalankan game setelah status menunjukkan patch siap.

> Selalu tutup game saat memasang, memperbarui, atau menghapus patch.

## Panduan & Troubleshooting Shizuku

### Android 15 & Android 16 (API 35 & 36)

- **Gejala:** Shizuku aktif (Running) namun service tidak merespons.
- **Solusi:**
  1. Buka aplikasi **Shizuku**, tekan tombol **Stop**.
  2. Jalankan ulang Shizuku melalui **Wireless Debugging** (lakukan pairing ulang jika koneksi terputus).
  3. Kembali ke WuwaID Mobile dan tekan tombol **Hubungkan ulang**.

## Build dari source

Butuh JDK 17, Android SDK 35, dan CMake 3.22.1. Build Tauri Android memakai NDK `27.3.13750724` (r27d), dipin oleh `src-tauri/gen/android/app/build.gradle.kts`. Legacy Gradle dan workflow CI tetap memakai NDK `27.0.12077973`.

### Tauri 2 Android

```bash
npm ci
npm run tauri:android:build -- --debug
```

APK debug tersedia di:

```text
src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
```

### Legacy Android Gradle

```bash
./gradlew test lint assembleDebug
```

APK legacy tersedia di:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release build

Release signing memakai environment variable berikut:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Tauri Android:

```bash
npm run tauri:android:build
```

Legacy Android Gradle:

```bash
./gradlew clean test lint assembleRelease
```

Tanpa environment signing, build Tauri release menghasilkan APK unsigned. Setiap rilis wajib menaikkan `versionCode`. `versionName` harus sama dengan tag tanpa awalan `v`.


## Pengujian

```bash
./gradlew test lint
python3 app/src/test/native/root_helper_security_test.py
```

CI juga membangun APK debug, memeriksa packaged root helper, dan menerbitkan laporan test, coverage, serta lint.

## Kredit

- Patch Bahasa Indonesia: [TitoTFP/WuwaID](https://github.com/TitoTFP/WuwaID)
- Shizuku: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- Referensi metode Android: CallMeDangDev/WuwaVHLauncher dan APK DangDevVH

## Lisensi

Dirilis di bawah [GNU General Public License v3.0](LICENSE).

WuwaID adalah proyek komunitas tidak resmi dan tidak berafiliasi dengan Kuro Games.
