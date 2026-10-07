package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.access.AccessRepository;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le temps des joueurs sur ce monde ressource. À l'arrivée, il faut du temps restant et un monde prêt, sinon le joueur
 * est expulsé (EterVelocityLobby le renvoie au lobby avec la raison). Le temps s'écoule chaque seconde ; ce qui a été
 * consommé part en base toutes les 30 s et au départ, et le temps est relu au passage (un achat fait ici compte tout de
 * suite). Rappels dans le chat, compte à rebours dans l'action bar les 5 dernières minutes. Le staff qui a
 * eterresource.bypass.time entre sans temps et n'en consomme pas.
 */
public class Sessions implements Listener {

    public static final String BYPASS = "eterresource.bypass.time";

    private static final long SAVE_TICKS = 30 * 20;
    private static final long COUNTDOWN = Duration.ofMinutes(5).toMillis();
    /** Rappels dans le chat quand il reste ce temps. */
    private static final List<Long> REMINDERS = List.of(Duration.ofMinutes(15).toMillis(), Duration.ofMinutes(5).toMillis(),
            Duration.ofMinutes(1).toMillis());

    /** Thread principal seulement. */
    private static final class Session {
        long remaining;
        long unsaved;
        long lastTick = System.currentTimeMillis();

        Session(long remaining) {
            this.remaining = remaining;
        }
    }

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Messages messages;
    private final AccessRepository access;
    private final WorldSetup setup;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public Sessions(JavaPlugin plugin, EterLib lib, Messages messages, AccessRepository access, WorldSetup setup) {
        this.plugin = plugin;
        this.lib = lib;
        this.messages = messages;
        this.access = access;
        this.setup = setup;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> List.copyOf(sessions.keySet()).forEach(this::sync), SAVE_TICKS, SAVE_TICKS);
    }

    /** Arrêt du serveur : le temps consommé part en base tout de suite (bloquant). */
    public void stop() {
        sessions.forEach((uuid, session) -> {
            try {
                access.removeTime(uuid, session.unsaved);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Temps de " + uuid + " non enregistré : " + e.getMessage());
            }
        });
        sessions.clear();
    }

    /** Le temps du joueur a changé (achat, clé, staff) : relu tout de suite. */
    public void reload(Player player) {
        sync(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        if (!setup.isReady()) {
            player.kick(messages.get(player, "world.not-ready"));
            return;
        }
        Tasks.async(plugin, player, () -> access.get(player.getUniqueId()), found -> {
            if (found.remainingMillis() <= 0) {
                player.kick(messages.get(player, "world.no-time"));
                return;
            }
            sessions.put(player.getUniqueId(), new Session(found.remainingMillis()));
            messages.send(player, "world.welcome", "time", time(player, found.remainingMillis()));
        }, () -> player.kick(messages.get(player, "error.generic")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null && session.unsaved > 0) {
            UUID uuid = event.getPlayer().getUniqueId();
            long used = session.unsaved;
            Tasks.async(plugin, () -> access.removeTime(uuid, used), "Temps de monde ressource non enregistré pour " + uuid);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null) {
                continue;
            }
            long elapsed = now - session.lastTick;
            long before = session.remaining;
            session.lastTick = now;
            session.remaining -= elapsed;
            session.unsaved += elapsed;
            if (session.remaining <= 0) {
                // Le départ (onQuit) enregistre le temps consommé
                player.kick(messages.get(player, "world.time-over"));
                continue;
            }
            for (long reminder : REMINDERS) {
                if (before > reminder && session.remaining <= reminder) {
                    messages.send(player, "world.reminder", "time", time(player, reminder));
                    player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1f);
                }
            }
            if (session.remaining <= COUNTDOWN) {
                messages.actionBar(player, "world.countdown", "time", time(player, session.remaining));
            }
        }
    }

    /** Envoie le temps consommé, puis relit le temps restant (moins ce qui a été consommé entre-temps). */
    private void sync(UUID uuid) {
        Session session = sessions.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (session == null || player == null) {
            return;
        }
        long used = session.unsaved;
        session.unsaved = 0;
        Tasks.async(plugin, player, () -> {
            access.removeTime(uuid, used);
            return access.get(uuid);
        }, (Access found) -> {
            Session current = sessions.get(uuid);
            if (current == session) {
                session.remaining = found.remainingMillis() - session.unsaved;
            }
        }, () -> {
            if (sessions.get(uuid) == session) {
                session.unsaved += used; // base injoignable : renvoyé la prochaine fois
            }
        });
    }

    private String time(Player player, long millis) {
        return lib.formatDuration(player, Math.max(0, millis) / 1000);
    }
}
