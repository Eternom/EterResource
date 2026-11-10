package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Les règles d'un monde ressource (pas un monde pour construire ni pour élever) :
 * - un coffre (tout conteneur) posé par un joueur n'est ouvert ni cassé que par lui (et le staff) ;
 * - au plus guard.entities-per-chunk créatures nées d'un élevage, d'un œuf ou d'un spawner par chunk ;
 * - au plus guard.hoppers-per-chunk hoppers, droppers et dispensers par chunk ;
 * - pas d'œuf de créature sur un spawner ;
 * - plus de guard.break-alert-per-minute blocs cassés en une minute : le staff est prévenu.
 */
public class Guard implements Listener {

    private static final String STAFF = "eterresource.admin";
    private static final Set<CreatureSpawnEvent.SpawnReason> FARMED = EnumSet.of(CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.EGG, CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM, CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN);
    private static final Set<Material> MACHINES = EnumSet.of(Material.HOPPER, Material.DROPPER, Material.DISPENSER);

    private final JavaPlugin plugin;
    private final Messages messages;
    private final NamespacedKey owner;
    private final int entitiesPerChunk;
    private final int hoppersPerChunk;
    private final int breakAlert;
    private final Map<UUID, int[]> breaks = new HashMap<>();
    private final Map<UUID, Long> alerted = new HashMap<>();
    private long minute;

    public Guard(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.owner = new NamespacedKey(plugin, "owner");
        this.entitiesPerChunk = Math.max(1, plugin.getConfig().getInt("guard.entities-per-chunk", 24));
        this.hoppersPerChunk = Math.max(0, plugin.getConfig().getInt("guard.hoppers-per-chunk", 6));
        this.breakAlert = Math.max(0, plugin.getConfig().getInt("guard.break-alert-per-minute", 400));
    }

    // ---------- Coffres ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        BlockState state = event.getBlockPlaced().getState();
        if (state instanceof Container && state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(owner, PersistentDataType.STRING, event.getPlayer().getUniqueId().toString());
            tile.update();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMachine(BlockPlaceEvent event) {
        if (!MACHINES.contains(event.getBlockPlaced().getType()) || event.getPlayer().hasPermission(STAFF)) {
            return;
        }
        Chunk chunk = event.getBlockPlaced().getChunk();
        long count = java.util.Arrays.stream(chunk.getTileEntities()).filter(tile -> MACHINES.contains(tile.getType())).count();
        if (count > hoppersPerChunk) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "guard.machines", "max", String.valueOf(hoppersPerChunk));
        }
    }

    private UUID ownerOf(Block block) {
        if (!(block.getState() instanceof TileState tile)) {
            return null;
        }
        String value = tile.getPersistentDataContainer().get(owner, PersistentDataType.STRING);
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null) {
            return;
        }
        Player player = event.getPlayer();
        if (block.getType() == Material.SPAWNER) {
            ItemStack hand = event.getItem();
            if (hand != null && hand.getType().name().endsWith("_SPAWN_EGG") && !player.hasPermission(STAFF)) {
                event.setCancelled(true);
                messages.actionBar(player, "guard.spawner");
            }
            return;
        }
        UUID by = ownerOf(block);
        if (by != null && !by.equals(player.getUniqueId()) && !player.hasPermission(STAFF)) {
            event.setCancelled(true);
            messages.actionBar(player, "guard.container");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID by = ownerOf(event.getBlock());
        if (by != null && !by.equals(player.getUniqueId()) && !player.hasPermission(STAFF)) {
            event.setCancelled(true);
            messages.actionBar(player, "guard.container");
            return;
        }
        watchRate(player);
    }

    // ---------- Créatures ----------

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!FARMED.contains(event.getSpawnReason())) {
            return;
        }
        long count = 0;
        for (Entity entity : event.getLocation().getChunk().getEntities()) {
            if (entity instanceof LivingEntity && !(entity instanceof Player)) {
                count++;
            }
        }
        if (count >= entitiesPerChunk) {
            event.setCancelled(true);
        }
    }

    // ---------- Cadence ----------

    private void watchRate(Player player) {
        if (breakAlert <= 0) {
            return;
        }
        long now = System.currentTimeMillis() / 60_000L;
        if (now != minute) {
            minute = now;
            breaks.clear();
        }
        int[] count = breaks.computeIfAbsent(player.getUniqueId(), uuid -> new int[1]);
        if (++count[0] == breakAlert) {
            long last = alerted.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() - last > 10 * 60_000L) {
                alerted.put(player.getUniqueId(), System.currentTimeMillis());
                plugin.getLogger().warning("[Monde ressource] cadence anormale : " + player.getName() + " a cassé " + breakAlert + " blocs en une minute");
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission(STAFF)) {
                        messages.send(staff, "guard.rate-alert", "player", player.getName(), "count", String.valueOf(breakAlert));
                    }
                }
            }
        }
    }
}
