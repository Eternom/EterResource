package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Le camp de base, au centre du monde : une plateforme sûre (même sur l'eau), le spawn, et des postes (camp.stations :
 * un bloc et une action d'EterLib, ex : le sac de retour, le relais de la guilde, le marchand, le carnet d'expédition),
 * avec leur nom au-dessus. Dans le camp (camp.radius) : le temps du monde ressource est en pause, on ne casse ni ne
 * pose rien (sauf le staff), les explosions n'y abîment rien. Reconstruit à chaque monde (rien n'est enregistré).
 */
public class Camp implements Listener {

    private static final int HALF = 6;
    private static final String STAFF = "eterresource.admin";

    /** Un poste du camp : bloc, position (par rapport au centre), actions au clic. */
    private record Station(String id, Material block, int dx, int dy, int dz, List<String> actions) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final int radius;
    private final List<Station> stations = new ArrayList<>();
    private final Map<Location, Station> placed = new LinkedHashMap<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private Location center;

    public Camp(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        ConfigurationSection camp = plugin.getConfig().getConfigurationSection("camp");
        this.radius = camp == null ? 12 : Math.max(HALF + 2, camp.getInt("radius", 12));
        ConfigurationSection section = camp == null ? null : camp.getConfigurationSection("stations");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection station = section.getConfigurationSection(id);
                Material block = station == null ? null : Material.matchMaterial(station.getString("block", "BARREL"));
                List<Integer> offset = station == null ? List.of() : station.getIntegerList("offset");
                if (block == null || !block.isBlock() || offset.size() != 3) {
                    plugin.getLogger().warning("camp.stations." + id + " : bloc ou offset [x, y, z] invalide, ignoré");
                    continue;
                }
                List<String> actions = station.isList("actions") ? station.getStringList("actions")
                        : List.of(station.getString("action", ""));
                stations.add(new Station(id, block, offset.get(0), offset.get(1), offset.get(2), actions));
            }
        }
    }

    /** Construit le camp autour de ce point (x, z ; la hauteur est cherchée) et y met le spawn. Thread principal. */
    public Location build(World world, int x, int z) {
        Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int y = top.getY();
        if (top.isLiquid() || top.getType() == Material.ICE) {
            y = Math.max(y, world.getSeaLevel() - 1) + 1; // sur l'eau : un ponton
        }
        y = Math.clamp(y, world.getMinHeight() + 5, world.getMaxHeight() - 12);
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                boolean edge = Math.abs(dx) == HALF || Math.abs(dz) == HALF;
                world.getBlockAt(x + dx, y, z + dz).setType(edge ? Material.STRIPPED_SPRUCE_LOG : Material.SPRUCE_PLANKS, false);
                // Dessous : de quoi tenir ; dessus : de l'air
                for (int below = 1; below <= 4; below++) {
                    Block support = world.getBlockAt(x + dx, y - below, z + dz);
                    if (support.isEmpty() || support.isLiquid() || !support.getType().isSolid()) {
                        support.setType(Material.SPRUCE_PLANKS, false);
                    }
                }
                for (int above = 1; above <= 6; above++) {
                    world.getBlockAt(x + dx, y + above, z + dz).setType(Material.AIR, false);
                }
            }
        }
        for (int[] corner : new int[][]{{-HALF, -HALF}, {-HALF, HALF}, {HALF, -HALF}, {HALF, HALF}}) {
            world.getBlockAt(x + corner[0], y + 1, z + corner[1]).setType(Material.SPRUCE_FENCE, false);
            world.getBlockAt(x + corner[0], y + 2, z + corner[1]).setType(Material.SPRUCE_FENCE, false);
            Block lantern = world.getBlockAt(x + corner[0], y + 3, z + corner[1]);
            lantern.setType(Material.LANTERN, false);
            if (lantern.getBlockData() instanceof Lantern data) {
                data.setHanging(false);
                lantern.setBlockData(data, false);
            }
        }
        Block fire = world.getBlockAt(x, y + 1, z);
        fire.setType(Material.CAMPFIRE, false);
        if (fire.getBlockData() instanceof Campfire data) {
            data.setSignalFire(false);
            fire.setBlockData(data, false);
        }
        center = new Location(world, x + 0.5, y + 1, z + 0.5);
        labels.forEach(TextDisplay::remove);
        labels.clear();
        placed.clear();
        for (Station station : stations) {
            Block block = world.getBlockAt(x + station.dx(), y + station.dy(), z + station.dz());
            block.setType(station.block(), false);
            placed.put(block.getLocation(), station);
            Location above = block.getLocation().add(0.5, 1.6, 0.5);
            TextDisplay label = world.spawn(above, TextDisplay.class, display -> {
                display.setPersistent(false);
                display.setBillboard(Display.Billboard.CENTER);
                display.text(messages.get(Bukkit.getConsoleSender(), "camp.station." + station.id()));
                display.setShadowed(true);
            });
            labels.add(label);
        }
        Location spawn = new Location(world, x + 0.5, y + 1, z + 3.5, 180, 0);
        world.setSpawnLocation(spawn);
        plugin.getLogger().info("Camp de base construit en " + x + " " + y + " " + z);
        return spawn;
    }

    public Location center() {
        return center;
    }

    public int radius() {
        return radius;
    }

    /** Ce point est-il dans le camp (même monde, rayon, et pas trop loin en hauteur) ? */
    public boolean contains(Location location) {
        if (center == null || location.getWorld() != center.getWorld()) {
            return false;
        }
        double dx = location.getX() - center.getX();
        double dz = location.getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius && Math.abs(location.getY() - center.getY()) <= 24;
    }

    // ---------- Postes et protection ----------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Station station = placed.get(event.getClickedBlock().getLocation());
        if (station == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (station.actions().stream().anyMatch(action -> !action.isBlank())) {
            EterLib.get().getActions().run(player, station.actions());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (contains(event.getBlock().getLocation()) && !event.getPlayer().hasPermission(STAFF)) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "camp.protected");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (contains(event.getBlock().getLocation()) && !event.getPlayer().hasPermission(STAFF)) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "camp.protected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> contains(block.getLocation()));
    }

    /** Arrêt : les noms des postes disparaissent (ils ne sont pas gardés dans le monde). */
    public void stop() {
        labels.forEach(TextDisplay::remove);
        labels.clear();
    }
}
