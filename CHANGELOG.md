# Changelog

## 0.3.0 - 2026-07-29

- Menambahkan backend root langsung untuk perangkat `arm64-v8a`, dengan fallback ke Shizuku saat root tidak tersedia.
- Menggunakan native helper descriptor-relative yang membatasi akses ke folder aplikasi/game dan menolak traversal serta symlink.
- Menambahkan resolver `openat2` dengan fallback aman untuk kernel Android lama.
- Memperkuat protocol helper, timeout proses, atomic write/replace, metadata, dan sanitasi diagnostic.
- Menambahkan test native/JVM untuk protocol, deadlock, timeout, symlink, special file, dan fallback resolver.
- Memperbaiki inset layout agar judul tidak berhimpitan dengan status bar.

## 0.2.0 - 2026-07-25

- Memulihkan koneksi UserService setelah timeout, binder mati, permission berubah, dan Shizuku dimulai ulang.
- Menambahkan retry dengan backoff serta perlindungan terhadap callback bind yang sudah kedaluwarsa.
- Membuat instalasi patch transaksional dengan verifikasi, backup, rollback, dan cleanup.
- Menambah regression test untuk lifecycle Shizuku, instalasi patch, parser, cache rilis, dan unduhan.
- Menambah laporan test, coverage, lint, serta APK debug sebagai artifact CI.
