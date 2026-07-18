# WuwaID Mobile

Launcher Android untuk memasang patch Bahasa Indonesia Wuthering Waves Global melalui Shizuku.

## Fitur v0.1.0

- Membaca patch terbaru dari GitHub Releases [`TitoTFP/WuwaID`](https://github.com/TitoTFP/WuwaID).
- Memverifikasi ukuran dan SHA-256 sebelum menulis file ke folder game.
- Memasang, memperbarui, memeriksa, dan menghapus hanya artefak milik WuwaID Mobile.
- Mengkloning `.sig` resmi game dan membuat mount manifest dengan hash SHA-1.
- Memblokir patch Vietnam atau mount custom berprioritas tinggi tanpa menghapusnya.
- Menampilkan status Shizuku, resource game, hash patch, dan catatan rilis.

Tidak ada NTE, custom font, media bergerak, analytics, log server, atau instalasi otomatis.

## Persyaratan pengguna

1. Wuthering Waves Global (`com.kurogame.wutheringwaves.global`).
2. Shizuku terpasang, aktif, dan memberi izin ke WuwaID Mobile.
3. Data game sudah selesai diunduh.
4. Game ditutup saat patch dipasang atau diperbarui.

## Build

Butuh JDK 17 dan Android SDK 35.

```bash
./gradlew test lint assembleDebug
```

Release signing membaca empat environment variable:

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

```bash
./gradlew clean test lint assembleRelease
```

Jangan commit keystore atau password. Alias resmi aplikasi: `wuwaid-mobile`.

## Uji perangkat sebelum prerelease

- Shizuku mati, izin ditolak, izin diterima, dan UserService tersambung.
- Game belum terpasang dan resource belum selesai.
- Instal latest patch, cocokkan PAK/SIG/mount dan pastikan game tampil Bahasa Indonesia.
- Restart aplikasi; status harus `Siap dimainkan`.
- Simulasikan unduhan terputus; patch lama harus tetap utuh.
- Pasang artefak Vietnam; instalasi WuwaID harus diblokir tanpa menghapusnya.
- Uninstall; hanya `wuwaindonesia/WuWaID_99_P.*` dan `Mount/wuwaindonesia.txt` yang hilang.

Rilis `v0.1.0` tetap prerelease sampai skenario di atas lulus pada perangkat nyata.

## Kredit dan lisensi

- Patch: [TitoTFP/WuwaID](https://github.com/TitoTFP/WuwaID)
- Referensi metode Android: CallMeDangDev/WuwaVHLauncher dan APK DangDevVH
- Shizuku: [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
- Branding diambil dari WuwaIDLauncher milik proyek yang sama.

Kode dirilis dengan GNU GPL v3. WuwaID merupakan mod komunitas tidak resmi dan tidak berafiliasi dengan Kuro Games.
