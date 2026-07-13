package dev.hylfrd.farmhelper.platform;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric main entry point and owner of mod-level platform metadata. */
public final class FarmHelper implements ModInitializer {
    public static final String MOD_ID = "farmhelper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("FarmHelper core initialized.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
