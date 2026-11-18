<div align="center">
  <img src="src-tauri/icons/icon.png" width="96" height="96" alt="Ikon WuwaID Mobile">
  <h1>WuwaID Mobile</h1>
  <p><strong>Jelajahi Solaris-3 dalam Bahasa Indonesia.</strong></p>
  <p>Pasang terjemahan dan atur tampilan UID Wuthering Waves langsung dari Android.</p>
  <p>
    <a href="https://github.com/TitoTFP/WuwaIDMobile/releases">Unduh APK</a>
    · <a href="#mulai-bermain">Mulai bermain</a>
    · <a href="https://github.com/TitoTFP/WuwaIDMobile/issues">Laporkan masalah</a>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 7.0 atau lebih baru">
    <img src="https://img.shields.io/badge/Tauri-2-24C8D8?logo=tauri&amp;logoColor=white" alt="Dibangun dengan Tauri 2">
  </p>
</div>

> [!NOTE]
> Dokumentasi ini untuk **1.0.0**. Lihat versi dan APK yang sudah dipublikasikan di **Releases**.

## Satu aplikasi, untuk perjalananmu

- **Terjemahan Indonesia** — pasang dan perbarui terjemahan dari rilis WuwaID.
- **UID kustom** — ubah teks tampilan UID, bukan UID akun.
- **Perawatan patch** — perbaiki, pulihkan, atau hapus terjemahan dari aplikasi.
- **Tema pilihanmu** — terang, gelap, atau mengikuti sistem.

## Mulai bermain

Siapkan **Android ARM64** dan **Wuthering Waves Global atau Galaxy Store**. **AxManager direkomendasikan** dan menjadi default instalasi baru pada Android 8+ di user utama. Pilihan pengguna lama tetap dipertahankan.

| Metode | Yang perlu disiapkan |
|---|---|
| [AxManager](https://github.com/fahrez182/AxManager/releases) | Versi aplikasi dan server 3.0.0+, Android 8+ pada user utama. Aktifkan server, pilih Hubungkan, lalu Allow all the time. Tidak memerlukan layanan Shizuku. |
| [Shizuku](https://shizuku.rikka.app/) | Alternatif; default instalasi baru pada Android 7 atau profil sekunder. Jalankan layanan dan berikan izin kepada WuwaID Mobile. |
| Root | Alternatif untuk perangkat rooted; izin diminta secara eksplisit dari pengelola root. |

1. Buka game dan selesaikan unduhan resource, termasuk paket bahasa Inggris.
2. Buka kartu **Akses game** di Beranda. Pilih metode, unduh/buka manager bila diperlukan, lalu hubungkan atau berikan izin langsung di kartu tersebut. Lihat [pengaturan AxManager](docs/android.md#akses-axmanager).
3. Pilih **Periksa resource** bila status belum diperiksa, lalu **Pasang terjemahan**.

Metode akses tidak berpindah otomatis saat izin ditolak atau koneksi putus. AxManager memerlukan **Hubungkan** ulang setiap aplikasi dibuka atau server dimulai ulang. Pengguna lama tanpa pilihan tersimpan tetap memakai Shizuku saat upgrade.
Setelah membuka manager dari aplikasi, status diperiksa otomatis saat kembali, setelah operasi aktif selesai. Pemeriksaan tidak meminta izin, menghubungkan ulang, atau mengganti metode. Root hanya untuk perangkat yang sudah di-root; WuwaIDMobile tidak melakukan root.
Beranda langsung tampil; pemeriksaan akses dan rilis berlangsung terpisah. Resource dan patch baru diperiksa saat diminta atau membuka Kustomisasi/Perawatan. Status belum diperiksa bukan berarti belum terpasang. [Alur pemuatan](docs/android.md#pemuatan-bertahap).

**Sudah memakai versi lama?** Jangan uninstall atau hapus data untuk upgrade. Ikuti [panduan upgrade](docs/android.md#release-dan-upgrade); APK debug bukan pengganti APK rilis.

## Butuh panduan?

- [Instalasi, UID, dan pemulihan](docs/android.md#penggunaan-dan-kontrak-instalasi)
- [Pengaturan AxManager](docs/android.md#akses-axmanager)
- [Konflik PAK manual dan UID kustom](docs/android.md#konflik-pak-manual-dengan-uid-kustom)
- [Catatan perubahan](CHANGELOG.md)

## Ikut membangun

Preview antarmuka dengan **Node.js 24 dan npm**:

```sh
npm ci
npm run dev
```

Browser memakai simulasi; desktop tidak memasang patch Android. Untuk pengembangan native, lihat [panduan Android](docs/android.md) dan [panduan kontribusi](CONTRIBUTING.md).

---

Proyek komunitas untuk pemain Wuthering Waves. Kode sumber tersedia dengan lisensi [GPL-3.0](LICENSE).
