package software.bernie.geckolib3;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import software.bernie.example.config.ConfigHandler;

/**
 * GeckoLib 3 entry point for Minecraft 1.7.10.
 * <p>
 * This mod ships the animation engine only; it registers no blocks, items or renderers of its own. Mods that want
 * animations depend on it and use the {@code software.bernie.geckolib3} API directly.
 */
@Mod(
    modid = GeckoLib.MOD_ID,
    name = GeckoLib.MOD_NAME,
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]")
public class GeckoLib {

    public static final String MOD_ID = "geckolib";
    public static final String MOD_NAME = "GeckoLib";
    public static final Logger LOG = LogManager.getLogger(MOD_ID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ConfigHandler.init(new File(event.getModConfigurationDirectory(), "geckolib.cfg"));
        LOG.info("GeckoLib {} initialized", Tags.VERSION);
    }
}
