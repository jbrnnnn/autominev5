package com.automine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AutoMineConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AutoMineConfig instance = new AutoMineConfig();

    public Set<String> ores = new LinkedHashSet<>(List.of("DIAMOND", "IRON"));
    public boolean esp = true;
    public boolean espAlways = false;
    public boolean showPath = true;
    public boolean tunneling = true;
    public boolean autoTool = true;
    public boolean stopLowHealth = true;
    public boolean stopInvFull = false;      // default: terus jalan walau inventory penuh
    public boolean autoRepair = true;        // ketik /repair saat durability tool hampir habis
    public String repairCommand = "repair";  // tanpa garis miring
    public int minDurability = 12;           // sisa durability yang memicu /repair
    public int repairCooldown = 200;         // tick antar percobaan /repair (200 = 10 detik)
    public boolean protectTool = true;       // stop jika tool nyaris pecah dan repair gagal
    public boolean background = true;        // tetap jalan saat window tidak fokus / di server
    public boolean explore = true;           // tidak ada ore terlihat -> gali terowongan lurus (strip mining)
    public boolean autoDepth = true;         // turun/naik ke level Y terbaik ore yang dipilih
    public boolean collectDrops = true;      // jalan ke bekas ore untuk memungut drop
    public boolean pillar = true;            // pasang blok ke atas untuk menjangkau kayu tinggi
    public boolean avoidLava = true;
    public int lavaMargin = 2;
    public int minHealth = 6;
    public int scanRadius = 24;
    public double reach = 4.2;
    public int maxNodes = 5000;

    public static AutoMineConfig get() { return instance; }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.json");
    }

    public static void load() {
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                AutoMineConfig c = GSON.fromJson(r, AutoMineConfig.class);
                if (c != null) {
                    if (c.ores == null) c.ores = new LinkedHashSet<>();
                    c.scanRadius = Math.max(8, Math.min(48, c.scanRadius));
                    c.reach = Math.max(3.0, Math.min(4.5, c.reach));
                    c.minHealth = Math.max(1, Math.min(19, c.minHealth));
                    c.lavaMargin = Math.max(1, Math.min(4, c.lavaMargin));
                    c.maxNodes = Math.max(500, Math.min(20000, c.maxNodes));
                    c.minDurability = Math.max(2, Math.min(200, c.minDurability));
                    c.repairCooldown = Math.max(40, Math.min(1200, c.repairCooldown));
                    if (c.repairCommand == null || c.repairCommand.isBlank()) c.repairCommand = "repair";
                    if (c.repairCommand.startsWith("/")) c.repairCommand = c.repairCommand.substring(1);
                    instance = c;
                }
            } catch (Exception e) {
                AutoMineClient.LOGGER.warn("Gagal baca config, pakai default", e);
            }
        } else {
            save();
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(instance));
        } catch (Exception e) {
            AutoMineClient.LOGGER.warn("Gagal simpan config", e);
        }
    }

    public boolean isOreEnabled(OreType t) { return ores.contains(t.name()); }

    public void toggleOre(OreType t) {
        if (!ores.remove(t.name())) ores.add(t.name());
    }
}
