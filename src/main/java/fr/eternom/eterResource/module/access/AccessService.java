package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import fr.eternom.eterResource.module.world.WorldDirectory;
import fr.eternom.eterEconomy.api.EconomyApi;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Accès aux mondes ressources : acheter un créneau, utiliser une clé, partir sur le monde le moins rempli.
 * Le temps ne s'écoule que sur un monde ressource (voir Sessions) ; on peut en garder au plus max-minutes.
 * Mondes ouverts et joueurs par serveur relus toutes les 5 s en tâche de fond : le menu s'ouvre sans attendre.
 */
public class AccessService {

    private static final long REFRESH_TICKS = 5 * 20;

    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Messages messages;
    private final AccessRepository access;
    private final WorldDirectory worlds;
    private final boolean worldServer;
    private final long slotMillis;
    private final long maxMillis;
    private final double slotPrice;
    /** Prévenu quand le temps d'un joueur change ici (sur un monde ressource : le compte à rebours se relit). */
    private Consumer<Player> onTimeChanged = player -> {
    };
    private volatile List<String> open = List.of();
    private volatile Map<String, Integer> counts = Map.of();

    public AccessService(JavaPlugin plugin, EterLib lib, Messages messages, AccessRepository access, WorldDirectory worlds,
                         boolean worldServer) {
        this.plugin = plugin;
        this.lib = lib;
        this.messages = messages;
        this.access = access;
        this.worlds = worlds;
        this.worldServer = worldServer;
        FileConfiguration config = plugin.getConfig();
        this.slotMillis = Duration.ofMinutes(Math.max(1, config.getLong("access.slot-minutes", 30))).toMillis();
        this.maxMillis = Duration.ofMinutes(Math.max(1, config.getLong("access.max-minutes", 120))).toMillis();
        this.slotPrice = config.getDouble("access.slot-price", 600);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                open = worlds.open();
                counts = lib.getPlayers().countByServer();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Mondes ressources non relus : " + e.getMessage());
            }
        }, 0, REFRESH_TICKS);
    }

    public void onTimeChanged(Consumer<Player> listener) {
        this.onTimeChanged = listener;
    }

    /** Le temps du joueur a changé hors de ce service (commande du staff). */
    public void timeChanged(Player player) {
        onTimeChanged.accept(player);
    }

    /** Temps et clés du joueur (tâche de fond), puis then sur le thread principal. */
    public void load(Player player, Consumer<Access> then) {
        Tasks.async(plugin, player, () -> access.get(player.getUniqueId()), then, () -> messages.send(player, "error.generic"));
    }

    /** Achète un créneau : le temps est ajouté d'abord (refusé au-delà du maximum), puis retiré si le paiement échoue. */
    public void buy(Player player, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (slotPrice <= 0) {
            messages.send(player, "access.buy-disabled");
            return;
        }
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        Tasks.async(plugin, player, () -> {
            if (!access.addTime(player.getUniqueId(), slotMillis, maxMillis)) {
                return "access.full";
            }
            if (!economy.withdraw(player.getUniqueId(), slotPrice, "EterResource · créneau")) {
                access.removeTime(player.getUniqueId(), slotMillis);
                return "access.not-enough";
            }
            return "access.bought";
        }, result -> {
            done(player, result, result.equals("access.bought"));
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    public void useKey(Player player, Access current, Runnable after) {
        if (current.keys() <= 0) {
            messages.send(player, "access.no-key");
            return;
        }
        Tasks.async(plugin, player, () -> access.useKey(player.getUniqueId(), slotMillis, maxMillis),
                used -> {
                    done(player, used ? "access.key-used" : "access.full", used);
                    after.run();
                }, () -> messages.send(player, "error.generic"));
    }

    /** Envoie le joueur sur le monde ressource ouvert le moins rempli. */
    public void go(Player player, Access current) {
        if (worldServer) {
            messages.send(player, "access.already-there");
        } else if (current.remainingMillis() <= 0) {
            messages.send(player, "access.no-time");
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            best().ifPresentOrElse(world -> {
                player.closeInventory();
                messages.send(player, "access.connecting", "server", lib.getServerDisplayName(world));
                lib.getTeleports().connect(player, world);
            }, () -> {
                messages.send(player, "access.no-world");
                player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            });
        }
    }

    public Optional<String> best() {
        return open.stream().min(Comparator.comparingInt(world -> counts.getOrDefault(world, 0)));
    }

    public int openWorlds() {
        return open.size();
    }

    public boolean isWorldServer() {
        return worldServer;
    }

    public long slotMillis() {
        return slotMillis;
    }

    public long maxMillis() {
        return maxMillis;
    }

    /** Prix d'un créneau ; 0 ou moins : achat désactivé. */
    public double slotPrice() {
        return slotPrice;
    }

    public String formatMillis(Player player, long millis) {
        return lib.formatDuration(player, Math.max(0, millis) / 1000);
    }

    private void done(Player player, String key, boolean success) {
        messages.send(player, key, "time", formatMillis(player, slotMillis), "max", formatMillis(player, maxMillis),
                "price", Money.format(slotPrice));
        if (success) {
            player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            onTimeChanged.accept(player);
        } else {
            player.playSound(player, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        }
    }
}
