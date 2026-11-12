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

    // 注册游戏规则：/gamerule blockDurabilityHealSeconds <秒数>
    public static final GameRules.Key<GameRules.IntegerValue> RULE_BLOCK_HEAL_SECONDS =
            GameRules.register("blockDurabilityHealSeconds", GameRules.Category.MISC, GameRules.IntegerValue.create(120));

    public BlockDurability(IEventBus modEventBus, ModContainer container) {
        // 注册配置
        container.registerConfig(ModConfig.Type.COMMON, BlockHPConfig.SPEC);

        // 监听配置加载与重载事件，确保热重载后白名单和血量覆盖即时生效
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

        // 注册核心事件监听器（爆炸、自愈与区块生命周期，无 TaCZ 时亦可独立运行）
        NeoForge.EVENT_BUS.register(new BlockHPEventHandler());

        // 安全注册 TACZ 子弹直击事件监听（TACZ 未安装时静默跳过）
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

        // 补写配置文件中缺失的新增配置项
        updateConfigFile();
    }

    /**
     * 用当前 spec 修正配置文件，把新增的配置项补进去。
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
