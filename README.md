<p align="center">
  <a href="https://github.com/ExtremeBoyGG/nonton-indo">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://cdn-icons-png.flaticon.com/512/2920/2920297.png">
      <source media="(prefers-color-scheme: light)" srcset="https://cdn-icons-png.flaticon.com/512/3136/3136684.png">
      <img src="https://cdn-icons-png.flaticon.com/512/3136/3136684.png" alt="Logo" width="80" height="80">
    </picture>
  </a>

  <h3 align="center">Nonton Indo</h3>

  <p align="center">
    CloudStream 3 Extensions untuk Situs Streaming Indonesia
    <br />
    Nonton anime dan film favoritmu langsung dari CloudStream
  </p>

  <p align="center">
    <a href="https://github.com/ExtremeBoyGG/nonton-indo/issues">Report Bug</a>
    ·
    <a href="https://github.com/ExtremeBoyGG/nonton-indo/issues">Request Situs</a>
  </p>
</p>

---

## Tentang

**Nonton Indo** adalah kumpulan plugin [CloudStream 3](https://github.com/recloudstream/cloudstream) untuk situs streaming populer di Indonesia. Plugin ini memungkinkan kamu menonton anime, film, dan drama langsung dari aplikasi CloudStream di Android.

### Fitur

- Streaming anime subtitle Indonesia
- Film dan drama Indonesia
- Support multiple server
- Download support
- Gratis dan open source

---

## Daftar Plugin

### Anime

| Plugin | Situs | Status |
|--------|-------|--------|
| AnimeIndo | anime-indo.lol | Development |
| Oploverz | oploverz.ch | Development |
| Otakudesu | otakudesu.blog | Development |
| Nimegami | nimegami.id | Development |
| Animasu | animasuid.com | Development |
| Samehadaku | v2.samehadaku.how | Development |
| Dubindo | dubbindo.site | Development |
| Kitanonton | kitanonton2.world | Development |

### Film & Drama

| Plugin | Situs | Status |
|--------|-------|--------|
| Pahe | pahe.ink | Development |
| Rebahin | rebahin | Development |
| Idlix | idlix | Development |
| LK21 | lk21official | Development |
| Cgvindo | cgvindo | Development |

---

## Instalasi

### Prasyarat

- [CloudStream 3](https://github.com/recloudstream/cloudstream) terinstall di Android

### Cara Install

1. Buka CloudStream
2. Masuk ke **Settings** > **Repository**
3. Tambahkan URL repository berikut:

```
https://raw.githubusercontent.com/ExtremeBoyGG/nonton-indo/builds/repo.json
```

4. Kembali ke halaman utama, plugin akan muncul di tab **Extensions**
5. Install plugin yang diinginkan

---

## Cara Build

### Prasyarat

- JDK 17+
- Android SDK

### Build

```bash
# Clone repository
git clone https://github.com/ExtremeBoyGG/nonton-indo.git
cd nonton-indo

# Build semua plugin
./gradlew assembleRelease

# Build satu plugin
./gradlew :AnimeIndo:assembleRelease
```

File `.cs3` akan dihasilkan di folder `build/` masing-masing plugin.

---

## Kontribusi

Kontribusi sangat diterima. Berikut cara berkontribusi:

1. Fork repository ini
2. Buat branch baru (`git checkout -b feature/situs-baru`)
3. Commit perubahan (`git commit -m 'Tambah plugin SitusBaru'`)
4. Push ke branch (`git push origin feature/situs-baru`)
5. Buat Pull Request

### Menambah Plugin Baru

1. Buat folder baru di root repository
2. Buat `build.gradle.kts` dengan konfigurasi plugin
3. Buat class provider yang extends `MainAPI()`
4. Buat class plugin yang extends `Plugin()`
5. Tambahkan plugin ke `settings.gradle.kts`

Lihat contoh plugin yang sudah ada untuk referensi.

---

## Disclaimer

- Plugin ini hanya menyediakan interface untuk mengakses situs publik
- Konten disediakan oleh situs pihak ketiga, bukan oleh kami
- Gunakan dengan bijak dan sesuai hukum yang berlaku

---

## Lisensi

MIT License - lihat [LICENSE](LICENSE) untuk detail.

---

## Credit

- [CloudStream 3](https://github.com/recloudstream/cloudstream) - Platform
- [hexated](https://github.com/hexated/cloudstream-extensions-hexated) - Referensi extension

---

## Star History

<a href="https://star-history.com/#ExtremeBoyGG/nonton-indo&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=ExtremeBoyGG/nonton-indo&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=ExtremeBoyGG/nonton-indo&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=ExtremeBoyGG/nonton-indo&type=Date" />
  </picture>
</a>
