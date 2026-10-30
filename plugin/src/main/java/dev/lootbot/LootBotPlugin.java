package dev.lootbot;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Loots known bases handed out by the LootBot learner and reports each visit back to it",
    url = "https://github.com/KingSoft69/LootBot",
    authors = {"LootBot"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class LootBotPlugin implements ZenithProxyPlugin {

    public static LootConfig CONFIG_DATA;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        CONFIG_DATA = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, LootConfig.class);
        pluginAPI.registerModule(new LootModule());
        pluginAPI.registerCommand(new LootCommand());
        LOG.info("LootBot {} loaded ({}); learner {}", BuildConstants.VERSION,
                CONFIG_DATA.enabled ? "on" : "off, 'loot on' to turn it on",
                CONFIG_DATA.learner.url.isBlank() ? "not set, 'loot learner <url> <token>'" : CONFIG_DATA.learner.url);
    }
}
