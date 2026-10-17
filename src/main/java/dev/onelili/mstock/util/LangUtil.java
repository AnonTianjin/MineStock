package dev.onelili.mstock.util;

import dev.onelili.mstock.MineStock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LangUtil {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private FileConfiguration lang;
    private String prefix;

    public LangUtil(MineStock plugin) {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (file.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                YamlConfiguration test = new YamlConfiguration();
                test.loadFromString(content);
                lang = test;
            } catch (InvalidConfigurationException e) {
                plugin.getLogger().warning("[MineStock] lang.yml 语法错误，用默认文件覆盖: " + e.getMessage());
                plugin.saveResource("lang.yml", true);
                lang = YamlConfiguration.loadConfiguration(file);
            } catch (Exception e) {
                plugin.getLogger().warning("[MineStock] lang.yml 读取失败，用默认文件覆盖: " + e.getMessage());
                plugin.saveResource("lang.yml", true);
                lang = YamlConfiguration.loadConfiguration(file);
            }
        } else {
            plugin.saveResource("lang.yml", false);
            lang = YamlConfiguration.loadConfiguration(file);
        }
        InputStream defaultStream = plugin.getResource("lang.yml");
        if (defaultStream != null) {
            lang.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
        }
        // 同样显式回落：旧数据目录里的 lang.yml 可能没有 prefix 键
        String configuredPrefix = lang.getString("prefix");
        if (configuredPrefix == null) {
            Configuration defaults = lang.getDefaults();
            configuredPrefix = defaults != null ? defaults.getString("prefix") : null;
        }
        prefix = configuredPrefix != null ? configuredPrefix : "<gold>[MineStock]</gold> ";
    }

    /**
     * 读取语言键，缺失时回落到 jar 内置的 lang.yml。
     *
     * <p><b>为什么不能直接用双参重载</b>：Bukkit 的
     * {@code getString(key, def)} 与 {@code getString(key)} 行为不同 ——
     * 单参版本走 {@code get()}，会回落到 {@code setDefaults()} 设定的默认值；
     * 而<b>双参版本</b>直接用 {@code def} 兜底，<b>不会</b>查 defaults（实测确认）。
     *
     * <p>原实现用的正是双参版本，因此当服务端数据目录里存在<b>旧版</b>
     * {@code lang.yml}（首次启用时保存的副本，不含后续新增键）时，所有新增键
     * 都会落到那个 {@code <red>Missing lang key: xxx</red>} 占位符，
     * 在聊天框刷屏 —— 线上已实际发生。
     */
    public String getRaw(String key) {
        // 单参版本：本地没有时会自动回落到 defaults
        String value = lang.getString(key);
        if (value != null) return value;
        // 双保险：显式再查一次 defaults（便于测试与无 defaults 环境的可控行为）
        Configuration defaults = lang.getDefaults();
        if (defaults != null) {
            String fallback = defaults.getString(key);
            if (fallback != null) return fallback;
        }
        return "<red>Missing lang key: " + key + "</red>";
    }

    /** 获取带前缀的消息，{key} 占位符会被替换 */
    public Component get(String key, String... kvPairs) {
        String raw = prefix + replacePairs(getRaw(key), kvPairs);
        return MM.deserialize(raw);
    }

    /** 获取不带前缀的消息 */
    public Component getNoPrefix(String key, String... kvPairs) {
        String raw = replacePairs(getRaw(key), kvPairs);
        return MM.deserialize(raw);
    }

    /** 发送带前缀的消息 */
    public void send(Player player, String key, String... kvPairs) {
        player.sendMessage(get(key, kvPairs));
    }

    /** 发送不带前缀的消息 */
    public void sendNoPrefix(Player player, String key, String... kvPairs) {
        player.sendMessage(getNoPrefix(key, kvPairs));
    }

    private String replacePairs(String raw, String[] kvPairs) {
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            raw = raw.replace("{" + kvPairs[i] + "}", kvPairs[i + 1]);
        }
        return raw;
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }
}
