package fr.eternom.eterResource.module.content;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.quest.QuestBridge;
import fr.eternom.eterResource.module.bonus.JobBonuses;
import fr.eternom.eterResource.module.world.Closing;
import fr.eternom.eterResource.module.world.Sessions;
import fr.eternom.eterResource.module.world.WorldReadyEvent;
import fr.eternom.eterResource.module.world.WorldSetup;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le contenu des mondes ressources, réuni : le sac de retour (partout), et sur un monde ressource le marchand du camp,
 * les lieux à découvrir et le carnet d'expédition, les événements, les règles (Guard), les minerais en plus, le
 * classement de la semaine (« resource_week »), l'annonce d'un nouveau monde sur tout le réseau, et les signaux pour
 * les quêtes (eterresource:arrive {theme}).
 * Actions d'EterLib : eterresource:bag, eterresource:shop, eterresource:map.
 */
public class ResourceContent implements Listener {

    private static final String READY = "world-ready";

    private final JavaPlugin plugin;
    private final Messages messages;
    private final ContentStore store;
    private final ReturnBag bag;
    private final NetworkBus bus;
    private WorldSetup setup;
    private CampShop shop;
    private Places places;
    private WorldEvents events;
    private final Map<UUID, Integer> blocks = new ConcurrentHashMap<>();

    public ResourceContent(JavaPlugin plugin, Messages messages, ContentStore store, NetworkBus bus) {
        this.plugin = plugin;
        this.messages = messages;
        this.store = store;
        this.bus = bus;
        this.bag = new ReturnBag(plugin, store, messages);
        Bukkit.getPluginManager().registerEvents(bag, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getServicesManager().register(RankingSource.class, store, plugin, ServicePriority.Normal);
        // Un nouveau monde ressource est prêt : annoncé sur tout le réseau
        bus.on(READY, data -> Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "announce.ready",
                "server", EterLib.get().getServerDisplayName(data.get("server").getAsString()),
                "theme", messages.plain(player, "theme." + data.get("theme").getAsString() + ".name"))));
        Actions actions = EterLib.get().getActions();
        actions.register(plugin, "eterresource:bag", "Le sac de retour", List.of(), (player, args) -> bag.open(player));
    }

    /** Sur un monde ressource : marchand, lieux, événements, règles, minerais en plus. */
    public void startWorld(WorldSetup setup, Sessions sessions, Closing closing, JobBonuses jobs) {
        this.setup = setup;
        this.shop = new CampShop(plugin, messages, setup.camp());
        this.places = new Places(plugin, messages, setup.camp());
        this.events = new WorldEvents(plugin, messages, setup.camp(), setup.theme(), jobs);
        Bukkit.getPluginManager().registerEvents(events, plugin);
        Bukkit.getPluginManager().registerEvents(new Guard(plugin, messages), plugin);
        sessions.setEventLine(events::sidebarLine);
        Actions actions = EterLib.get().getActions();
        actions.register(plugin, "eterresource:shop", "Le marchand du camp", List.of(), (player, args) -> shop.open(player));
        actions.register(plugin, "eterresource:map", "Le carnet d'expédition (lieux du monde)", List.of(), (player, args) -> places.openBook(player));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushStats, 60 * 20L, 60 * 20L);
        this.closing = closing;
    }

    private Closing closing;

    /** Les minerais en plus du thème, avant la génération du monde (avant WorldSetup.start). */
    public static void addPopulator(WorldSetup setup) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                world.getPopulators().add(new OrePopulator(() -> setup.theme().oreMultiplier()));
            }
        }
    }

    public ReturnBag bag() {
        return bag;
    }

    public Places places() {
        return places;
    }

    public WorldEvents events() {
        return events;
    }

    @EventHandler
    public void onReady(WorldReadyEvent event) {
        if (places != null) {
            places.generate(setup.theme().oreMultiplier());
            shop.start();
            events.start();
        }
        JsonObject data = new JsonObject();
        data.addProperty("server", EterLib.get().getServerName());
        data.addProperty("theme", event.theme());
        bus.publish(READY, data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (setup == null) {
            return;
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            QuestBridge.signal(player, "eterresource:arrive", Map.of("theme", setup.theme().id()), 1);
            if (events != null) {
                messages.send(player, "daily-resource.hint", "item", events.dailyResource().name().toLowerCase().replace('_', ' '));
            }
            if (closing != null) {
                closing.show(player);
            }
        }, 60);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (setup != null) {
            blocks.merge(event.getPlayer().getUniqueId(), 1, Integer::sum);
        }
    }

    private void flushStats() {
        for (UUID player : List.copyOf(blocks.keySet())) {
            Integer count = blocks.remove(player);
            if (count != null && count > 0) {
                try {
                    store.addBlocks(player, count);
                } catch (RuntimeException e) {
                    blocks.merge(player, count, Integer::sum);
                }
            }
        }
    }

    /** Arrêt : les derniers blocs comptés (bloquant). */
    public void stop() {
        flushStats();
    }
}
