package com.automine;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoMineClient implements ClientModInitializer {
    public static final String MOD_ID = "automine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyMapping openGui, toggleMine, toggleWood;

    @Override
    public void onInitializeClient() {
        AutoMineConfig.load();
        AutoBot.rebuildWanted();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automine.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, cat));
        toggleMine = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automine.toggle_mine", InputConstants.Type.KEYSYM, InputConstants.KEY_G, cat));
        toggleWood = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.automine.toggle_wood", InputConstants.Type.KEYSYM, InputConstants.KEY_H, cat));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGui.consumeClick()) client.setScreen(new AutoMineScreen(null));
            while (toggleMine.consumeClick()) AutoBot.toggle(AutoBot.Mode.MINE);
            while (toggleWood.consumeClick()) AutoBot.toggle(AutoBot.Mode.WOOD);
            AutoBot.tick(client);
        });

        EspRenderer.init();
        LOGGER.info("AutoMine Bot siap (RShift = menu, G = mine, H = tebang kayu)");
    }
}
