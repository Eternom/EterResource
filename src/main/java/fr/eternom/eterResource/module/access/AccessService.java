package fr.eternom.eterResource.module.access;

import fr.eternom.eterEconomy.api.EconomyApi;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.economy.Money;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterResource.module.access.AccessRepository.Access;
import fr.eternom.eterResource.module.bonus.JobPerks;
import fr.eternom.eterResource.module.world.WorldDirectory;
import fr.eternom.eterResource.module.world.WorldDirectory.WorldInfo;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Accès aux mondes ressources : acheter du temps (des offres : plus long, moins cher à la minute ; remise aux heures
 * creuses), utiliser une clé, les minutes gratuites du jour (et celles d'un abonnement : eterresource.daily.<minutes>),
 * offrir du temps, l'historique, partir sur un monde (le moins rempli, celui choisi, ou celui d'un ami). Le temps ne
 * s'écoule que sur un monde ressource (Sessions) ; on peut en garder au plus max-minutes. Mondes ouverts et joueurs par
 * serveur relus toutes les 5 s en tâche de fond : le menu s'ouvre sans attendre.
 */
public class AccessService {

    private static final long REFRESH_TICKS = 5 * 20;
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final String DAILY_PERMISSION = "eterresource.daily.";

    /** Une offre de temps : minutes et prix. */
    public record Offer(int minutes, double price) {
        public long millis() {
            return Duration.ofMinutes(minutes).toMillis();
        }
    }

    /** Bonus de métier, montrés dans le menu (appliqués seulement sur le monde ressource). */
    private final JobPerks perks;
    private final JavaPlugin plugin;
    private final EterLib lib;
    private final Messages messages;
    private final AccessRepository access;
    private final WorldDirectory worlds;
    private final boolean worldServer;
    private final long slotMillis;
    private final long maxMillis;
    private final List<Offer> offers;
    private final int dailyFreeMinutes;
    private final int offPeakPlayers;
    private final double offPeakDiscount;
    private final int giftMinimum;
    /** Prévenu quand le temps d'un joueur change ici (sur un monde ressource : le compte à rebours se relit). */
    private Consumer<Player> onTimeChanged = player -> {
    };
    private volatile List<WorldInfo> open = List.of();
    private fr.eternom.eterLib.helper.cache.NetworkBus bus;
    private volatile Map<String, Integer> counts = Map.of();

    public AccessService(JavaPlugin plugin, EterLib lib, Messages messages, AccessRepository access, WorldDirectory worlds,
                         boolean worldServer, JobPerks perks) {
        this.perks = perks;
        this.plugin = plugin;
        this.lib = lib;
        this.messages = messages;
        this.access = access;
        this.worlds = worlds;
        this.worldServer = worldServer;
        FileConfiguration config = plugin.getConfig();
        this.slotMillis = Duration.ofMinutes(Math.max(1, config.getLong("access.slot-minutes", 30))).toMillis();
        this.maxMillis = Duration.ofMinutes(Math.max(1, config.getLong("access.max-minutes", 120))).toMillis();
        List<Offer> loaded = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("access.offers")) {
            Object minutes = raw.get("minutes");
            Object price = raw.get("price");
            if (minutes instanceof Number m && price instanceof Number p && m.intValue() > 0) {
                loaded.add(new Offer(m.intValue(), Math.max(0, p.doubleValue())));
            }
        }
        if (loaded.isEmpty() && config.getDouble("access.slot-price", 600) > 0) {
            loaded.add(new Offer((int) Duration.ofMillis(slotMillis).toMinutes(), config.getDouble("access.slot-price", 600)));
        }
        this.offers = List.copyOf(loaded.stream().limit(3).toList());
        this.dailyFreeMinutes = Math.max(0, config.getInt("access.daily-free-minutes", 15));
        this.offPeakPlayers = Math.max(0, config.getInt("access.off-peak.players", 5));
        this.offPeakDiscount = Math.clamp(config.getDouble("access.off-peak.discount", 0.2), 0, 0.9);
        this.giftMinimum = Math.max(1, config.getInt("access.gift-minimum-minutes", 10));
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

    /** Le bus du réseau (prévenir un ami de son cadeau, où qu'il soit). */
    public void setBus(fr.eternom.eterLib.helper.cache.NetworkBus bus) {
        this.bus = bus;
    }

    public void onTimeChanged(Consumer<Player> listener) {
        this.onTimeChanged = listener;
    }

    /** Le temps du joueur a changé hors de ce service (commande du staff). */
    public void timeChanged(Player player) {
        onTimeChanged.accept(player);
    }

    public JobPerks perks() {
        return perks;
    }

    /** Temps et clés du joueur (tâche de fond), puis then sur le thread principal. */
    public void load(Player player, Consumer<Access> then) {
        Tasks.async(plugin, player, () -> access.get(player.getUniqueId()), then, () -> messages.send(player, "error.generic"));
    }

    // ---------- Acheter ----------

    public List<Offer> offers() {
        return offers;
    }

    /** Heure creuse : peu de joueurs sur les mondes ressources ouverts, le temps est moins cher. */
    public boolean offPeak() {
        int players = open.stream().mapToInt(world -> counts.getOrDefault(world.name(), world.players())).sum();
        return offPeakDiscount > 0 && players < offPeakPlayers;
    }

    public double priceOf(Offer offer) {
        return Math.round(offer.price() * (offPeak() ? 1 - offPeakDiscount : 1) * 100) / 100.0;
    }

    public double offPeakDiscount() {
        return offPeakDiscount;
    }

    /** Achète une offre : le temps est ajouté d'abord (refusé au-delà du maximum), puis retiré si le paiement échoue. */
    public void buy(Player player, Offer offer, Runnable after) {
        EconomyApi economy = EconomyApi.get().orElse(null);
        if (economy == null) {
            messages.send(player, "economy.unavailable");
            return;
        }
        double price = priceOf(offer);
        Tasks.async(plugin, player, () -> {
            if (!access.addTime(player.getUniqueId(), offer.millis(), maxMillis)) {
                return "access.full";
            }
            if (price > 0 && !economy.withdraw(player.getUniqueId(), price, "EterResource · créneau")) {
                access.removeTime(player.getUniqueId(), offer.millis());
                return "access.not-enough";
            }
            access.log(player.getUniqueId(), offer.millis(), "buy");
            return "access.bought";
        }, result -> {
            done(player, result, result.equals("access.bought"), offer.millis(), price);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    public void useKey(Player player, Access current, Runnable after) {
        if (current.keys() <= 0) {
            messages.send(player, "access.no-key");
            return;
        }
        Tasks.async(plugin, player, () -> {
            boolean used = access.useKey(player.getUniqueId(), slotMillis, maxMillis);
            if (used) {
                access.log(player.getUniqueId(), slotMillis, "key");
            }
            return used;
        }, used -> {
            done(player, used ? "access.key-used" : "access.full", used, slotMillis, 0);
            after.run();
        }, () -> messages.send(player, "error.generic"));
    }

    // ---------- Minutes du jour, cadeaux, remboursement ----------

    /** Les minutes gratuites du jour du joueur : la plus grande de daily-free-minutes et de ses eterresource.daily.<n>. */
    public int dailyMinutes(Player player) {
        int minutes = dailyFreeMinutes;
        for (PermissionAttachmentInfo permission : player.getEffectivePermissions()) {
            String name = permission.getPermission();
            if (permission.getValue() && name.startsWith(DAILY_PERMISSION)) {
                try {
                    minutes = Math.max(minutes, Integer.parseInt(name.substring(DAILY_PERMISSION.length())));
                } catch (NumberFormatException ignored) {
                    // eterresource.daily.* ou autre : ignoré
                }
            }
        }
        return minutes;
    }

    /** À l'arrivée sur un serveur : les minutes du jour (une fois par jour). Thread principal. */
    public void grantDaily(Player player) {
        int minutes = dailyMinutes(player);
        if (minutes <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long day = LocalDate.now(ZONE).toEpochDay();
        long millis = Duration.ofMinutes(minutes).toMillis();
        Tasks.async(plugin, player, () -> {
            long added = access.grantDaily(uuid, day, millis, maxMillis);
            if (added > 0) {
                access.log(uuid, added, "daily");
            }
            return added;
        }, added -> {
            if (added > 0) {
                messages.send(player, "access.daily", "time", formatMillis(player, added));
                onTimeChanged.accept(player);
            }
        }, () -> { });
    }

    /** Offrir du temps à un joueur (pas en dessous de gift-minimum-minutes) ; l'ami le reçoit dans la limite de son plafond. */
    public void gift(Player player, UUID target, String targetName, int minutes) {
        if (minutes < giftMinimum) {
            messages.send(player, "gift.too-small", "minutes", String.valueOf(giftMinimum));
            return;
        }
        if (target.equals(player.getUniqueId())) {
            messages.send(player, "gift.self");
            return;
        }
        long millis = Duration.ofMinutes(minutes).toMillis();
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            if (!access.takeTime(uuid, millis)) {
                return "gift.not-enough";
            }
            if (!access.addTime(target, millis, maxMillis)) {
                access.addTime(uuid, millis, Long.MAX_VALUE / 2);
                return "gift.full";
            }
            access.log(uuid, -millis, "gift-to:" + targetName);
            access.log(target, millis, "gift-from:" + player.getName());
            return "gift.sent";
        }, result -> {
            messages.send(player, result, "player", targetName, "time", formatMillis(player, millis));
            if (result.equals("gift.sent")) {
                player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
                onTimeChanged.accept(player);
                if (bus != null) {
                    bus.notify(target, "gift.received", true, "player", player.getName(),
                        "time", formatMillis(player, millis));
                }
            } else {
                Sounds.refuse(player);
            }
        }, () -> messages.send(player, "error.generic"));
    }

    /**
     * À l'arrivée sur un serveur : une session restée ouverte sur un monde qui ne répond plus (il a planté) ; le temps
     * perdu (au plus 30 s non enregistrées) est rendu, avec un dédommagement (crash-refund-minutes).
     */
    public void refundCrash(Player player) {
        UUID uuid = player.getUniqueId();
        long millis = Duration.ofMinutes(Math.max(0, plugin.getConfig().getInt("access.crash-refund-minutes", 2))).toMillis();
        if (millis <= 0) {
            return;
        }
        Tasks.async(plugin, player, () -> {
            Optional<String> world = access.takeOpenSession(uuid);
            if (world.isEmpty() || open.stream().anyMatch(info -> info.name().equals(world.get()))) {
                return 0L;
            }
            access.addTime(uuid, millis, Long.MAX_VALUE / 2);
            access.log(uuid, millis, "crash:" + world.get());
            return millis;
        }, refunded -> {
            if (refunded > 0) {
                messages.send(player, "access.crash-refund", "time", formatMillis(player, refunded));
            }
        }, () -> { });
    }

    public void history(Player player, Consumer<List<AccessRepository.Entry>> then) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> access.history(uuid, 21), then, () -> messages.send(player, "error.generic"));
    }

    // ---------- Partir ----------

    /** Envoie le joueur sur le monde ressource ouvert le moins rempli (ou celui donné). */
    public void go(Player player, Access current, String world) {
        if (worldServer) {
            messages.send(player, "access.already-there");
        } else if (current.remainingMillis() <= 0) {
            messages.send(player, "access.no-time");
            Sounds.refuse(player);
        } else {
            Optional<String> target = world != null && open.stream().anyMatch(info -> info.name().equals(world)) ? Optional.of(world) : best();
            target.ifPresentOrElse(name -> {
                player.closeInventory();
                messages.send(player, "access.connecting", "server", lib.getServerDisplayName(name));
                lib.getTeleports().connect(player, name);
            }, () -> {
                messages.send(player, "access.no-world");
                Sounds.refuse(player);
            });
        }
    }

    public void go(Player player, Access current) {
        go(player, current, null);
    }

    /** Rejoindre le monde ressource où joue cet ami (s'il y en a un d'ouvert). */
    public void join(Player player, String friend) {
        Tasks.async(plugin, player, () -> lib.getPlayers().findOnlineFor(player, friend).map(found -> new Object[]{found.server(), access.get(player.getUniqueId())}),
                found -> {
                    String server = found.map(data -> (String) data[0]).orElse(null);
                    if (server == null || open.stream().noneMatch(info -> info.name().equals(server))) {
                        messages.send(player, "access.friend-not-there", "player", friend);
                        Sounds.refuse(player);
                        return;
                    }
                    go(player, (Access) found.get()[1], server);
                }, () -> messages.send(player, "error.generic"));
    }

    public Optional<String> best() {
        return open.stream().filter(info -> info.tps() >= 15 || open.size() == 1)
                .min(Comparator.comparingInt(info -> counts.getOrDefault(info.name(), info.players())))
                .or(() -> open.stream().min(Comparator.comparingInt(info -> counts.getOrDefault(info.name(), info.players()))))
                .map(WorldInfo::name);
    }

    public WorldDirectory directory() {
        return worlds;
    }

    public List<WorldInfo> worlds() {
        return open;
    }

    public int playersOn(String world) {
        return counts.getOrDefault(world, 0);
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

    /** Prix de la première offre ; 0 ou moins : achat désactivé. */
    public double slotPrice() {
        return offers.isEmpty() ? 0 : offers.getFirst().price();
    }

    public String formatMillis(Player player, long millis) {
        return lib.formatDuration(player, Math.max(0, millis) / 1000);
    }

    private void done(Player player, String key, boolean success, long millis, double price) {
        messages.send(player, key, "time", formatMillis(player, millis), "max", formatMillis(player, maxMillis), "price", Money.format(price));
        if (success) {
            player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            onTimeChanged.accept(player);
        } else {
            Sounds.refuse(player);
        }
    }

    static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        return section == null ? config.createSection(path) : section;
    }
}
