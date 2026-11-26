# AutoMine Bot — Fabric 1.21.11

Mod client-side: **auto mine ore**, **Block ESP tembus tembok**, **auto tebang kayu**, dengan **pathfinding otomatis** dan **GUI** yang bisa dibuka dari **Mod Menu**.

> ⚠️ Mod ini fitur "cheat" (bot + ESP). Aman dipakai di single-player / server sendiri.
> Di server publik biasanya **dilarang** dan bisa kena ban. Pakai dengan tanggung jawab sendiri.

## Status
- Kode lengkap ditulis untuk MC **1.21.11**, Fabric Loader 0.18.4, Fabric API 0.141.1+1.21.11, **Mojang mappings**, Loom 1.14.
- **Belum dikompilasi/dites di game** (sandbox pembuat tidak punya akses Maven Fabric). Kalau ada error compile, lihat bagian "Titik rawan" di bawah.

## Build & jalankan
Syarat: JDK 21.

1. Buat wrapper Gradle (jar wrapper tidak disertakan):
   `gradle wrapper --gradle-version 9.2.1`
   (atau salin folder `gradle/` + `gradlew` dari template di https://fabricmc.net/develop/template)
2. Test di game: `./gradlew runClient`
3. Build jar: `./gradlew build` → `build/libs/automine-1.0.0.jar`
4. Taruh jar di folder `mods/` bersama **Fabric API** (dan **Mod Menu** untuk akses lewat menu mod).

## Cara pakai
| Tombol | Fungsi |
|---|---|
| **Right Shift** | Buka GUI AutoMine |
| **G** | Mulai / stop Auto Mine |
| **H** | Mulai / stop Auto Tebang Kayu |

Tombol bisa diubah di Options → Controls → Key Binds → "AutoMine Bot".
**Mod Menu**: buka Mods → AutoMine Bot → tombol *Configure* → GUI yang sama.

GUI tab **Ore & Kontrol**: toggle ore (Coal, Iron, Copper, Gold, Redstone, Lapis, Diamond, Emerald, Quartz, Ancient Debris), Pilih semua/Kosongkan, tombol Mulai Mining / Mulai Tebang / STOP.
Tab **Pengaturan**: radius scan, jangkauan, batas HP, ESP, ESP selalu tampil, jalur, gali menembus blok, auto ganti tool, stop HP rendah / inventory penuh.

Config tersimpan di `config/automine.json`.

## Cara kerja
1. **Scan** blok sekitar (radius default 24) tiap 1 detik → daftar ore/log.
2. **ESP**: saat bot jalan, semua target digambar kotak berwarna tembus tembok; target aktif merah; jalur hijau.
3. **Pilih target** terdekat (kayu: batang bawah diutamakan), jalankan **A\*** (`Pathfinder`): jalan, lompat 1 blok, turun, jatuh maks 3, dan *menggali* blok penghalang (biaya lebih tinggi).
4. **Berjalan** lewat key forward/jump, menghadap node berikutnya, menggali blok yang menghalang, deteksi macet → hitung ulang jalur.
5. Dalam jangkauan → **ray-cast** ke target, hancurkan apa pun yang menghalangi, auto pilih tool terbaik.
6. Mode kayu: hanya boleh menggali log & daun.

Pengaman: tidak menggali blok di sebelah air/lava (lihat bagian *Hindari lava*), bedrock, block entity (chest dsb), blok sangat keras; menghindari lava/api/kaktus/magma/cobweb; stop jika HP rendah, terbakar, inventory penuh, atau tidak ada target.

## Hindari lava
Aktif default (toggle **Hindari lava** + slider **Jarak aman lava** 1-4 di tab Pengaturan, config `avoidLava` / `lavaMargin`).
- **Pathfinding**: sel yang menempel lava (termasuk diagonal, atas, bawah) dilarang; sel dalam jarak aman diberi biaya tambahan sehingga jalur menjauh dari lava kalau ada alternatif. Lava sumber dan lava mengalir sama-sama terdeteksi.
- **Saat jalan** (cek tiap 4 tick): jika lava muncul di 6 node jalur berikutnya (mis. mengalir setelah blok digali) jalur dihitung ulang. Jika lava menempel di pemain, bot berhenti menggali, menjauh lewat jalur baru, dan stop total bila masih dekat lava setelah 3 kali percobaan.
- Tetap berlaku: tidak menggali blok yang bersebelahan dengan cairan, dan stop saat terbakar / masuk lava.

## Fitur otomatis (tab "Otomatis")
- **Auto /repair**: tiap tick cek durability tool di hotbar. Jika sisa <= batas (default 12), bot memegang tool yang sekarat lalu mengirim `/repair` (sama seperti mengetik di chat), cek hasilnya 2 detik kemudian, cooldown default 10 dtk. Perintah bisa diganti (mis. `fix`). *Lindungi tool*: kalau sisa <= 1 bot diam menunggu cooldown, dan berhenti setelah 3x gagal supaya tool tidak pecah.
- **Jalan di background**: `pauseOnLostFocus` dimatikan selama bot jalan, layar pause tidak menghentikan bot. Dikembalikan saat STOP.
- **Inventory penuh tetap jalan** (default). Aktifkan *Stop jika inv penuh* kalau mau berhenti.
- **Lompat cerdas**: lompat otomatis jika ada blok setinggi 1 di depan dan ruang kepala bebas, atau saat menabrak; mengapung di air.
- **Panjat pohon tinggi** (mode tebang): kayu yang tak terjangkau (dark oak, jungle, spruce tinggi) dipanjat dengan *pillar jump* — lompat + pasang blok di bawah kaki, gali daun/batang yang menghalangi kepala, lanjut ke log berikutnya, lalu turun dengan menghancurkan pilar. **Taruh dirt/cobblestone di hotbar.**

## Cara menambang (mode Mine)
1. **Ore terlihat/terpindai** → bot mencari jalur biasa dulu (jalan, gua). Kalau gagal, bot memakai **mode terowongan**: menggali hampir lurus menuju ore (1x2, tangga turun/naik 1 blok), seperti penambang sungguhan. Tidak lagi dibatasi 8-10 blok.
2. **Tidak ada ore di radius** → **jelajah (strip mining)**: bot turun lewat tangga ke level Y terbaik ore yang dipilih (Diamond/Redstone -58, Gold -16, Lapis 0, Iron 16, Coal 45, ...), lalu menggali terowongan lurus sampai ore terdeteksi. Belok otomatis jika ada lava, air, atau blok yang tak bisa digali; berhenti hanya jika semua arah tidak aman.
   - **Anti-macet:** kalau bot tidak bergerak ±0,5 detik, ia menghancurkan blok apa pun yang menghalangi di depan (kaki, kepala, atas kepala), berapa pun jumlahnya.
   - **Gali langsung:** kalau jalur A* gagal/macet berulang, bot tidak menyerah: ia membuat jalan sendiri selangkah demi selangkah menuju ore (menggali semua blok penghalang, tangga naik/turun, menghindari lava/air), maksimum ±75 detik per ore.
3. **Ore hancur** → bot berjalan ke bekas ore untuk memungut drop.
4. **Nether / Ancient Debris:** netherrack, basalt, blackstone dll. bisa digali (semua blok ber-kekerasan <= 35 tanpa block entity). Ancient Debris butuh **pickaxe diamond/netherite di hotbar**; kalau tidak ada, debris dilewati dan bot memberi pesan. Pilih hanya *Ancient Debris* + berada di Nether, bot akan strip mine di Y=15 dan menghindari lava (lava lake Nether banyak, tetap awasi).
5. Tombol di tab *Otomatis*: Jelajah, Turun ke level ore, Ambil drop ore. Di Nether pilih ore Nether (Quartz/Ancient Debris), karena level Y Overworld tidak berlaku.

## Struktur kode (`src/main/java/com/automine`)
- `AutoMineClient` – entrypoint, keybind, tick
- `AutoBot` – state machine bot (scan, target, jalan, gali, pengaman)
- `Pathfinder` – A\*
- `LavaScan` – deteksi lava di sekitar posisi
- `EspRenderer` – ESP via custom RenderPipeline `NO_DEPTH_TEST`
- `AutoMineScreen` – GUI
- `AutoMineModMenu` – integrasi Mod Menu
- `AutoMineConfig`, `OreType`

## Titik rawan jika compile error (1.21.11)
API Minecraft berubah tiap versi. Cek bagian ini dulu:
- `Inventory#getSelectedSlot/setSelectedSlot` (AutoBot.selectTool)
- `ClipContext(Vec3, Vec3, Block, Fluid, Entity)` (AutoBot.mineBlock)
- `Level#getMinY/getMaxY` (AutoBot.scan)
- `KeyMapping#setDown`, `MultiPlayerGameMode#continueDestroyBlock`
- `AbstractSliderButton` / `Screen` / `GuiGraphics#drawCenteredString` (AutoMineScreen)
- Render: `WorldRenderEvents.BEFORE_TRANSLUCENT`, `context.worldState().cameraRenderState.pos`
  → bandingkan dengan https://docs.fabricmc.net/1.21.11/develop/rendering/world
- Versi Mod Menu (`modmenu_version`) – cek yang cocok di https://modrinth.com/mod/modmenu/versions
- Versi Fabric terbaru: https://fabricmc.net/develop

Tips: jika versi Mod Menu bermasalah, hapus baris `modLocalRuntime` di `build.gradle` (mod tetap jalan tanpa Mod Menu).

## Ide lanjutan (TODO)
- Tanam ulang sapling & ambil item drop (jalan ke ItemEntity)
- Mode *strip mining* / jelajah otomatis saat ore tidak ada di radius
- Auto makan, auto taruh obor, auto taruh blok menutup lava
- Outline garis ESP (pipeline lines), tracer, hitung ore per jenis di HUD
- Rotasi halus (smoothing) dan jeda acak
- Gerak air/berenang, pintu, tangga/vine
- Whitelist/blacklist area (jangan gali dekat base)
