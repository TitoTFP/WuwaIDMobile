# Changelog

## 0.2.0 - 2026-07-25

- Memulihkan koneksi UserService setelah timeout, binder mati, permission berubah, dan Shizuku dimulai ulang.
- Menambahkan retry dengan backoff serta perlindungan terhadap callback bind yang sudah kedaluwarsa.
- Membuat instalasi patch transaksional dengan verifikasi, backup, rollback, dan cleanup.
- Menambah regression test untuk lifecycle Shizuku, instalasi patch, parser, cache rilis, dan unduhan.
- Menambah laporan test, coverage, lint, serta APK debug sebagai artifact CI.
