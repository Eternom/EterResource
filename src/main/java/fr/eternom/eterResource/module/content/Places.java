package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.quest.QuestBridge;
import fr.eternom.eterResource.module.world.Camp;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les lieux à découvrir d'un monde ressource, posés quand il est prêt (places) : petites constructions avec un coffre
 * (camp de mineurs, tour de guet, ruines, donjon souterrain, puits de lave), filons riches sous terre et bosquets.
 * Chaque lieu a son butin (places.loot.<type>). Un joueur qui s'approche d'un lieu le découvre (son, message, signal
 * eterresource:discover pour les quêtes) ; le carnet d'expédition (poste du camp, eterresource:map) liste les lieux :
 * direction et distance, et les coordonnées de ceux qu'il a découverts.
 */
public class Places {

    /** Un lieu : type, position, découvert par qui. */
    public record Place(String type, Location location, Set<UUID> discoveredBy) {
    }

    private static final List<String> BUILDINGS = List.of("miners-camp", "watchtower", "ruins", "dungeon", "lava-well");
    private static final int DISCOVER_RADIUS = 16;

    private final JavaPlugin plugin;
    private final Messages messages;
    private final Camp camp;
    private final List<Place> places = new ArrayList<>();

    public Places(JavaPlugin plugin, Messages messages, Camp camp) {
        this.plugin = plugin;
        this.messages = messages;
        this.camp = camp;
    }

    public List<Place> all() {
        return List.copyOf(places);
    }

    // ---------- Pose ----------

    /** Le monde est prêt : les lieux sont posés (dans la zone prégénérée, autour du camp). Thread principal. */
    public void generate(double oreMultiplier) {
        Location center = camp.center();
        if (center == null) {
            return;
        }
        World world = center.getWorld();
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("places");
        int min = config == null ? 120 : config.getInt("min-distance", 120);
        int max = config == null ? 650 : config.getInt("max-distance", 650);
        int buildings = config == null ? 6 : config.getInt("buildings", 6);
        int deposits = (int) Math.round((config == null ? 5 : config.getInt("deposits", 5)) * Math.max(1, oreMultiplier));
        int groves = config == null ? 3 : config.getInt("groves", 3);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < buildings; i++) {
            spot(world, center, min, max, random).ifPresent(at -> build(BUILDINGS.get(random.nextInt(BUILDINGS.size())), at));
        }
        for (int i = 0; i < deposits; i++) {
            spot(world, center, min / 2, max, random).ifPresent(this::deposit);
        }
        for (int i = 0; i < groves; i++) {
            spot(world, center, min, max, random).ifPresent(this::grove);
        }
        plugin.getLogger().info(places.size() + " lieux posés dans le monde ressource");
        Bukkit.getScheduler().runTaskTimer(plugin, this::discover, 40, 40);
    }

    /** Un point de surface au hasard entre min et max blocs du camp (pas dans l'eau). */
    private java.util.Optional<Location> spot(World world, Location center, int min, int max, ThreadLocalRandom random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * Math.max(1, max - min);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
                continue;
            }
            Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (top.isLiquid() || top.getType() == Material.ICE) {
                continue;
            }
            return java.util.Optional.of(top.getLocation().add(0, 1, 0));
        }
        return java.util.Optional.empty();
    }

    private void build(String type, Location at) {
        World world = at.getWorld();
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        Location chest;
        switch (type) {
            case "watchtower" -> {
                for (int dy = 0; dy < 9; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            boolean wall = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                            set(world, x + dx, y + dy, z + dz, wall ? (dy % 3 == 0 ? Material.STONE_BRICKS : Material.COBBLESTONE) : Material.AIR);
                        }
                    }
                }
                set(world, x, y + 9, z, Material.COBBLESTONE);
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        set(world, x + dx, y + 9, z + dz, Material.SPRUCE_PLANKS);
                    }
                }
                set(world, x + 1, y, z, Material.AIR);
                set(world, x + 1, y + 1, z, Material.AIR);
                for (int dy = 0; dy < 9; dy++) {
                    set(world, x, y + dy, z, Material.LADDER);
                }
                chest = new Location(world, x + 1, y + 10, z + 1);
            }
            case "ruins" -> {
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        if ((Math.abs(dx) == 3 || Math.abs(dz) == 3) && random.nextDouble() < 0.7) {
                            int height = 1 + random.nextInt(3);
                            for (int dy = 0; dy < height; dy++) {
                                set(world, x + dx, y + dy, z + dz, random.nextBoolean() ? Material.MOSSY_COBBLESTONE : Material.CRACKED_STONE_BRICKS);
                            }
                        }
                    }
                }
                chest = new Location(world, x, y, z);
            }
            case "dungeon" -> {
                int floor = Math.max(world.getMinHeight() + 8, Math.min(y - 20, 30));
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 0; dy <= 4; dy++) {
                            boolean shell = Math.abs(dx) == 3 || Math.abs(dz) == 3 || dy == 0 || dy == 4;
                            set(world, x + dx, floor + dy, z + dz, shell ? Material.MOSSY_STONE_BRICKS : Material.AIR);
                        }
                    }
                }
                // Un puits d'accès depuis la surface
                for (int dy = floor + 1; dy < y; dy++) {
                    set(world, x + 2, dy, z, Material.AIR);
                    set(world, x + 2, dy, z + 1, Material.LADDER);
                }
                set(world, x, floor + 1, z - 2, Material.SOUL_LANTERN);
                chest = new Location(world, x, floor + 1, z);
                for (int i = 0; i < 3; i++) {
                    world.spawnEntity(new Location(world, x + 0.5, floor + 1, z + 0.5 + i - 1), i == 2 ? EntityType.SKELETON : EntityType.ZOMBIE);
                }
                at = new Location(world, x, floor + 1, z);
            }
            case "lava-well" -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        set(world, x + dx, y - 1, z + dz, ring ? Material.POLISHED_BLACKSTONE_BRICKS : Material.LAVA);
                        set(world, x + dx, y - 2, z + dz, Material.OBSIDIAN);
                        if (ring) {
                            set(world, x + dx, y, z + dz, (dx + dz) % 2 == 0 ? Material.POLISHED_BLACKSTONE_BRICK_WALL : Material.AIR);
                        }
                    }
                }
                set(world, x, y - 3, z, Material.MAGMA_BLOCK);
                chest = new Location(world, x + 3, y, z);
            }
            default -> { // miners-camp : une cabane de mineurs
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                        set(world, x + dx, y - 1, z + dz, Material.OAK_PLANKS);
                        for (int dy = 0; dy <= 2; dy++) {
                            set(world, x + dx, y + dy, z + dz, wall ? (Math.abs(dx) == 2 && Math.abs(dz) == 2 ? Material.OAK_LOG : Material.OAK_PLANKS)
                                    : Material.AIR);
                        }
                        set(world, x + dx, y + 3, z + dz, Material.SPRUCE_SLAB);
                    }
                }
                set(world, x, y, z - 2, Material.AIR);
                set(world, x, y + 1, z - 2, Material.AIR);
                set(world, x + 1, y, z + 1, Material.CRAFTING_TABLE);
                set(world, x - 1, y + 2, z + 1, Material.LANTERN);
                chest = new Location(world, x - 1, y, z + 1);
            }
        }
        fillChest(chest, type);
        places.add(new Place(type, at, new HashSet<>()));
    }

    /** Un filon riche : une boule de minerais sous terre, selon la profondeur. */
    private void deposit(Location surface) {
        World world = surface.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int y = Math.max(world.getMinHeight() + 6, random.nextInt(-40, 40));
        Material ore = y < 0 ? (random.nextDouble() < 0.25 ? Material.DEEPSLATE_DIAMOND_ORE : random.nextBoolean()
                ? Material.DEEPSLATE_GOLD_ORE : Material.DEEPSLATE_REDSTONE_ORE)
                : random.nextDouble() < 0.4 ? Material.IRON_ORE : random.nextBoolean() ? Material.COPPER_ORE : Material.COAL_ORE;
        int radius = 2 + random.nextInt(2);
        Location center = new Location(world, surface.getBlockX(), y, surface.getBlockZ());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius || random.nextDouble() < 0.35) {
                        continue;
                    }
                    Block block = world.getBlockAt(center.getBlockX() + dx, y + dy, center.getBlockZ() + dz);
                    Material type = block.getType();
                    if (type == Material.STONE || type == Material.DEEPSLATE || type == Material.TUFF || type == Material.ANDESITE
                            || type == Material.DIORITE || type == Material.GRANITE) {
                        block.setType(ore, false);
                    }
                }
            }
        }
        places.add(new Place("deposit:" + ore.name().toLowerCase(Locale.ROOT), center, new HashSet<>()));
    }

    /** Un bosquet : quelques arbres d'une essence rare. */
    private void grove(Location at) {
        TreeType[] kinds = {TreeType.CHERRY, TreeType.AZALEA, TreeType.DARK_OAK, TreeType.BIRCH, TreeType.MANGROVE};
        TreeType kind = kinds[ThreadLocalRandom.current().nextInt(kinds.length)];
        World world = at.getWorld();
        int grown = 0;
        for (int i = 0; i < 6; i++) {
            Location spot = at.clone().add(ThreadLocalRandom.current().nextInt(-8, 9), 0, ThreadLocalRandom.current().nextInt(-8, 9));
            spot.setY(world.getHighestBlockYAt(spot, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1);
            if (world.generateTree(spot, kind)) {
                grown++;
            }
        }
        if (grown > 0) {
            places.add(new Place("grove:" + kind.name().toLowerCase(Locale.ROOT), at, new HashSet<>()));
        }
    }

    private static void set(World world, int x, int y, int z, Material type) {
        if (y > world.getMinHeight() && y < world.getMaxHeight()) {
            world.getBlockAt(x, y, z).setType(type, false);
        }
    }

    /** Le coffre et son butin (places.loot.<type> : « MATIÈRE min-max chance »). */
    private void fillChest(Location at, String type) {
        Block block = at.getBlock();
        block.setType(Material.CHEST, false);
        if (!(block.getState() instanceof Chest chest)) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<String> loot = plugin.getConfig().getStringList("places.loot." + type);
        if (loot.isEmpty()) {
            loot = plugin.getConfig().getStringList("places.loot.default");
        }
        for (String line : loot) {
            String[] parts = line.trim().split("\\s+");
            Material material = Material.matchMaterial(parts[0]);
            if (material == null || !material.isItem()) {
                continue;
            }
            int low = 1;
            int high = 1;
            double chance = 1;
            try {
                if (parts.length > 1) {
                    String[] range = parts[1].split("-");
                    low = Integer.parseInt(range[0]);
                    high = range.length > 1 ? Integer.parseInt(range[1]) : low;
                }
                if (parts.length > 2) {
                    chance = Double.parseDouble(parts[2]);
                }
            } catch (NumberFormatException ignored) {
                // valeurs par défaut
            }
            if (random.nextDouble() < chance) {
                int amount = Math.clamp(random.nextInt(low, Math.max(low, high) + 1), 1, material.getMaxStackSize());
                chest.getBlockInventory().setItem(random.nextInt(27), ItemStack.of(material, amount));
            }
        }
    }

    // ---------- Découverte et carnet ----------

    private void discover() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            for (Place place : places) {
                if (place.location().getWorld() == location.getWorld()
                        && place.location().distanceSquared(location) <= DISCOVER_RADIUS * DISCOVER_RADIUS
                        && place.discoveredBy().add(player.getUniqueId())) {
                    String kind = place.type().contains(":") ? place.type().substring(0, place.type().indexOf(':')) : place.type();
                    messages.send(player, "places.discovered", "place", messages.plain(player, "places.type." + kind));
                    player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f);
                    QuestBridge.signal(player, "eterresource:discover", Map.of("place", kind, "detail",
                            place.type().contains(":") ? place.type().substring(place.type().indexOf(':') + 1) : ""), 1);
                }
            }
        }
    }

    /** Le carnet d'expédition : un livre avec chaque lieu (direction, distance ; coordonnées s'il est découvert). */
    public void openBook(Player player) {
        Location here = player.getLocation();
        List<Component> pages = new ArrayList<>();
        pages.add(messages.get(player, "places.book.intro", "count", String.valueOf(places.size())));
        Component page = Component.empty();
        int lines = 0;
        Map<String, Integer> counter = new HashMap<>();
        for (Place place : places) {
            String kind = place.type().contains(":") ? place.type().substring(0, place.type().indexOf(':')) : place.type();
            int number = counter.merge(kind, 1, Integer::sum);
            Component line;
            if (place.discoveredBy().contains(player.getUniqueId())) {
                line = messages.get(player, "places.book.found", "place", messages.plain(player, "places.type." + kind), "n", String.valueOf(number),
                        "x", String.valueOf(place.location().getBlockX()), "y", String.valueOf(place.location().getBlockY()),
                        "z", String.valueOf(place.location().getBlockZ()));
            } else {
                double dx = place.location().getX() - here.getX();
                double dz = place.location().getZ() - here.getZ();
                long distance = Math.round(Math.hypot(dx, dz) / 50) * 50;
                line = messages.get(player, "places.book.hint", "place", messages.plain(player, "places.type." + kind), "n", String.valueOf(number),
                        "direction", messages.plain(player, "places.direction." + direction(dx, dz)), "distance", String.valueOf(Math.max(50, distance)));
            }
            page = page.append(line).append(Component.newline());
            if (++lines == 6) {
                pages.add(page);
                page = Component.empty();
                lines = 0;
            }
        }
        if (lines > 0) {
            pages.add(page);
        }
        player.openBook(Book.book(messages.get(player, "places.book.title"), Component.text("EterResource"), pages));
    }

    /** Huit directions (nord = -z). */
    static String direction(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return names[(int) Math.floorMod(Math.round(angle / 45), 8)];
    }
}
