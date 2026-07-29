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

- Wuthering Waves Global (`com.kurogame.wutheringwaves.global`).
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

## Build dari source

Butuh JDK 17, Android SDK 35, NDK `27.0.12077973`, dan CMake 3.22.1.

```bash
git clone https://github.com/TitoTFP/WuwaIDMobile.git
cd WuwaIDMobile
./gradlew test lint assembleDebug
```

APK debug tersedia di:

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

```bash
./gradlew clean test lint assembleRelease
```

Setiap rilis wajib menaikkan `versionCode`. `versionName` harus sama dengan tag tanpa awalan `v`.

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
