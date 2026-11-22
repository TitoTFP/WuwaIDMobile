# WuwaID Mobile

<!-- impeccable:product-schema 1 -->

## Platform

web

Antarmuka TypeScript/CSS di WebView Tauri. Android adalah platform utama. Browser memakai simulasi; desktop native tidak menulis file game.

## Pengguna dan tugas

Pemain Wuthering Waves Global atau Galaxy Store di Android ingin bermain dengan terjemahan Indonesia, menyesuaikan teks tampilan UID, dan memulihkan pemasangan bila bermasalah.

## Cara kerja dan batas

- WuwaID Mobile memeriksa game, izin metode akses yang dipilih, dan file game secara terpisah. Beranda tampil sebelum pemeriksaan selesai. Rilis dapat gagal dimuat tanpa menghalangi pemulihan atau penghapusan offline yang memenuhi syarat.
- AxManager, Shizuku, dan Root adalah pilihan eksplisit. UI tidak meminta izin atau mengganti metode otomatis. Status terhubung bukan bukti file game sudah diperiksa.
- UID kustom mengubah teks tampilan, bukan UID akun. Pemasangan, pemulihan, dan penghapusan tetap mengikuti batas kepemilikan file dan transaksi native. Ekspor diagnostik menyamarkan UID.
- Pertahankan identitas WuwaID Mobile, bahasa Indonesia yang ringkas, tema terang/gelap/sistem, dan keterangan bahwa proyek tidak berafiliasi dengan Kuro Games.

Rujukan: [panduan penggunaan](README.md#mulai-bermain) dan [kontrak Android](docs/android.md#penggunaan-dan-kontrak-instalasi).
