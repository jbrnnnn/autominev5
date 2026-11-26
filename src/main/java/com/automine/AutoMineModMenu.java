package com.automine;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Membuat tombol "Configure" di Mod Menu membuka GUI AutoMine. */
public class AutoMineModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new AutoMineScreen(parent);
    }
}
