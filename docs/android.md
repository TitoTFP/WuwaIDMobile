# Pengembangan Android

Proyek Android Tauri tersimpan di `src-tauri/gen/android/` dan dikomit. Versi build lokal/CI: Android API 36, Build Tools 36.0.0, NDK 27.3.13750724, JDK 17.

## Persiapan dan build debug

Pasang melalui Android Studio SDK Manager:

- `platforms;android-36`
- `build-tools;36.0.0`
- `platform-tools`
- `cmdline-tools;latest`
- `ndk;27.3.13750724`

CI/release memakai `android-actions/setup-android@v3` dengan `packages: platform-tools`; paket SDK `tools` sudah tidak tersedia. Platform, Build Tools, dan NDK dipasang terpisah sesuai versi di atas.

Pasang Rust stable dan target yang diperlukan. ARM64:

```sh
rustup target add aarch64-linux-android
```

Tambahkan target berikut bila membangun ABI tersebut atau memakai emulator x86_64:

```sh
rustup target add armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

Atur environment Android/JDK (sesuaikan lokasi mesin):

```sh
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
```

Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:NDK_HOME = "$env:ANDROID_HOME\ndk\27.3.13750724"
```

Dari root repo:

```sh
npm ci
adb devices
npm run tauri android dev
```

Checkout sudah memuat `src-tauri/gen/android/`; `npm run tauri android init` hanya untuk regenerasi native, misalnya setelah mengganti identifier di `src-tauri/tauri.conf.json`. Setelah regenerasi, pastikan `compileSdk` dan `targetSdk` di `src-tauri/gen/android/app/build.gradle.kts` cocok dengan SDK terpasang (saat ini 36).

Buat APK debug ARM64:

```sh
npm run tauri android build -- --debug --apk --target aarch64
```

Jangan berbagi `CARGO_TARGET_DIR` antarworktree: Tauri/Wry menghasilkan kode Kotlin pada path proyek. Cache worktree lain dapat melewati generasi dan menyebabkan `TauriActivity` tidak ditemukan pada checkout baru. Gunakan target default `src-tauri/target` atau override milik worktree tersebut; bukan `android init` ulang.

Output berada di `src-tauri/gen/android/app/build/outputs/apk/`. CI membangun ARM64 dengan perintah yang sama dan menyimpan artifact 14 hari. APK debug memakai package `com.titotfp.wuwaid.debug` dan akhiran versi `-debug`; bisa berdampingan dengan release, tetapi bukan upgrade release. Jangan distribusikan signing debug atau jalankan dua installer bersamaan pada resource game yang sama.

### Profile debug

`profile.dev.debug = 1` menyimpan informasi modul/baris dan backtrace, tetapi bukan detail penuh tipe/variabel lokal. Profile dev memakai `opt-level = 2`; debug assertions, overflow checks, dan trace tetap aktif. Atur `CARGO_PROFILE_DEV_DEBUG=2` untuk detail debugging Rust penuh. Hanya task `package*Debug` yang nonincremental; task build lain tetap incremental.

## Penggunaan dan kontrak instalasi

### Instalasi dan migrasi

1. Pasang Global (`com.kurogame.wutheringwaves.global`) atau Galaxy Store (`com.kurogame.wutheringwaves.samsung`); selesaikan resource game dan paket bahasa Inggris.
2. Buka kartu **Akses game** di Beranda. Shizuku menampilkan Unduh/Buka serta Izinkan langsung di kartu; permintaan izin hanya aktif setelah layanan terdeteksi. Server Shizuku tetap memerlukan API 12+. Root hanya untuk perangkat yang sudah di-root: aplikasi tidak melakukan root dan meminta `su` hanya melalui **Izinkan root**, bukan saat membuka aplikasi/refresh. Untuk AxManager, ikuti [setup berikut](#akses-axmanager).
3. Pilih **Periksa resource** bila belum diperiksa, lalu **Pasang terjemahan**. Game dihentikan sebelum perubahan berkas. Pilihan akses disimpan; Root/AxManager memerlukan koneksi ulang secara eksplisit setelah aplikasi/service dimulai ulang. Izin yang tersimpan bukan bukti sesi aktif. Tidak ada fallback otomatis atau pergantian metode selama operasi.

Setelah membuka pengaturan/manager dari WuwaIDMobile, kembali ke aplikasi memicu pemeriksaan status pasif sekali. Jika operasi atau updater sedang aktif, pemeriksaan ditunda sampai selesai; tidak ada permintaan izin, reconnect, atau pemilihan metode otomatis. **Periksa akses** tetap tersedia untuk pemeriksaan manual. Setup memakai pesan menunggu tanpa persentase; persentase hanya muncul ketika operasi berkas mengirim progres nyata.

Pemeriksaan otomatis memakai `wuwa_access_status`: hanya membaca paket, Binder, dan izin yang tersedia; tidak memulai service ataupun memindai berkas game. Jika izin Shizuku sudah ada tetapi service storage belum tersambung, kartu menawarkan **Hubungkan Shizuku**, bukan meminta izin lagi. **Periksa akses** manual memeriksa resource/patch penuh saat status akses sudah diketahui; bila status akses gagal, tombol mengulangi pemeriksaan pasif.

Jika kedua paket terpasang, pilih resource Global yang siap, lalu Galaxy Store, lalu paket terpasang pertama. Paket aktif dibekukan selama sesi.

Upgrade versi sebelum Tauri memakai APK release dengan package/key yang sama; jangan uninstall atau hapus data. APK debug bukan jalur upgrade. Setelah upgrade, pilih metode akses dan **Perawatan → Migrasikan**. Migrasi offline mensyaratkan SHA-256/ukuran PAK cocok katalog rilis historis, signature tidak kosong, dan descriptor lama cocok persis dengan PAK/signature. Sumber lama dipertahankan sampai set baru terverifikasi; berkas ambigu/tidak dikenal memblokir mutasi. Setelah migrasi, pasang PAK stabil yang sesuai lini game/resource dan terapkan UID setelah status sehat.

### Pemuatan bertahap

- Shell dan navigasi tampil sebelum IPC selesai. `wuwa_bootstrap` hanya membaca metadata paket game dan pilihan akses tersimpan; tanpa Binder manager, shell, atau berkas game.
- `wuwa_release` mengambil rilis PAK secara independen. Kegagalan jaringan tidak menahan status akses atau pemeriksaan storage; **Periksa rilis** mengulanginya.
- Setelah bootstrap, `wuwa_access_status` memeriksa akses secara pasif di latar belakang. Tidak meminta izin, mengikat service baru, atau mencari root otomatis.
- `wuwa_inspect` memindai resource, kepemilikan patch, dan UID hanya saat **Periksa resource/akses**, membuka Kustomisasi/Perawatan dengan akses tersedia, atau setelah koneksi/izin diminta secara eksplisit. Tidak mengambil rilis jaringan. Beranda tidak memulai pemindaian otomatis.
- Setiap tahap memiliki status `not_checked`, `loading`, `ready`, atau `error`. Belum diperiksa/gagal bukan bukti game, izin, resource, atau patch tidak ada. Mutasi terkunci sampai prasyaratnya terverifikasi.
- Pemilihan metode dan buka game hanya memperbarui status akses. Hasil rilis tidak menimpa hasil inspeksi, dan perubahan metode/kehilangan akses membatalkan status storage lama. Tidak ada retry penuh otomatis setelah kegagalan.
- Permintaan inspeksi dari perpindahan halaman digabungkan; hasil inspeksi yang sudah selesai tidak dipindai ulang oleh antrean navigasi lama. Status gagal tidak menampilkan centang siap atau UID aktif dari hasil lama. Waktu **Diperiksa** hanya berubah setelah respons inspeksi storage, bukan pemeriksaan akses pasif.
- Installer tetap memeriksa izin, storage, kepemilikan, dan transaksi kembali pada setiap operasi. Pemulihan/migrasi offline dan hapus patch terverifikasi tidak bergantung pada keberhasilan pemeriksaan rilis.

### Berkas dan batas keselamatan

Rilis PAK diambil dari [stable latest WuwaID](https://github.com/TitoTFP/WuwaID/releases/latest), bukan rilis aplikasi. Metadata tag, aset, ukuran, dan hash tidak dibundel. Unduhan HTTPS dan cache pakai ulang diverifikasi dengan ukuran/SHA-256. Lini game/resource harus cocok; pemasangan memakai pasangan PAK/signature resmi pada resource materialized, prefer `Lang_en`.

Layout Android di `Resources/<versi-resource>/`, berbeda dari `Patch/` launcher PC:

- `wuwaid/WuwaID_99_P.pak` dan `wuwaid/WuwaID_99_P.sig` (signature resmi).
- `wuwaid/.owner.json` untuk identitas/hash kepemilikan.
- `Mount/wuwaid.txt`, dengan nama dasar tanpa ekstensi dan prioritas 99:

```text
wuwaid/WuwaID_99_P,99,<PAK_SHA1_UPPER>,<SIG_SHA1_UPPER>,,
```

Berkas resmi game tidak ditimpa. Mutasi dibatasi pada path WuwaID di versi resource numerik. Traversal, symlink, kepemilikan tidak dikenal, serta mount lain berprioritas 99 atau lebih tinggi ditolak; berkas asing tidak dihapus. Verifikasi dilakukan sebelum commit. Jurnal/backup mendukung rollback dan recovery tertunda tanpa jaringan; residu `.new`/`.bak` tanpa jurnal valid tidak dihapus otomatis.

### UID kustom

Teks maksimal **32 unit UTF-16**; spasi tepi dipangkas. Input kosong, karakter kontrol, dan pemisah baris ditolak. Ini mengganti teks tampilan, bukan UID akun. Pemasangan biasa menyalin PAK utuh; UID kustom mengubah salinan privat lalu mengemas ulang PAK. Tombol **Pulihkan** mengembalikan PAK rilis asli dan format UID bawaan game. UID contoh di browser bukan data akun.

### Pembaruan aplikasi

Updater build Android release memeriksa stable terbaru saat startup dan melalui **Perawatan → Pembaruan aplikasi → Periksa**. Unduh APK, tunggu verifikasi, lalu konfirmasikan dialog Android. Update tidak dipasang diam-diam, tidak menghapus data/PAK, tidak memerlukan Root/Shizuku, dan tidak berjalan bersama mutasi PAK. Batal/gagal bukan sukses. Browser, desktop, dan debug tidak menyediakan updater release.

Sebelum membuka installer, periksa nama aset, ukuran/SHA-256, package `com.titotfp.wuwaid`, versi/tag, versionCode lebih tinggi, signer rilis, minSdk, dan ABI ARM64. Android memeriksa signature akhir. [Kontrak release dan upgrade](#release-dan-upgrade) memuat identitas, signing, dan checklist QA.

## Akses AxManager

**AxManager direkomendasikan** dan menjadi default instalasi baru pada Android API 26+ di user utama (user 0), termasuk ketika manager belum terpasang. Android API 24–25 dan profil sekunder memakai default Shizuku; minimum SDK aplikasi tidak berubah. Pilihan tersimpan selalu dipertahankan. Upgrade dari versi lama tanpa pilihan tersimpan tetap memakai Shizuku: aplikasi membedakan instalasi baru dari upgrade melalui `PackageInfo.firstInstallTime` dan `lastUpdateTime`, lalu menyimpan pilihan awal di `storage-access`. Tidak ada pemilihan berdasarkan manager/layanan yang sedang aktif ataupun fallback otomatis.

1. Buka **Akses game** di Beranda. Jika belum terpasang, pilih **Unduh AxManager** untuk membuka [rilis resmi](https://github.com/fahrez182/AxManager/releases) di browser. Jika sudah terpasang tetapi tidak terdeteksi, periksa pengaturan AX-Stealth di AxManager.
2. Gunakan aplikasi **serta server** AxManager 3.0.0/versionCode 30000 atau lebih baru. AxManager 1.x/2.x tidak didukung. Setelah update APK AxManager, mulai ulang servernya. Metode ini hanya mendukung Android 8+ dan user utama, bukan profil sekunder/work profile.
3. Aktifkan server AxManager, kembali ke WuwaID Mobile, lalu pilih **Hubungkan** dan izinkan **Allow all the time**. Refresh tidak meminta izin. Gunakan Hubungkan lagi setiap aplikasi dibuka atau server dimulai ulang.
4. Untuk memakai metode lain, pilih **Shizuku** atau **Root** di kartu **Akses game** dan berikan izinnya secara eksplisit. Penolakan izin atau koneksi putus tidak mengubah metode terpilih.

Backend memakai [Axeron-API `axsh`/`aidl` 3.0.0](https://github.com/fahrez182/Axeron-API/tree/3.0.0):

- Manager ditemukan melalui `AxBroker.findManager`/action `AxRuntime.REQUEST_ACCESS`, bukan nama package tetap. **Hubungkan** mengikat `AxShellService` melalui launcher resmi `libaxsh.so` milik manager (path di-resolve ulang setiap start). `argv` bootstrap SDK di-quote per argumen; tidak ada perintah shell dari frontend. Launcher menjadi klien terautentikasi sebelum meminta izin melalui dialog normal AxManager.
- AxManager 3 tidak membagikan Binder global kepada aplikasi: server mendorong capability khusus UID ke provider SDK setelah izin diberikan. Sebelum itu, server aktif tidak bisa dibedakan dari belum diizinkan melalui probe pasif. Tombol Hubungkan tetap tersedia tanpa capability; pemasangan/UID tetap diblokir.
- Jalur native dipakai konsisten, bukan fallback: pada AxManager 3.0.0.r509, dialog request-access activity memberikan grant tanpa client record sehingga server membuang identitas grant sebelum capability dikirim. Bootstrap `libaxsh.so` memiliki record tersebut. Deny/timeout membatalkan bind; keberhasilan tetap memerlukan capability SDK, versi server, UID 0/2000, dan grant aktual.
- Pilih **Allow all the time / Allowed**. Pada 3.0.0, Allow once hanya mengizinkan proses bootstrap sementara dan tidak memberikan capability persisten ke proses aplikasi; WuwaIDMobile tetap menolak operasi. Ubah menjadi Allowed di Privilege, lalu Hubungkan lagi. Broker in-app baru dimulai setelah bootstrap awal, agar native launcher tidak kembali ke broker yang belum diizinkan.
- Provider `AxshProvider` dari SDK memverifikasi UID root/shell. Attach layanan tetap membutuhkan token bind acak outstanding yang dikirim melalui stdin, bukan argv. `AxManagerStorageService` memakai `AxShellService` dan memverifikasi UID 0/2000 sebelum menyediakan `StorageUserService`.
- Metadata, streaming PFD, path/symlink guards, hashing, mutasi, dan installer transaksi tetap memakai implementasi yang sama. Tidak ada akses file Axeron alternatif atau perintah shell bebas dari frontend.
- `request_axmanager` saja yang meminta izin dan mengikat service. Refresh tidak memunculkan dialog. Sesi memeriksa izin server lagi setiap akses service; Binder server yang berubah, grant dicabut, atau storage service mati memblokir operasi, bukan beralih ke Shizuku/Root.
- SDK 3 tidak menyediakan probe grant/server publik tanpa dialog. Hanya `remote()` meng-opt-in `AxBroker.service()` internal dari versi SDK yang dipin untuk memeriksa grant, versi, dan identitas capability tanpa menjalankan shell atau meminta izin dari refresh. Perubahan SDK harus meninjau ulang kontrak ini; tidak ada refleksi atau salinan wire protocol.
- Pilihan metode disimpan di `storage-access`; sesi privileged non-daemon dilepas ketika berganti metode atau aplikasi ditutup. Setelah membuka ulang aplikasi/service, gunakan Hubungkan. Grant terikat sertifikat penandatangan aplikasi; debug dan release perlu izin masing-masing. **AX-Stealth** dapat menyembunyikan manager/request-access; nonaktifkan mode tersebut sebelum menghubungkan, atau gunakan metode lain.

Tidak diperlukan aplikasi/server Shizuku ataupun AX-Permission Intercept untuk metode AxManager. Dependensi API/provider Shizuku tetap ada untuk metode Shizuku. AIDL SDK Axeron memuat kontrak Shizuku 13 yang sama; dependensi `dev.rikka.shizuku:aidl` dikecualikan agar kelas Binder tidak dikemas dua kali.

Adaptasi mempertahankan kontribusi awal [@Ishcy melalui PR #9](https://github.com/TitoTFP/WuwaIDMobile/pull/9). Implementasi Tauri memakai jalur SDK resmi agar pemeriksaan storage yang lebih ketat tidak diganti oleh file client aplikasi lama.

### QA AxManager

Gunakan APK debug ARM64 agar package release/data pengguna tetap dipertahankan. Catat baseline patch/UID dan kembalikan setelah tes. Jangan memasang dua installer secara bersamaan pada resource yang sama.

Sebelum merilis perubahan default, uji instalasi baru (AxManager dipilih meski belum terpasang), upgrade dengan setiap pilihan tersimpan, serta upgrade versi lama yang belum pernah memilih metode (tetap Shizuku). Instalasi baru API 24–25/profil sekunder harus memilih Shizuku; pilihan tersimpan tidak boleh diubah karena lingkungan tidak mendukungnya. Uji tombol Unduh/Buka, deny/cancel, restart aplikasi/server, dan pastikan tidak ada permintaan root atau fallback otomatis. Tes host/browser bukan bukti QA perangkat asli.

Untuk QA setup pada perangkat asli: periksa Unduh/Buka Shizuku, izin Shizuku dari Beranda, tautan pengaturan akses dari status Perawatan, serta pemeriksaan pasif setelah kembali dari manager. Uji kembali saat operasi/updater aktif: tidak boleh ada operasi bertumpuk, dialog izin otomatis, atau perpindahan metode. Status AxManager/Root yang belum tersambung tidak boleh dianggap pencabutan izin. Indikator setup tidak menampilkan persentase pemasangan. Pengujian host/browser tidak menggantikan dialog izin dan lifecycle Android asli.

1. Jalankan server AxManager 3.0.0+; matikan layanan Shizuku lewat menu manager, tanpa mengubah konfigurasi wireless debugging. Pastikan proses `shizuku_server` tidak ada dan dashboard `shizuku.running=false`.
2. Pilih AxManager: sebelum grant, Hubungkan harus tetap aktif walau capability belum tersedia. Uji deny/cancel, lalu grant melalui dialog normal atau halaman Privilege. Refresh tidak boleh meminta izin sendiri. Cabut izin dan pastikan akses/mutasi diblokir; restart server harus memerlukan koneksi ulang.
3. Uji install, Apply/Reset UID, repair, uninstall, serta hasil terjemahan/UID di game. Bandingkan hash berkas resmi sebelum/sesudah. Jangan menulis langsung ke game melalui ADB.
4. Uji restart aplikasi, stop/restart server, koneksi ulang eksplisit, dan pergantian Root/Shizuku/AxManager. Metode lain yang aktif tidak boleh membuka grant metode terpilih.
5. Jalankan suite frontend/Rust/Android. Tes host Binder/provider mencakup API 24 dan 28; itu bukan bukti layanan AxManager dapat dipakai pada API 24–25. Root sukses memerlukan perangkat rooted; catat terpisah dari penolakan `su`.

### QA migrasi Axeron 3 — 2026-11-16

POCO X7/24095PCADG, Android 16/API 36, HyperOS OS3.0.303.0.WOOIDXM; AxManager 3.0.0.r509/server 30000, UID shell 2000; APK WuwaIDMobile debug terpisah dari release.

- Tanpa proses `shizuku_server`: Hubungkan tersedia sebelum grant; deny/timeout memblokir akses. Allow all the time mengirim capability dan menjalankan `:shell_shared` sebagai shell; inspeksi pemasangan Global 3.8.1/resource 3.8.0 berhasil.
- Allow once tidak memberikan capability aplikasi dan tidak membuka akses. Restart aplikasi maupun server memerlukan Hubungkan eksplisit; reconnect berhasil. Mencabut grant melalui Privilege menghentikan aplikasi; percobaan koneksi saat Denied ditolak tanpa dialog dan kontrol mutasi tetap terkunci. Allowed memulihkan koneksi.
- Build APK ARM64 debug, 33 tes state frontend, dan 14 tes batas Android (provider pada API 24/28 serta batas versi/user) lulus. Shizuku/Root tidak diganti diam-diam.
- SHA-256 empat berkas pemasangan yang sudah ada (PAK, signature, owner, mount) identik sebelum/sesudah pengujian agen. Agen tidak mengulang install/uninstall, perubahan UID, atau pemeriksaan visual dalam game agar pemasangan pengguna tidak berubah; bukti agen mencakup transport/inspeksi dan batas akses, bukan QA release/signing atau seluruh operasi installer.
- Pengguna kemudian melakukan pengujian perangkat sendiri dan melaporkan aman. Skenario serta hasil per operasi tidak dirinci; penerimaan ini tidak disamakan dengan pengujian menyeluruh.

### Bukti QA port PR #9 (historis, Axeron 2)

Perangkat: Samsung SM-A525F, Android 14/API 34, user 0, ARM64; AxManager 2.0.1.r442/server 20100, UID shell 2000; game Global 3.7.0 dan rilis terjemahan `v3.7.1-id.1`.

Pemeriksaan host/build diulang pada gabungan PR #9 dan base `c5f7c3b` setelah [PR #10](https://github.com/TitoTFP/WuwaIDMobile/pull/10) merged, memakai Rust 1.99.1. Bukti browser/perangkat tetap berasal dari QA sebelum integrasi CI; APK gabungan belum diuji pada perangkat.

| Pemeriksaan | Hasil aktual |
|---|---|
| `npm test` | 52 tes lulus, termasuk type-check frontend/tes |
| `npm run build` | Lulus |
| Browser mobile 412×915 | Pilihan AxManager, grant, Apply UID, reload/persistensi, server mati, pergantian metode; tanpa overflow/error browser |
| `cargo +1.99.1 test --manifest-path src-tauri/Cargo.toml --all-targets` | 43 tes lulus |
| Build APK debug ARM64, JDK 17 | Lulus pada gabungan dengan base CI |
| Android JVM `testUniversalDebugUnitTest`, JDK 21 | 54 tes lulus, tanpa failure/error/skip; batas provider/Binder juga diuji pada SDK 24 dan 28 |
| Instrumentasi `ResourceFilesTest` | APK dibangun dan dipasang; belum dijalankan karena transport ADB terputus |
| Rust 1.99.1 format/Clippy all targets `-D warnings` | Lulus setelah PR #10; tanpa suppression |
| Tauri/Wry desktop native IPC | Lulus: status AxManager tidak tersedia, diagnostics `desktop-unsupported`, probe/resource `not_checked`, install ditolak; bukan browser mock |

| APK debug ARM64 | Byte | SHA-256 |
|---|---:|---|
| QA perangkat sebelum integrasi CI; tetap dipakai untuk QA Root pengguna | 120889742 | `8737909ffe3e7c31f05a782d11f66d78d5000896a0a17cadaccbb838e45b105e` |
| Gabungan PR #9 + base `c5f7c3b`; build/host saja | 120185686 | `53d12bbc058b8d84dc83a67763695e041ae7f1005a66c35f7b5997ea42cfeff3` |

Kedua APK ini bukan bukti signing release atau kompatibilitas upgrade release.

- Dengan `shizuku_server` tidak berjalan dan dashboard `shizuku.running=false`, install dan Apply UID berhasil; HUD game menampilkan `AxManager QA` dan teks quest Indonesia. Bukti install/Apply berasal dari build QA sebelum penambahan gate versi server; APK QA perangkat sebelum integrasi CI membuktikan Reset UID, repair, uninstall, dan pemeriksaan lifecycle berikut.
- Reset mengembalikan HUD ke UID bawaan; terjemahan tetap berjalan. Uninstall melalui aplikasi mengembalikan baseline tanpa patch/UID kustom/jurnal tertunda. SHA-256 16 berkas resmi `Mount*.txt` sama sebelum/sesudah; ini bukan audit seluruh file game.
- Deny/timeout dan pencabutan grant melalui halaman Privilege memblokir akses. Permintaan repair native setelah pencabutan ditolak; tidak ada fallback. Stop server memblokir akses; restart server tetap memerlukan koneksi ulang eksplisit. Pilihan AxManager bertahan setelah restart/update APK dan pergantian proses aplikasi.
- Shizuku 13.5: dialog grant normal dan pembacaan storage privileged berhasil. Pengguna mengonfirmasi hasil QA Root aman; pengujian sebelumnya juga mencakup penolakan/tidak tersedia dan isolasi grant. Recovery akibat interupsi transaksi dicakup suite Rust, belum diinterupsi sengaja pada perangkat.
- ADB USB dan discovery wireless hilang saat mengaktifkan kembali Shizuku, setelah baseline patch dipulihkan. Hasil aktivasi kembali Shizuku dan penghentian server Axeron setelah QA belum terkonfirmasi. APK release/data aplikasinya tidak diganti; konfigurasi wireless debugging tidak diubah.

Pengguna menyetujui merge PR #9 ke rebuild setelah CI lulus. QA perangkat APK gabungan, regresi mutasi Shizuku, instrumentasi `ResourceFilesTest`, serta konfirmasi pemulihan layanan setelah QA sebelumnya diserahkan kepada pengguna untuk dilakukan sesudah merge; hasilnya belum diklaim lulus. Jangan menafsirkan build/mock sebagai pengganti bukti perangkat.

## Ukuran APK dan resource (pengukuran WM-29)

| Pengukuran | Baseline WM-28 | WM-29 |
|---|---:|---:|
| APK debug ARM64 | 479.265.253 byte (457,06 MiB) | 120.178.389 byte (114,61 MiB) |
| `libwuwaidmobile_lib.so` | 236.674.584 byte | 112.417.248 byte |
| ELF section `.debug_*` total | 218.904.759 byte | 94.682.666 byte |
| Kompilasi regex per laporan (20 error) | 41 | 2 |
| Alokasi per panggilan (instance baru + metadata 256 KiB) | 811.348 byte | 283.716 byte |
| Cache fixture (termasuk file asing/staging) | 458.778 byte | 65.562 byte |

APK turun 74,9%; library native 52,5%. ZIP diukur dari central directory dan rentang local header/payload; ELF dengan `llvm-size -A`. Baseline memuat 233.540.556 byte data ZIP usang dari incremental packaging; APK akhir memiliki 929 entry dan hanya gap 4.096 byte untuk signing block. Build debug diulang tanpa `clean`; APK akhir diperiksa signature v2 dan alignment 16 KiB.

Angka alokasi berasal dari harness JVM sementara (5 warmup, 10 panggilan, `ThreadMXBean`, adapter `Os` ke file host); bukan syscall, RSS/PSS atau Binder Android. Regex diukur pada modul frontend yang dipakai UI dengan 20 error dan UID aktif. CPU, baterai, startup, dan RSS/PSS perangkat belum diukur.

## Optimasi storage dan APK release (WM-30)

Build sebelum/sesudah memakai source checkout yang sama selain perubahan WM-30: Linux x86_64, Node 24.20.0, Rust 1.97.1, JDK 17.0.20.1, API/Build Tools 36, NDK 27.3.13750724, ARM64, `npm run tauri android build -- --apk --target aarch64`. Identitas tetap `com.titotfp.wuwaid`, 0.4.0/code 8 dan key rilis asli. APK akhir lolos signature v2 dan `zipalign -c -P 16 4`; bukan bukti upgrade/game pada perangkat.

| Pengukuran (byte) | Sebelum | Sesudah | Penurunan |
|---|---:|---:|---:|
| APK release ARM64 | 10.550.849 | 9.970.529 | 5,50% |
| Library native di APK | 8.451.032 | 7.870.712 | 6,87% |
| Cache PAK setelah operasi pada fixture crash | 12.634.179 | 4.207.297 | 66,70% |
| Total fixture saat UID Apply sebelum jurnal | 25.257.891 | 12.623.712 | 50,02% |
| Total fixture saat UID Reset sebelum jurnal | 21.050.594 | 12.623.712 | 40,03% |
| Cache updater yatim setelah restart | 2.097.154 | 0 | 100% |
| Maksimum cache updater teramati di callback progres | 17.210 | 8.736 | 49,24% |

Metode dan batas:

- APK: ukuran file dan ZIP central directory. Dominan native; Cargo release beralih `opt-level = 3` → `"s"`, tetap LTO, satu codegen unit, abort, dan strip. `"z"` tidak dipilih agar loop vectorization tidak dimatikan. Tidak ada section `.debug_*` pada kedua ELF. R8/resource shrinking, ikon, locale, ABI, dependensi, dan konfigurasi signing tidak diubah. Pengaruh CPU/startup dari profile `"s"` belum diukur di perangkat.
- Rust: harness sementara menjalankan kode installer sebenarnya lewat `LocalStorage`, PAK v12 4.207.297 byte dengan SQLite UID valid dan payload 4 MiB. Awal identik: source terverifikasi, satu partial seukuran source, satu derived PAK seukuran source, database yatim 12 KiB. Install → UID Apply → Reset → uninstall berhasil; source dan file game bawaan fixture tetap utuh.
- Angka native adalah jumlah panjang file reguler pada checkpoint, **bukan high-water mark seluruh syscall**. UID masih memerlukan source, derived PAK, stage, dan pemasangan lama secara bersamaan saat staging; pelepasan derived setelah staging mengurangi footprint saat commit, bukan menghilangkan kebutuhan ruang puncak staging. Cache sumber tunggal tetap tersedia untuk penggunaan offline.
- Updater: Robolectric API 28, TLS localhost, APK ARM64 sungguhan yang ditandatangani key fixture sementara (8.474 byte). Baseline repository sebelum perubahan dan repository akhir menjalankan skenario sama: restart dengan orphan/partial, unduh kandidat, pilih aset dengan digest baru, unduh dan verifikasi lagi. Pengukuran progres tidak mengamati setiap write metadata. APK final, ukuran/SHA-256, identitas dan signing tetap diverifikasi; ini bukan installer Android asli.
- Tabel di atas hanya pengukuran host. Hasil Settings perangkat dicatat terpisah di bawah; alokasi blok filesystem, ruang PackageInstaller, dan puncak storage perangkat belum diukur. Penurunan ukuran APK bukan bukti penurunan total storage terpasang.

Audit artifact pembanding awal oleh subagent mengonfirmasi SHA-256, identitas/signing, ARM64, serta kesamaan library optimized dengan output build pada fase pengukuran itu (`src-tauri/target/wm30/qa/host-audit.txt`). **Alignment ZIP bukan alignment ELF**: kedua library awal memiliki `PT_LOAD.p_align = 0x1000` (4 KiB). Kelulusan `zipalign -P 16` tidak membuktikan loading native pada perangkat dengan page size 16 KiB; dukungan runtime tersebut belum diuji.

Artifact pembanding sebelum koreksi lock berada di `src-tauri/target/wm30/`: `baseline-release.apk`, `optimized-release.apk`, dan `SHA256sums.txt`. **Jangan gunakan `optimized-release.apk` untuk tes lanjutan:** pengguna melaporkan operasi PAK gagal pada API lock Android. APK koreksi memakai nama `cache-lock-fixed-release.apk`; baseline/optimized lama dipertahankan hanya untuk provenance pengukuran. Output build standar tetap `src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release.apk`. Artifact tidak dikomit.

APK koreksi lock: **9.970.593 byte**, SHA-256 `a3f6a06b5ecb01c232fa994cd387427e912f78d3633bf175ca37034913974f79`, package/key/0.4.0/code 8 tetap. Signature v2 dan alignment ZIP 16 KiB lolos; library yang dikemas cocok output build dan mengimpor `flock@LIBC`. Sebanyak 42 tes Rust dan smoke lock dua proses lulus. Pengguna kemudian mengonfirmasi tes perangkat aman setelah investigasi konflik PAK manual di bawah; hasil pengukuran artifact awal tidak diganti.

### Hasil QA perangkat asli (bertahap)

Pada fase awal, subagent DeviceQA menguji Samsung SM-A525F, Android 14/API 34, ARM64; Wuthering Waves Global 3.7.0. Package debug tetap dipertahankan. Package release awalnya belum ada: baseline dipasang fresh, lalu diganti optimized memakai `adb install -r`, tanpa uninstall/clear data/downgrade. Optimized yang ditarik dari perangkat cocok byte/digest/signing dengan artifact fase awal.

Settings → App info → Storage, sesudah launch dan cek update manual pada setiap fase:

| Komponen (unit sesuai UI, dibulatkan) | Baseline fresh | Optimized setelah replacement |
|---|---:|---:|
| App | 12,24 MB | 11,66 MB |
| Data | 4,42 MB | 8,63 MB |
| Cache | 438 KB | 442 KB |
| Total | 17,11 MB | 20,74 MB |

Komponen App turun, tetapi total naik karena Data bertambah. Data dipertahankan dan urutan pengujian tidak mengisolasi pertumbuhan state/WebView; jangan mengatribusikannya pada perubahan kode atau mengklaim total storage turun. Ini bukan pengukuran peak.

- Cek update manual pada kedua APK menampilkan belum ada stable resmi; API latest mengembalikan 404. Download/PackageInstaller belum diuji; kedua artifact code 8 bukan kandidat versi lebih tinggi.
- Pada fase awal, Shizuku 13.6 terpasang tetapi UI manager dan WuwaID menyatakan tidak berjalan setelah startup ADB resmi mengembalikan exit 0. Snapshot proses hanya memuat manager, bukan `shizuku_server`; log terfilter tidak memberi penyebab (`qa/shizuku-start-output.txt`). Grant dan operasi game belum diuji pada fase tersebut; exit starter bukan bukti service siap.
- Patch milik WuwaID dan status adanya UID kustom dipertahankan; teks privat tidak dibaca/disimpan. Empat file resmi sampel (dua mount, satu PAK beserta signature) memiliki SHA-256 sebelum/sesudah yang sama; bukan validasi seluruh tree game.
- Pengamatan log PID/exit-info tidak menemukan crash/ANR optimized pada jendela uji. Bukti: `src-tauri/target/wm30/qa/`, terutama `storage-settings.txt`, `package-identity.txt`, `runtime-observations.txt`, `game-{initial,final}-sha256.txt` dan screenshot `baseline-storage-breakdown/screen.png`, `optimized-storage-breakdown/screen.png`.

Retest berikutnya memakai APK koreksi lock dan Shizuku wireless debugging yang dinyalakan pengguna. Pengguna mengonfirmasi pemasangan dengan UID bawaan aman, lalu melaporkan UID kustom tidak tampil. Pemeriksaan read-only membuktikan PAK installer dan ketiga baris UID sudah berisi teks kustom, tetapi ada PAK terjemahan manual lain di `Saved/Paks` yang hash-nya identik dengan rilis asli. Sesudah temuan dan instruksi menonaktifkan PAK manual, pengguna mengonfirmasi hasil aman. Ini penerimaan pengguna, bukan retest otomatis; lima siklus, recovery setelah interupsi, peak storage, dan PackageInstaller update lebih tinggi belum dikonfirmasi.

### Konflik PAK manual dengan UID kustom

Jika UID kustom tercatat aktif tetapi game tetap menampilkan UID bawaan, periksa apakah ada terjemahan manual di:

```text
Android/data/<package-game>/files/UE4Game/Client/Client/Saved/Paks/
```

Pada perangkat QA ditemukan `pakchunk0-ID-WindowsNoEditor_1000_P.pak` tanpa UID kustom, di luar kepemilikan installer `Resources/<versi>/wuwaid`. PAK manual dapat menimpa database yang sama; status sehat installer hanya memverifikasi berkas miliknya, bukan seluruh urutan mount game.

Tutup paksa game, pindahkan hanya PAK terjemahan manual yang dikenali ke backup di luar direktori game, lalu buka game kembali. Jangan hapus PAK resmi atau berkas `Resources/<versi>/wuwaid`. Jika PAK installer sudah memuat UID kustom, tidak perlu reinstall APK atau mengunduh ulang PAK untuk retest ini. Aplikasi tidak memindahkan/menghapus file manual tersebut otomatis.

### Prosedur QA storage perangkat

1. Catat model/Android, filesystem, package game, metode Root/Shizuku/AxManager, versi game/PAK, serta kondisi WebView yang sama. Bandingkan kedua APK tanpa uninstall/clear data. Catat ukuran aplikasi/data/cache dari Settings sebelum dan sesudah skenario; ukur puncak dengan tooling yang tersedia dan izin yang sudah diberikan, atau tandai belum terukur.
2. Ulangi install/update, UID Apply/Reset, repair/recovery, dan uninstall. Periksa hash file game bawaan sebelum/sesudah dan hasil UID/terjemahan di game. Backup/jurnal recovery tidak boleh dibersihkan sebagai cache.
3. Putuskan jaringan atau hentikan proses saat unduh PAK/UID; setelah membuka ulang, jalankan operasi cache berikutnya. Partial/UID scratch yang namanya direservasi harus hilang; source terverifikasi dan file asing tetap ada. Kegagalan staging/commit harus tetap dapat diperbaiki lewat recovery.
4. Jalankan [QA updater](#qa-updater-wajib), termasuk versi stabil resmi yang lebih tinggi, installer batal/izin ditolak, retry offline dari APK terverifikasi, dan restart setelah update. Partial/yatim dibersihkan saat restore; APK ready tidak dihapus hanya karena installer batal atau pemeriksaan jaringan gagal.
5. Ulangi sedikitnya lima siklus pada kondisi sama. Catat byte/MiB sebelum/sesudah dan persentase, kegagalan cleanup, ruang puncak yang benar-benar teramati, serta hasil pass/fail. Hasil host di atas tidak menggantikan penerimaan perangkat.


## Nama dan ikon

Nama tampilan **WuwaID Mobile**. Samakan `productName` dan judul window di `src-tauri/tauri.conf.json` dengan `app_name` dan `main_activity_title` di `src-tauri/gen/android/app/src/main/res/values/strings.xml`. `com.titotfp.wuwaid` adalah identifier, bukan nama tampilan; jangan ubah untuk rebranding.

Sumber ikon `src-tauri/app-icon.png` berasal dari [WuwaIDLauncher](https://github.com/TitoTFP/WuwaIDLauncher/blob/676c4a9b3b3f14ccb570d90252dc8972dbac8d13/src-tauri/icons/icon.png). Regenerasi:

```sh
npm run tauri icon -- src-tauri/app-icon.json
```

CLI menghasilkan ikon bundle di `src-tauri/icons/` dan Android langsung di `src-tauri/gen/android/app/src/main/res/`, termasuk adaptive icon Android 8+ dan density mdpi–xxxhdpi. Aset iOS juga dihasilkan, tetapi iOS tidak dikonfigurasi dan aset itu tidak disertakan.

`src-tauri/app-icon.json` memakai `app-icon-android-foreground.svg` untuk adaptive foreground: artwork 72×72 di tengah kanvas 108×108 (padding transparan 18 tiap sisi), sesuai viewport 72 dp. Ukuran awal 66×66 menyisakan bingkai putih; 72×72 memperbesar foto sekitar 9% sambil mempertahankan mask launcher. Jangan ganti dengan foreground penuh, regenerasi langsung dari PNG, atau mengandalkan `android_fg_scale` (CLI yang dipakai tidak mengecilkannya). Untuk xxxhdpi, batas artwork `(72,72)–(360,360)` pada kanvas 432×432.

Periksa APK final—alpha/padding, label aplikasi/activity, dan resource ikon—bukan hanya RGB/sumber PNG. Preview mask lokal bukan screenshot perangkat. Uji update tanpa clear data; launcher dan info aplikasi harus menampilkan nama/ikon baru.

## Release dan upgrade

Tujuh APK publik v0.1.0–v0.3.4 memakai package `com.titotfp.wuwaid`, versionCode 1–7, dan sertifikat SHA-256:

```text
2cb1b4fed5446cf52018b5d56d58c379d7328022c1ba17278c19c0002e484df9
```

Rilis stabil `1.0.0` memakai versionCode `9`. Kandidat `1.0.1` memakai versionCode `10` dan package serta sertifikat yang sama. Samakan versi Tauri, Cargo, dan npm beserta lockfile. Untuk release berikutnya, naikkan `bundle.android.versionCode` dari code publik terakhir. Jangan memakai `adb install -d` sebagai bukti upgrade normal.

Gunakan private keystore rilis asli. Sediakan dari secret store lokal/CI, bukan Git/chat:

- Local build: `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Workflow `.github/workflows/release.yml`: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

Build signed release:

```sh
npm run tauri android build -- --apk --target aarch64
```

Task `verifyUpgradeSigning` memeriksa sertifikat alias dan versionCode sebelum build. Secret tidak lengkap atau sertifikat berbeda menggagalkan build; tidak ada fallback debug atau pembuatan key baru. Sertifikat publik dalam APK tidak dapat menggantikan private key.

Workflow release dipicu manual pada tag yang sudah ada; pilih tag yang sama sebagai workflow ref, bukan `main`. Fase kandidat membangun dan memverifikasi APK signed, menjalankan tes host, lalu menyimpan APK/checksum sebagai artifact immutable selama 14 hari. `upgrade_qa_completed=false` (default) tidak memublikasikan release. Fase publikasi memerlukan `upgrade_qa_completed=true` dan ID artifact yang sudah lolos QA; asal repository/workflow, commit/tag, checksum, identitas APK, sertifikat, dan alignment diperiksa ulang. APK tidak dibangun atau ditandatangani ulang saat publikasi. Keystore sementara dihapus meskipun job gagal.

Setelah QA, publikasikan sebagai rilis stabil (`draft: false`, `prerelease: false`) agar tersedia di updater. Sertakan tepat satu aset `WuwaID-Mobile-v<versi>.apk` dan `SHA256sums.txt`. Updater menerima SHA-256 dari GitHub digest atau checksum tersebut.

### Kandidat dan publikasi v1.0.1

1. Commit dan push source yang sudah ditinjau. Buat dan push tag `v1.0.1` pada commit yang sama; jangan pindahkan tag setelah QA.
2. Bangun kandidat signed tanpa memublikasikan release:

   ```sh
   gh workflow run release.yml --ref v1.0.1 -f tag=v1.0.1 -F upgrade_qa_completed=false
   ```

3. Ambil artifact `wuwaidmobile-signed-v1.0.1`. Catat **ID artifact** dari ringkasan run atau URL artifact; ini bukan ID run. Verifikasi APK `com.titotfp.wuwaid`, versi `1.0.1`, code `10`, sertifikat di atas, dan checksum. Jalankan [QA upgrade/updater](#qa-updater-wajib) dari APK publik `1.0.0` tanpa uninstall/clear data. APK debug bukan bukti upgrade in-place.
4. Setelah artifact tersebut lulus QA, publikasikan APK yang sama:

   ```bash
   read -r -p "ID artifact signed yang lolos QA: " CANDIDATE_ARTIFACT_ID
   gh workflow run release.yml --ref v1.0.1 -f tag=v1.0.1 -F upgrade_qa_completed=true -f candidate_artifact_id="$CANDIDATE_ARTIFACT_ID"
   ```

5. Pastikan GitHub Release bukan draft/prerelease, menjadi stable terbaru, dan memuat APK serta checksum yang sama dengan hasil QA. Artifact kedaluwarsa atau salah asal/commit harus ditolak; buat dan uji kandidat baru, bukan membangun ulang diam-diam saat publikasi. Jangan memakai checkbox QA untuk melewati kegagalan upgrade, installer, atau hasil game.

### QA updater wajib

1. Mulai dari APK publik lama; pasang kandidat dengan `adb install -r`, tanpa uninstall/clear data. Uji deteksi, migrasi, recovery, UID, dan game; pastikan mount/signature benar dan hash berkas game bawaan tidak berubah sebelum/sesudah. APK debug hanya menguji migrasi berkas; package berbeda tidak menguji PackageManager/data upgrade in-place.
2. Periksa banner, versi terpasang, dan tombol manual. Jika tidak ada stable yang lebih baru (termasuk hanya ada prerelease), UI harus menyatakan belum ada update stabil, bukan bahwa versi terpasang terbaru.
3. Sebelum publikasi, uji upgrade `1.0.0`/code 9 → kandidat signed `1.0.1`/code 10 melalui `adb install -r`. Updater produksi hanya bisa menawarkan versi baru setelah rilis stabil resmi tersedia; jalankan smoke test unduh/pasang dari `1.0.0` setelah publikasi. Jangan gunakan metadata palsu atau menurunkan versionCode.
4. Uji perbandingan versi numerik (`0.4.9` → `0.4.10`), progres, ukuran/digest, dan penolakan APK dengan package, signing/tag, versionCode, minSdk, ABI, atau signature tidak sesuai. APK invalid tidak boleh mencapai installer.
5. Tolak izin sumber tak dikenal lalu coba lagi setelah grant; tutup app dari pengaturan izin dan buka lagi untuk memastikan APK cache diverifikasi ulang. Batalkan installer: jangan laporkan sukses dan sediakan retry. Setelah sukses, versi baru harus terbuka, data/PAK bertahan, dan downgrade tidak ditawarkan.
6. Putuskan jaringan saat cek/unduh atau hentikan app saat download. Partial/corrupt cache tidak siap dipasang dan harus dapat diunduh ulang. Background check tidak boleh mencuri fokus/draft UID atau memblokir akses game.

Hasil host/build/metadata bukan bukti penerimaan PackageManager, alur installer, atau game. Catat cakupan uji perangkat yang benar-benar dijalankan; laporan penerimaan pengguna sebelumnya tidak membuktikan semua skenario checklist ini.

## Tes host dan instrumentation Android

Tes host frontend/Rust (sesudah environment Android disiapkan):

```sh
npm test
npm run build
cargo test --manifest-path src-tauri/Cargo.toml --all-targets
```

`ResourceFilesTest` memiliki delapan tes instrumentation: batas teks 256 KiB/file kosong; FD saat sukses/gagal; digest segar setelah mutasi ukuran tetap, pergantian mode, atau deletion; batch 64 vs 65 dan urutan hasil; path invalid, symlink, FIFO, serta direktori non-file. Fixture berada di cache privat app debug, bukan folder game. Build/packaging saja tidak berarti tes dijalankan.

Setelah build debug ARM64 dari bagian persiapan di atas, siapkan JVM tes:

Suite Robolectric yang mencakup API 36 memerlukan **JDK 21** (JDK 17 ditolak sebelum tes kelas dijalankan). Setelah build APK dengan JDK 17, ubah JVM untuk tes; CI/release melakukan pergantian yang sama:

```sh
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
```


```sh
./src-tauri/gen/android/gradlew -p src-tauri/gen/android \
  :app:testUniversalDebugUnitTest :app:assembleUniversalDebugAndroidTest \
  -PabiList=arm64-v8a -ParchList=arm64 -PtargetList=aarch64 \
  -x :app:rustBuildArm64Debug --no-configuration-cache --console=plain
```

Pengecualian `rustBuildArm64Debug` hanya memakai library ARM64 yang baru dibangun; Tauri build harus sukses pada source sama terlebih dahulu. ABI properties membatasi packaging ke ARM64. Jangan pakai pengecualian setelah mengubah source native tanpa membangun ulang lewat Tauri CLI.

Pada perangkat ARM64, pengguna dapat memasang dan menjalankan instrumentation:

```sh
adb install -r src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk
adb install -r src-tauri/gen/android/app/build/outputs/apk/androidTest/universal/debug/app-universal-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.titotfp.wuwaid.storage.ResourceFilesTest \
  com.titotfp.wuwaid.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Package test `com.titotfp.wuwaid.debug.test`; runner `androidx.test.runner.AndroidJUnitRunner`; target `com.titotfp.wuwaid.debug`, minSdk 24, targetSdk 36. Catat versi Android dan hasil pass/fail/skip. Tes host/instrumentation tidak membuktikan Binder, izin/kematian service Root/Shizuku, scoped storage game, atau interpretasi mount game. QA perangkat juga harus mencakup kedua package game dan metode akses yang tersedia, izin ditolak/diberikan, service terputus, upgrade saat UserService lama masih hidup (pastikan version change membuat service baru), install/repair/uninstall, recovery/migrasi legacy valid, UID Apply/Reset, dan hasil di game.

## Diagnostik dan ekspor

JSON diagnostik `schemaVersion: 1` mencakup `generatedAt`, `mock`, `backend`, `sections`, `privacy`, dan `errors`; bagian application/device/probe/resource/patch/release dikumpulkan independen. `status: ok` hanya berarti pemeriksaan selesai: cek `patch.data.healthy` dan pesan masalah. `error`/`not_checked` memakai `data: null`, bukan `installed: false`.

Ekspor tidak memasang PAK, meminta root grant, atau mengunggah laporan. Release PAK hanya dibaca dari cache sesi. Metadata perangkat tidak memuat serial/ID akun; UID kustom dihilangkan. Teks bebas meredaksi UID aktif dan input operasi yang diketahui. History hanya sesi frontend, maksimal 20 error; pesan yang tersimpan dibatasi 1024 unit UTF-16 setelah redaksi. Reload menghapus history. Logcat/trace tidak disertakan.

Regex UID dibuat sekali per snapshot/serialisasi, longest-first agar UID yang memuat `[REDACTED]` atau karakter regex tidak lolos sebagai substring; pola tidak disimpan lintas panggilan/sesi.

- Android **Simpan** memakai `ACTION_CREATE_DOCUMENT` (`application/json`) dan menulis UTF-8 ke URI `content://` pilihan pengguna. Status `saved` hanya setelah write/flush/close sukses; cancel, URI/hasil dialog invalid, kegagalan write/close, atau lifecycle tertutup bukan sukses. Provider bisa meninggalkan dokumen parsial saat gagal.
- Android **Bagikan** memakai `ACTION_SEND`, chooser, FileProvider dan ClipData dengan grant read-only. Setiap laporan punya URI unik; fitur hanya menambah cache `diagnostics/`, tidak mengubah cakupan updater `app-updates/`. Laporan fitur yang lebih tua dari 24 jam dibersihkan pada share berikutnya. `share_opened` berarti chooser terbuka, bukan penerima berhasil mengirim/menerima.
- Satu ekspor aktif menahan snapshot hingga dialog/worker selesai; lifecycle menolak request tertunda dan mengabaikan callback terlambat. Browser memakai Blob download (`download_started`, bukan `saved`) dan Web Share bila didukung. Desktop native tidak menyediakan dialog Android.

Pemeriksaan perangkat: simpan dan buka JSON UTF-8; batalkan pemilih dokumen dan pastikan tidak ada status sukses. Bagikan lalu buka di penerima dan bandingkan isinya dengan hasil simpan; chooser terbuka bukan bukti pengiriman dan laporan sebelumnya harus tetap immutable. Uji tanpa grant, dengan Root/Shizuku, dan setelah service terputus: bagian yang tak diperiksa harus `null`. Terapkan UID privat dan operasi gagal, lalu pastikan UID tidak muncul dalam laporan/error; history maksimal 20 dan hilang setelah reload. Catat Android, provider penyimpanan, penerima, serta hasil/batal/gagal.

## Retensi cache PAK dan batas keselamatan

Cache privat `release-paks` memakai nama `<tag-rilis>-<nama-asset>.pak`. `release::download` mengembalikan `CachedPak`; caller wajib mempertahankannya hingga instalasi/UID selesai. Handle ini memegang OS file lock `.cache.lock`, sehingga operasi kedua gagal sebelum cleanup, dan kematian proses melepas lock otomatis.

Koreksi regresi Android: Rust 1.97 `std::fs::File::try_lock()` mengembalikan `Unsupported` pada target Android, sehingga tes Linux sebelumnya tidak membuktikan operasi lock pada perangkat. Android dan Linux sekarang berbagi `libc::flock(fd, LOCK_EX | LOCK_NB)`; platform lain tetap memakai API std. `WouldBlock` dilaporkan sebagai kontensi, sedangkan error OS lain ditampilkan sebagai kegagalan lock yang sebenarnya. Tidak ada penghapusan lock atau fallback tanpa lock.

Smoke host dua proses pada jalur produksi memverifikasi penolakan kontensi sebelum cleanup, scratch aktif tetap utuh, lock terlepas setelah proses pemegang dihentikan, serta akuisisi ulang dengan source terverifikasi tetap utuh. [Hasil perangkat](#hasil-qa-perangkat-asli-bertahap) dicatat terpisah dari smoke host. Berkas `.cache.lock` yang tertinggal tidak perlu dihapus karena kepemilikan lock ada pada descriptor/proses, bukan keberadaan nama file.

Setelah metadata rilis valid dan lock eksklusif diperoleh, operasi cache berikutnya membersihkan scratch yatim: regular `.<tag>-<asset>.pak.part-<pid>-<counter>`, regular `uid-pak-<6 alfanumerik>.pak`, dan direktori `uid-work-<6 alfanumerik>` beserta isinya tanpa mengikuti symlink. Prefix/suffix yang tidak cocok, symlink di root cache, file asing, dan direktori lain tidak disentuh. Drop normal tetap membersihkan partial/UID; cleanup sesudah proses mati menunggu operasi cache berikutnya, bukan passive dashboard refresh.

Setelah cache hit terverifikasi atau unduhan dipublikasikan atomik dengan ukuran/SHA-256 benar, source regular lama yang cocok validator tag/asset dapat dihapus; source saat ini dipertahankan. Metadata, transfer, atau integritas gagal sebelum verifikasi/publikasi tidak mengeviksikan source lama. Kegagalan inspeksi/penghapusan cache menghentikan operasi sebelum penulisan game. Eviksi yang sudah selesai tidak dipulihkan bila instalasi berikutnya gagal; rollback game tidak bergantung pada source cache lama.

Derived PAK UID dihapus setelah staging selesai, sebelum penanganan jurnal/commit. Stage, backup dan jurnal milik installer tetap mengikuti pemeriksaan hash/kepemilikan dan recovery; tidak ada perubahan format PAK, mount/signature, IPC, atau akses Root/Shizuku. Satu source tidak membatasi ukuran seluruh direktori; staging UID tetap membutuhkan beberapa salinan.

Cache `app-updates` membersihkan `update.part.apk`, `candidate.json.part`, serta `update.apk` tanpa metadata saat restore pertama. Pemilihan kandidat berbeda yang sudah valid membuang APK/metadata lama; cek gagal/offline mempertahankan kandidat ready. Unduh kandidat ready memverifikasi ulang file tanpa transfer atau partial kedua. File asing tidak dihapus. Cleanup yang gagal dilaporkan, bukan dianggap sukses. Installer batal tetap menyimpan APK untuk retry; versi terpasang yang sudah menyamai kandidat membuang cache saat restore.

`read_text` mengisi satu `ByteArray` sesuai ukuran file yang sudah diperiksa (batas 256 KiB), tanpa scratch buffer atau salinan akhir. Hash mengalokasikan buffer 64 KiB saat diperlukan dan menggunakannya ulang dalam instance service; file kosong/short read, no-follow, identitas file, dan penutupan FD tetap ditangani. Tidak ada polling, retry, telemetry, probe sintetis, atau cache metadata/hash lintas operasi. UID PAK rebuild tetap memakai buffer entry `repak`, bukan constant-memory.

UserService Shizuku harus memulai ulang implementasi setelah upgrade kode: `UserServiceArgs.version` dinaikkan 3 → 4 agar proses service lama tidak dipakai dengan kode baru. Ikuti [kontrak Shizuku untuk version/recreating service](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/api/src/main/java/rikka/shizuku/Shizuku.java#L624-L633).

## Referensi

- [Prasyarat Tauri](https://v2.tauri.app/start/prerequisites/)
- [Perintah Android Tauri CLI](https://v2.tauri.app/reference/cli/#android-build)
- [Cargo: debug information dan runtime checks](https://doc.rust-lang.org/cargo/reference/profiles.html#debug)
- [Gradle: incremental task](https://docs.gradle.org/current/userguide/custom_tasks.html#incremental_tasks)
- Implementasi terdahulu: [Resource Mount](https://github.com/TitoTFP/WuwaIDLauncher/blob/676c4a9b3b3f14ccb570d90252dc8972dbac8d13/src-tauri/src/engine/installer.rs#L95-L111), [algoritme UID](https://github.com/TitoTFP/WuwaIDLauncher/blob/676c4a9b3b3f14ccb570d90252dc8972dbac8d13/src-tauri/src/engine/patch_asset.rs), [ikon](https://github.com/TitoTFP/WuwaIDLauncher/blob/676c4a9b3b3f14ccb570d90252dc8972dbac8d13/src-tauri/icons/icon.png), dan [kontrak berkas v0.3.4](https://github.com/TitoTFP/WuwaIDMobile/blob/v0.3.4/app/src/main/java/com/titotfp/wuwaid/GamePaths.kt).
- Kontrak dependensi: [Shizuku UserService API](https://github.com/RikkaApps/Shizuku-API#userservice), [libsu 6.0.0](https://github.com/topjohnwu/libsu/tree/6.0.0), dan [reqwest connection pool](https://docs.rs/reqwest/0.12.28/reqwest/blocking/struct.Client.html).
- Rilis PAK historis: [daftar rilis](https://github.com/TitoTFP/WuwaID/releases) dan [v3.7.1-id.1](https://github.com/TitoTFP/WuwaID/releases/tag/v3.7.1-id.1).
