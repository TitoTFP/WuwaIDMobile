---
name: WuwaID Mobile
description: Antarmuka tugas dengan hierarki ala denah transit.
colors:
  canvas: "#edf3f1"
  surface: "#fbfdfa"
  ink: "#142f32"
  muted: "#4c6362"
  line: "#cadbd5"
  accent: "#146356"
  on-accent: "#fff"
  board: "#123239"
  board-ink: "#f3faf6"
  signal: "#b9ebd1"
  dark-canvas: "#0d1c20"
  dark-surface: "#122529"
  dark-ink: "#edf7f3"
  dark-accent: "#ade2ca"
typography:
  display:
    fontFamily: "system-ui, sans-serif"
    fontSize: "2.625rem"
    fontWeight: 720
    lineHeight: 1.08
    letterSpacing: "-0.038em"
  title:
    fontSize: "1.0625rem"
    fontWeight: 690
    lineHeight: 1.3
  body:
    fontFamily: "system-ui, sans-serif"
    fontSize: "1rem"
    lineHeight: 1.55
rounded:
  control: "0.5rem"
  panel: "0.875rem"
spacing:
  xs: "0.5rem"
  sm: "0.75rem"
  md: "1rem"
  lg: "1.5rem"
  xl: "2rem"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.control}"
    height: "2.75rem"
    padding: "0.625rem 0.875rem"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
    padding: "{spacing.lg}"
---

## Overview

Ambil disiplin denah transit, bukan tampilannya secara harfiah: posisi status tetap dan tindakan berikutnya jelas. [Stylesheet](src/styles.css) adalah sumber implementasi.

## Colors

Papan nila laut memberi bobot pada status terjemahan dan UID aktif. Mint mengarahkan tindakan utama dan pilihan aktif. Gunakan kuning atau merah hanya untuk status yang memerlukan perhatian. Warna tidak menggantikan ikon dan teks.

## Typography

Gunakan sans bawaan tanpa unduhan font. Judul Beranda dan judul tab memakai skala yang sama di ponsel (2rem). Angka versi, UID, dan progres memakai angka tabular.

## Layout

Rel navigasi menjadi dock bawah di bawah 48rem. Papan Beranda membagi tugas dan kesiapan mulai 56rem; panel lanjutan, UID, dan Perawatan memakai dua kolom mulai 62rem. Kontrol sentuh minimal 44px. Sisakan ruang untuk dock dan inset perangkat.

## Elevation & Depth

Papan dan panel dibedakan lewat warna bidang, garis, dan ruang. Bayangan netral hanya untuk dialog dan toast. Transisi singkat menandai perubahan status dan ditiadakan saat reduced motion aktif.

## Components

- **Pemeriksaan kesiapan:** simpul berurutan untuk game, akses, dan file; bedakan gagal diperiksa dari belum diperiksa. Rel tetap di tempat saat status berubah.
- **Tindakan:** satu tombol utama per tugas, tindakan sekunder bergaris, dan tombol bahaya hanya untuk penghapusan. Fokus keyboard harus terlihat.
- **UID:** tampilkan nilai aktif dan bawaan terpisah dari draf. Editor memakai label yang tetap terlihat dan penghitung batas.
- **Perawatan:** gunakan baris pemulihan, diagnostik, dan penghapusan; simpan rincian teknis dalam disclosure.

## Do's and Don'ts

- Pertahankan kontras teks pada kedua tema dan uji label Indonesia pada lebar sempit.
- Jangan tampilkan persentase sebelum operasi melaporkan progres atau gunakan warna saja untuk status.
