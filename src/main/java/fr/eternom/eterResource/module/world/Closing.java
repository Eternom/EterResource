package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterResource.module.access.AccessService;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * La fin d'un monde ressource (close_at, fixée par la durée de vie de son thème ; EterVelocityResource le vide à cette
 * heure) : annonces à 1 h, 30, 10, 5 et 1 min, barre de boss la dernière demi-heure, et la « tempête finale » : la
 * bordure se resserre doucement vers le camp. À l'heure : le monde n'est plus proposé, chaque joueur part sur un autre
 * monde ressource ouvert (son temps continue), sinon au lobby. Ce qui reste au sol et dans les coffres est perdu : c'est
 * dit dès l'arrivée et à chaque annonce.
 */
public class Closing {

    private static final List<Long> ANNOUNCES = List.of(60L, 30L, 10L, 5L, 1L);
    private static final long STORM_MINUTES = 30;

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Messages messages;
    private final WorldSetup setup;
    private final AccessService access;
    private final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    private long lastMinutes = Long.MAX_VALUE;
    private boolean storm;
    private boolean closed;

    public Closing(JavaPlugin plugin, EterLib lib, Messages messages, WorldSetup setup, AccessService access) {
        this.plugin = plugin;
        this.lib = lib;
        this.messages = messages;
        this.setup = setup;
        this.access = access;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 20);
    }

    private void tick() {
        if (closed) {
            return;
        }
        long left = setup.closeAt() - System.currentTimeMillis();
        long minutes = Math.max(0, (left + 59_999) / 60_000);
        if (minutes != lastMinutes) {
            if (ANNOUNCES.contains(minutes)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    messages.send(player, "closing.announce", "time", lib.formatDuration(player, minutes * 60));
                    player.playSound(player, Sound.BLOCK_BELL_USE, 0.8f, minutes <= 5 ? 1.2f : 0.8f);
                }
            }
            lastMinutes = minutes;
        }
        if (left <= Duration.ofMinutes(STORM_MINUTES).toMillis()) {
            if (!storm) {
                startStorm(left);
            }
            float progress = Math.clamp(left / (float) Duration.ofMinutes(STORM_MINUTES).toMillis(), 0f, 1f);
            bar.progress(progress);
            for (Player player : Bukkit.getOnlinePlayers()) {
                bar.name(messages.get(player, "closing.bar", "time", lib.formatDuration(player, Math.max(0, left) / 1000)));
                player.showBossBar(bar);
            }
        }
        if (left <= 0) {
            close();
        }
    }

    /** La tempête finale : la bordure de la surface se resserre vers le camp jusqu'à la fermeture. */
    private void startStorm(long left) {
        storm = true;
        World world = setup.camp().center() == null ? null : setup.camp().center().getWorld();
        if (world != null) {
            double target = Math.max(64, setup.camp().radius() * 4.0);
            world.getWorldBorder().setCenter(setup.camp().center());
            world.getWorldBorder().changeSize(target, Math.max(1, left / 50));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            messages.send(player, "closing.storm");
            player.playSound(player, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 0.8f);
        }
    }

    /** Fermeture : plus proposé ; chacun part sur un autre monde ouvert, sinon au lobby. */
    private void close() {
        closed = true;
        setup.markClosing();
        Optional<String> other = access.best().filter(name -> !name.equals(lib.getServerName()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
            if (other.isPresent()) {
                messages.send(player, "closing.moved", "server", lib.getServerDisplayName(other.get()));
                lib.getTeleports().connect(player, other.get());
            } else {
                player.kick(messages.get(player, "closing.kick"));
            }
        }
        plugin.getLogger().info("Monde ressource fermé (durée de vie du thème " + setup.theme().id() + " atteinte)");
    }

    /** Une nouvelle arrivée pendant la tempête voit la barre tout de suite. */
    public void show(Player player) {
        if (storm && !closed) {
            player.showBossBar(bar);
        }
    }
}
