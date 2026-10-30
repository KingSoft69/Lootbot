package dev.lootbot;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.module.ServerPlayerInVisualRangeEvent;
import com.zenith.module.api.Module;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.PLAYER_LISTS;
import static dev.lootbot.LootBotPlugin.CONFIG_DATA;

/** Owns the loot job and ticks it while the proxy's bot has the controls. */
@SuppressWarnings("this-escape")
public class LootModule extends Module {

    private final LearnerClient learner = new LearnerClient(() -> CONFIG_DATA.learner,
            Path.of("plugins", "config", "lootbot-outbox.jsonl"), this::info);
    private final LootJob job = new LootJob(CONFIG_DATA, learner, this::info,
            cmd -> executeCommand(cmd, true), LootModule::botName, CONFIG_DATA.inventoryPriority);
    private final LiveView view = new LiveView(CONFIG_DATA, learner);

    @Override
    public boolean enabledSetting() {
        return CONFIG_DATA.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, e -> job.tick()),
            of(com.zenith.event.client.ClientTickEvent.class, e -> view.tick(botName(), job)),
            of(ClientOnlineEvent.class, e -> job.onOnline()),
            of(ClientDisconnectEvent.class, e -> job.onOffline()),
            of(ServerPlayerInVisualRangeEvent.class, this::onPlayerInVisualRange)
        );
    }

    @Override
    public void onEnable() {
        if (CONFIG_DATA.active) {
            info("LootBot: job was running before the restart; carrying on");
            job.requestStart();
        }
    }

    @Override
    public void onDisable() {
        if (job.phase() != LootJob.Phase.OFF) {
            boolean keep = CONFIG_DATA.active;
            job.requestStop();
            CONFIG_DATA.active = keep; // a module toggle is not the job being stopped
        }
    }

    private void onPlayerInVisualRange(ServerPlayerInVisualRangeEvent event) {
        if (event.playerEntity() != null && event.playerEntity().isSelfPlayer()) {
            return;
        }
        UUID id = event.playerEntry() == null ? null : event.playerEntry().getProfileId();
        if (id != null && (PLAYER_LISTS.getFriendsList().contains(id) || PLAYER_LISTS.getWhitelist().contains(id))) {
            return;
        }
        String name = event.playerEntry() == null || event.playerEntry().getName() == null ? "someone" : event.playerEntry().getName();
        job.onStranger(name);
    }

    static String botName() {
        var profile = CACHE.getProfileCache().getProfile();
        return profile != null && profile.getName() != null ? profile.getName() : CONFIG.authentication.username;
    }

    LootJob job() {
        return job;
    }
}
