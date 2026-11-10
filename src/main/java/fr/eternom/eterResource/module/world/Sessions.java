package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sidebar.SidebarOverrides;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.access.AccessRepository;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Le temps des joueurs sur ce monde ressource. À l'arrivée, il faut du temps et un monde prêt, sinon le joueur est
 * expulsé (EterVelocityLobby le renvoie au lobby avec la raison). Le temps s'écoule chaque seconde, SAUF au camp de base
 * et pendant l'inactivité (AFK) ; il compte plus vite dans le Nether et l'End (time.dimension-multiplier). Le consommé
 * part en base toutes les 30 s et au départ, et le temps est relu au passage (un achat fait ici compte tout de suite).
 * Rappels dans le chat, compte à rebours dans l'action bar les 5 dernières minutes, sidebar (thème, temps, fermeture du
 * monde, événement), arrivée animée, récapitulatif de sortie (gardé en base, montré sur le serveur suivant), et la
 * session notée en base (si le monde plante, le temps perdu est rendu à l'arrivée suivante). Le staff qui a
 * eterresource.bypass.time entre sans temps et n'en consomme pas.
 */
public class Sessions implements Listener {

    public static final String BYPASS = "eterresource.bypass.time";
    private static final String SIDEBAR = "EterResource";

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
        final long joinedAt = System.currentTimeMillis();
        long spent;
        int blocks;
        int ores;
        int logs;
        int mobs;
        boolean paused;

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
    private final Map<World.Environment, Double> multipliers = new HashMap<>();
    /** Ligne de l'événement en cours pour la sidebar (module des événements), null : aucun. */
    private Function<Player, Component> eventLine = player -> null;

    public Sessions(JavaPlugin plugin, EterLib lib, Messages messages, AccessRepository access, WorldSetup setup) {
        this.plugin = plugin;
        this.lib = lib;
        this.messages = messages;
        this.access = access;
        this.setup = setup;
        multipliers.put(World.Environment.NETHER, Math.max(0, plugin.getConfig().getDouble("time.dimension-multiplier.nether", 1.5)));
        multipliers.put(World.Environment.THE_END, Math.max(0, plugin.getConfig().getDouble("time.dimension-multiplier.the_end", 2)));
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> List.copyOf(sessions.keySet()).forEach(this::sync), SAVE_TICKS, SAVE_TICKS);
    }

    public void setEventLine(Function<Player, Component> line) {
        this.eventLine = line == null ? player -> null : line;
    }

    /** Arrêt du serveur : le temps consommé part en base tout de suite (bloquant), la session est refermée. */
    public void stop() {
        sessions.forEach((uuid, session) -> {
            try {
                access.removeTime(uuid, session.unsaved);
                access.log(uuid, -session.spent, "spent:" + lib.getServerName());
                access.setSession(uuid, null);
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

    /** Temps restant d'un joueur connecté ici (ms), -1 sans session. */
    public long remaining(UUID player) {
        Session session = sessions.get(player);
        return session == null ? -1 : session.remaining;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        setup.joined();
        if (player.hasPermission(BYPASS)) {
            welcome(player);
            return;
        }
        if (!setup.isReady()) {
            player.kick(messages.get(player, "world.not-ready"));
            return;
        }
        String server = lib.getServerName();
        Tasks.async(plugin, player, () -> {
            Access found = access.get(player.getUniqueId());
            if (found.remainingMillis() > 0) {
                access.setSession(player.getUniqueId(), server);
            }
            return found;
        }, found -> {
            if (found.remainingMillis() <= 0) {
                player.kick(messages.get(player, "world.no-time"));
                return;
            }
            sessions.put(player.getUniqueId(), new Session(found.remainingMillis()));
            messages.send(player, "world.welcome", "time", time(player, found.remainingMillis()));
            welcome(player);
        }, () -> player.kick(messages.get(player, "error.generic")));
    }

    /** L'arrivée : titre du thème, son, particules, sidebar. */
    private void welcome(Player player) {
        String theme = setup.theme().id();
        player.showTitle(Title.title(messages.get(player, "theme." + theme + ".name"), messages.get(player, "theme." + theme + ".subtitle"),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(800))));
        player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 0.8f);
        player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1f);
        player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0);
        lib.getSidebars().show(player, SIDEBAR, this::sidebar);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lib.getSidebars().clear(uuid, SIDEBAR);
        Session session = sessions.remove(uuid);
        if (session == null) {
            return;
        }
        long used = session.unsaved;
        long spent = session.spent;
        String recap = recap(session);
        String server = lib.getServerName();
        Tasks.async(plugin, () -> {
            access.removeTime(uuid, used);
            access.log(uuid, -spent, "spent:" + server);
            access.setSession(uuid, null);
            access.saveRecap(uuid, recap);
        }, "Temps de monde ressource non enregistré pour " + uuid);
    }

    /** « secondes;blocs;minerais;bûches;créatures;thème » (AccessEvents le montre sur le serveur suivant). */
    private String recap(Session session) {
        long seconds = (System.currentTimeMillis() - session.joinedAt) / 1000;
        return seconds + ";" + session.blocks + ";" + session.ores + ";" + session.logs + ";" + session.mobs + ";" + setup.theme().id();
    }

    // ---------- Récapitulatif : ce qui est récolté ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        session.blocks++;
        Material type = event.getBlock().getType();
        if (type.name().endsWith("_ORE") || type == Material.ANCIENT_DEBRIS) {
            session.ores++;
        } else if (Tag.LOGS.isTagged(type)) {
            session.logs++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        Session session = killer == null ? null : sessions.get(killer.getUniqueId());
        if (session != null && event.getEntity() instanceof Monster) {
            session.mobs++;
        }
    }

    // ---------- Chaque seconde ----------

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null) {
                continue;
            }
            long elapsed = now - session.lastTick;
            session.lastTick = now;
            boolean inCamp = setup.camp().contains(player.getLocation());
            boolean afk = lib.getActivity().isAfk(player);
            boolean paused = inCamp || afk;
            if (paused != session.paused) {
                session.paused = paused;
                messages.actionBar(player, paused ? (inCamp ? "world.paused-camp" : "world.paused-afk") : "world.resumed");
            }
            if (paused) {
                continue;
            }
            long cost = Math.round(elapsed * multipliers.getOrDefault(player.getWorld().getEnvironment(), 1.0));
            long before = session.remaining;
            session.remaining -= cost;
            session.unsaved += cost;
            session.spent += cost;
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

    /** La sidebar du monde ressource (EterTab la redemande à chaque rafraîchissement). */
    private SidebarOverrides.Content sidebar(Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        String theme = setup.theme().id();
        lines.add(messages.get(player, "sidebar.theme", "theme", messages.plain(player, "theme." + theme + ".name")));
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            lines.add(messages.get(player, session.paused ? "sidebar.time-paused" : "sidebar.time", "time", time(player, session.remaining)));
        } else {
            lines.add(messages.get(player, "sidebar.time-staff"));
        }
        long closing = setup.closeAt() - System.currentTimeMillis();
        lines.add(messages.get(player, "sidebar.closing", "time", time(player, closing)));
        double multiplier = multipliers.getOrDefault(player.getWorld().getEnvironment(), 1.0);
        if (multiplier != 1) {
            lines.add(messages.get(player, "sidebar.multiplier", "multiplier",
                    String.valueOf(multiplier).replace(".0", "").toLowerCase(Locale.ROOT)));
        }
        Component event = eventLine.apply(player);
        if (event != null) {
            lines.add(Component.empty());
            lines.add(event);
        }
        if (session != null) {
            lines.add(Component.empty());
            lines.add(messages.get(player, "sidebar.harvest", "blocks", String.valueOf(session.blocks), "ores", String.valueOf(session.ores)));
        }
        lines.add(Component.empty());
        return new SidebarOverrides.Content(messages.get(player, "sidebar.title"), lines);
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

    /** Tous les joueurs suivis ici (fermeture du monde). */
    public List<UUID> players() {
        return List.copyOf(sessions.keySet());
    }

    private String time(Player player, long millis) {
        return lib.formatDuration(player, Math.max(0, millis) / 1000);
    }
}
