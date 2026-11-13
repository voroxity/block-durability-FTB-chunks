package top.dreamfall.blockdurability;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(BlockDurability.MOD_ID)
public class BlockDurability {
    public static final String MOD_ID = "blockdurability";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Register game rule: /gamerule blockDurabilityHealSeconds <seconds>
    public static final GameRules.Key<GameRules.IntegerValue> RULE_BLOCK_HEAL_SECONDS =
            GameRules.register("blockDurabilityHealSeconds", GameRules.Category.MISC, GameRules.IntegerValue.create(120));

    public BlockDurability(IEventBus modEventBus, ModContainer container) {
        // Register config
        container.registerConfig(ModConfig.Type.COMMON, BlockHPConfig.SPEC);

        // Listen for config load/reload so the block list and HP overrides take effect immediately after a hot reload
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == BlockHPConfig.SPEC) {
                BlockHPManager.invalidateCache();
            }
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == BlockHPConfig.SPEC) {
                BlockHPManager.invalidateCache();
            }
        });

        // Register the core event listeners (explosions, healing and chunk lifecycle; works without TaCZ)
        NeoForge.EVENT_BUS.register(new BlockHPEventHandler());

        // Safely register the TACZ bullet-hit listener (silently skipped if TACZ is not installed)
        try {
            Class.forName("com.tacz.guns.api.event.server.AmmoHitBlockEvent");
            NeoForge.EVENT_BUS.register(new TaczHitHandler());
            LOGGER.info("TACZ detected - Block Durability bullet hit handler registered!");
        } catch (ClassNotFoundException e) {
            LOGGER.info("TACZ not found - Block Durability will run in explosion-only mode until TACZ is installed.");
        }

        // Optional FTB Chunks integration: claimed/unclaimed/team-specific durability settings.
        // FtbChunksCompat is the only class that touches FTB types, so it is only loaded here.
        if (ModList.get().isLoaded("ftbchunks")) {
            try {
                FtbChunksCompat.init();
                LOGGER.info("FTB Chunks detected - claimed/unclaimed land settings enabled.");
            } catch (LinkageError | RuntimeException e) {
                LOGGER.warn("FTB Chunks is installed but its API could not be used; all land will be treated as unclaimed. {}", e.toString());
            }
        } else {
            LOGGER.info("FTB Chunks not found - all land is treated as unclaimed.");
        }

        // Write any newly added config options that are missing from the config file
        updateConfigFile();
    }

    /**
     * Corrects the config file against the current spec, adding any new options.
     */
    private static void updateConfigFile() {
        try {
            Path path = Path.of("config", MOD_ID + "-common.toml");
            CommentedFileConfig cfg = CommentedFileConfig.builder(path).build();
            cfg.load();
            BlockHPConfig.SPEC.correct(cfg);
            cfg.save();
            cfg.close();
            LOGGER.info("Config file updated with new options.");
        } catch (Exception e) {
            LOGGER.debug("Config update skipped: {}", e.toString());
        }
    }
}
