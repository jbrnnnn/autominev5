package com.automine;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** GUI: pilih ore, mulai/stop bot, atur opsi. Bisa dibuka lewat RShift atau Mod Menu. */
public class AutoMineScreen extends Screen {
    private final Screen parent;
    private int page = 0;

    public AutoMineScreen(Screen parent) {
        super(Component.literal("AutoMine Bot"));
        this.parent = parent;
    }

    private static final String[] TABS = {"Ore & Kontrol", "Pengaturan", "Otomatis"};
    private static final int PW = 340, ACCENT = 0xFF37E6B0;

    @Override
    protected void init() {
        int cx = this.width / 2;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            String label = page == i ? "[ " + TABS[i] + " ]" : TABS[i];
            addRenderableWidget(Button.builder(Component.literal(label),
                    b -> { page = idx; rebuildWidgets(); }).bounds(cx - PW / 2 + 6 + i * 109, 30, 106, 20).build());
        }
        if (page == 0) initMain(cx, 60); else if (page == 1) initSettings(cx, 60); else initAuto(cx, 60);

        addRenderableWidget(Button.builder(Component.literal("Selesai"), b -> onClose())
                .bounds(cx - 100, this.height - 26, 200, 20).build());
    }

    private void initMain(int cx, int top) {
        AutoMineConfig cfg = AutoMineConfig.get();
        OreType[] types = OreType.values();
        for (int i = 0; i < types.length; i++) {
            OreType t = types[i];
            boolean on = cfg.isOreEnabled(t);
            MutableComponent label = Component.literal(on ? "ON  " : "OFF ")
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .append(Component.literal(t.label).withStyle(s -> s.withColor(t.color)));
            int x = cx - 155 + (i % 2) * 160;
            int y = top + (i / 2) * 22;
            addRenderableWidget(Button.builder(label, b -> {
                cfg.toggleOre(t);
                AutoMineConfig.save();
                AutoBot.rebuildWanted();
                rebuildWidgets();
            }).bounds(x, y, 150, 20).build());
        }

        int y2 = top + 5 * 22 + 6;
        addRenderableWidget(Button.builder(Component.literal("Pilih semua"), b -> {
            for (OreType t : types) cfg.ores.add(t.name());
            AutoMineConfig.save(); AutoBot.rebuildWanted(); rebuildWidgets();
        }).bounds(cx - 155, y2, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Kosongkan"), b -> {
            cfg.ores.clear();
            AutoMineConfig.save(); AutoBot.rebuildWanted(); rebuildWidgets();
        }).bounds(cx + 5, y2, 150, 20).build());

        int y3 = y2 + 26;
        boolean mining = AutoBot.mode == AutoBot.Mode.MINE;
        boolean wood = AutoBot.mode == AutoBot.Mode.WOOD;
        addRenderableWidget(Button.builder(Component.literal(mining ? "Mining: JALAN" : "Mulai Mining"), b -> {
            AutoBot.start(AutoBot.Mode.MINE); onClose();
        }).bounds(cx - 155, y3, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal(wood ? "Tebang: JALAN" : "Mulai Tebang"), b -> {
            AutoBot.start(AutoBot.Mode.WOOD); onClose();
        }).bounds(cx - 50, y3, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("STOP").withStyle(ChatFormatting.RED), b -> {
            AutoBot.stop("Dihentikan dari menu"); rebuildWidgets();
        }).bounds(cx + 55, y3, 100, 20).build());
    }

    private void initSettings(int cx, int top) {
        AutoMineConfig cfg = AutoMineConfig.get();
        int i = 0;
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Radius scan", 8, 48,
                cfg.scanRadius, v -> cfg.scanRadius = v, v -> String.valueOf(v)));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Jangkauan", 30, 45,
                (int) Math.round(cfg.reach * 10), v -> cfg.reach = v / 10.0, v -> String.format("%.1f", v / 10.0)));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Stop jika HP <=", 1, 19,
                cfg.minHealth, v -> cfg.minHealth = v, v -> String.valueOf(v)));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Block ESP", cfg.esp, () -> cfg.esp = !cfg.esp));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "ESP selalu tampil", cfg.espAlways, () -> cfg.espAlways = !cfg.espAlways));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Tampilkan jalur", cfg.showPath, () -> cfg.showPath = !cfg.showPath));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Gali menembus blok", cfg.tunneling, () -> cfg.tunneling = !cfg.tunneling));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Auto ganti tool", cfg.autoTool, () -> cfg.autoTool = !cfg.autoTool));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Stop jika HP rendah", cfg.stopLowHealth, () -> cfg.stopLowHealth = !cfg.stopLowHealth));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Hindari lava", cfg.avoidLava, () -> cfg.avoidLava = !cfg.avoidLava));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Jarak aman lava", 1, 4,
                cfg.lavaMargin, v -> cfg.lavaMargin = v, v -> String.valueOf(v)));
    }

    private void initAuto(int cx, int top) {
        AutoMineConfig cfg = AutoMineConfig.get();
        int i = 0;
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Auto /repair", cfg.autoRepair, () -> cfg.autoRepair = !cfg.autoRepair));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Lindungi tool", cfg.protectTool, () -> cfg.protectTool = !cfg.protectTool));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Repair saat sisa <=", 2, 200,
                cfg.minDurability, v -> cfg.minDurability = v, v -> String.valueOf(v)));
        addRenderableWidget(new IntSlider(slotX(cx, i), slotY(top, i++), 150, 20, "Cooldown repair (dtk)", 2, 60,
                cfg.repairCooldown / 20, v -> cfg.repairCooldown = v * 20, v -> String.valueOf(v)));
        EditBox cmd = new EditBox(this.font, slotX(cx, i), slotY(top, i++), 150, 20, Component.literal("Perintah repair"));
        cmd.setMaxLength(32);
        cmd.setValue(cfg.repairCommand);
        cmd.setResponder(v -> {
            String c = v.trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (!c.isEmpty()) cfg.repairCommand = c;
        });
        addRenderableWidget(cmd);
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Jalan di background", cfg.background, () -> cfg.background = !cfg.background));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Jelajah (strip mine)", cfg.explore, () -> cfg.explore = !cfg.explore));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Turun ke level ore", cfg.autoDepth, () -> cfg.autoDepth = !cfg.autoDepth));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Ambil drop ore", cfg.collectDrops, () -> cfg.collectDrops = !cfg.collectDrops));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Panjat pohon tinggi", cfg.pillar, () -> cfg.pillar = !cfg.pillar));
        addRenderableWidget(toggle(slotX(cx, i), slotY(top, i++), "Stop jika inv penuh", cfg.stopInvFull, () -> cfg.stopInvFull = !cfg.stopInvFull));
    }

    private static int slotX(int cx, int i) { return cx - 155 + (i % 2) * 160; }
    private static int slotY(int top, int i) { return top + (i / 2) * 24; }

    private Button toggle(int x, int y, String name, boolean on, Runnable flip) {
        MutableComponent label = Component.literal(name + ": ")
                .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        return Button.builder(label, b -> {
            flip.run();
            AutoMineConfig.save();
            rebuildWidgets();
        }).bounds(x, y, 150, 20).build();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        int x0 = this.width / 2 - PW / 2, x1 = x0 + PW;
        int y0 = 6, y1 = this.height - 32;
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF0B1F1A);          // bingkai
        g.fillGradient(x0, y0, x1, y1, 0xEE12262F, 0xEE0A1015);       // panel
        g.fill(x0, y0, x1, y0 + 2, ACCENT);                           // garis aksen
        g.fillGradient(x0, y0 + 2, x1, y0 + 24, 0x5537E6B0, 0x0037E6B0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        g.drawString(this.font, Component.literal("AutoMine Bot").withStyle(ChatFormatting.BOLD), cx - PW / 2 + 10, 12, ACCENT);

        if (page == 0) { // kotak warna ore di kiri tombol
            OreType[] types = OreType.values();
            for (int i = 0; i < types.length; i++) {
                int x = cx - 155 + (i % 2) * 160, y = 60 + (i / 2) * 22;
                g.fill(x + 3, y + 5, x + 13, y + 15, 0xFF000000);
                g.fill(x + 4, y + 6, x + 12, y + 14, 0xFF000000 | types[i].color);
            }
        }

        boolean on = AutoBot.mode != AutoBot.Mode.OFF;
        String status = switch (AutoBot.mode) {
            case OFF -> "BERHENTI";
            case MINE -> "AUTO MINE BERJALAN";
            case WOOD -> "AUTO TEBANG BERJALAN";
        };
        int w = this.font.width(status) + 22, sy = 9;
        int sx = cx + PW / 2 - w - 8;
        g.fill(sx, sy, sx + w, sy + 14, on ? 0xCC0F5A3F : 0xCC5A1F1F);
        g.fill(sx + 5, sy + 5, sx + 9, sy + 9, on ? 0xFF37E6B0 : 0xFFFF5555);
        g.drawString(this.font, Component.literal(status), sx + 14, sy + 3, 0xFFFFFFFF);
    }

    @Override
    public void removed() {
        AutoMineConfig.save();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final IntConsumer set;
        private final IntFunction<String> fmt;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int value,
                  IntConsumer set, IntFunction<String> fmt) {
            super(x, y, w, h, Component.empty(), (double) (value - min) / (max - min));
            this.label = label; this.min = min; this.max = max; this.set = set; this.fmt = fmt;
            updateMessage();
        }

        private int cur() { return min + (int) Math.round(this.value * (max - min)); }

        @Override
        protected void updateMessage() {
            if (fmt == null) return;
            setMessage(Component.literal(label + ": " + fmt.apply(cur())));
        }

        @Override
        protected void applyValue() { set.accept(cur()); }
    }
}
