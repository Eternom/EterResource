package fr.eternom.eterResource.module.world;

import fr.eternom.eterResource.module.theme.Themes;
import fr.eternom.eterResource.module.theme.Themes.Theme;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BiomeSearchResult;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prépare les mondes d'un serveur ressource (surface, Nether, End, générés neufs). Le thème du jour (Themes) choisit
 * où s'installe le camp de base : le biome du thème le plus proche du spawn (sinon le spawn), centre du monde. Puis :
 * on garde son inventaire en mourant, pas de PvP, pas de sauvegarde automatique (monde jetable), une bordure par
 * dimension (surface : celle du thème), la prégénération autour du camp. Le serveur n'accepte les joueurs (ready)
 * qu'une fois la prégénération finie et les plugins requis présents ; son signe de vie (WorldDirectory) part toutes les
 * 10 s avec son thème, ses joueurs, son TPS et sa fermeture prévue (close_at = ouverture + durée de vie du thème).
 */
public class WorldSetup {

    private static final long HEARTBEAT_TICKS = 10 * 20;
    /** Chunks générés en même temps : assez pour aller vite, sans bloquer le serveur. */
    private static final int IN_FLIGHT = 8;
    private static final int BIOME_SEARCH_RADIUS = 4000;

    private record ChunkPos(World world, int x, int z) {
    }

    private final JavaPlugin plugin;
    private final WorldDirectory directory;
    private final String serverName;
    private final Camp camp;
    private final Themes themes;
    private volatile Theme theme = Themes.DEFAULT;
    private volatile boolean ready;
    private volatile boolean blocked;
    private volatile boolean closing;
    private volatile boolean test;
    private volatile long openedAt;
    private volatile long closeAt;
    private volatile int peak;
    private volatile int joins;

    public WorldSetup(JavaPlugin plugin, WorldDirectory directory, String serverName, Camp camp, Themes themes) {
        this.plugin = plugin;
        this.directory = directory;
        this.serverName = serverName;
        this.camp = camp;
        this.themes = themes;
    }

    public void start() {
        FileConfiguration config = plugin.getConfig();
        String forced = config.getString("force-theme", "");
        theme = themes.get(forced).orElseGet(() -> themes.today(ZoneId.of("Europe/Paris")));
        openedAt = System.currentTimeMillis();
        closeAt = openedAt + Duration.ofMinutes(Math.round(theme.lifetimeHours() * 60)).toMillis();
        checkRequiredPlugins(config.getStringList("required-plugins"));
        plugin.getLogger().info("Thème du monde ressource : " + theme.id() + " (fermeture dans " + theme.lifetimeHours() + " h)");
        List<ChunkPos> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String dimension = world.getEnvironment().name().toLowerCase(Locale.ROOT);
            world.setGameRule(GameRules.KEEP_INVENTORY, true);
            world.setGameRule(GameRules.PVP, false);
            world.setAutoSave(false);
            Location center = world.getSpawnLocation();
            double border = config.getDouble("world.border." + dimension, 0);
            if (world.getEnvironment() == World.Environment.NORMAL) {
                center = centerOf(world);
                center = camp.build(world, center.getBlockX(), center.getBlockZ());
                border = theme.border();
            }
            if (border > 0) {
                world.getWorldBorder().setCenter(center);
                world.getWorldBorder().setSize(border);
            }
            int radius = config.getInt("world.pregenerate." + dimension, 0) >> 4;
            int centerX = center.getBlockX() >> 4;
            int centerZ = center.getBlockZ() >> 4;
            List<ChunkPos> around = new ArrayList<>();
            for (int x = -radius; x <= radius && radius > 0; x++) {
                for (int z = -radius; z <= radius; z++) {
                    around.add(new ChunkPos(world, centerX + x, centerZ + z));
                }
            }
            // Du centre vers l'extérieur : le camp est prêt en premier
            around.sort(Comparator.comparingInt(chunk -> Math.max(Math.abs(chunk.x() - centerX), Math.abs(chunk.z() - centerZ))));
            chunks.addAll(around);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::beat, 0, HEARTBEAT_TICKS);
        pregenerate(chunks);
    }

    /** Le centre du monde : le biome du thème le plus proche du spawn, sinon le spawn. */
    private Location centerOf(World world) {
        Location spawn = world.getSpawnLocation();
        if (theme.biomes().isEmpty()) {
            return spawn;
        }
        try {
            BiomeSearchResult found = world.locateNearestBiome(spawn, BIOME_SEARCH_RADIUS, theme.biomes().toArray(Biome[]::new));
            if (found != null) {
                plugin.getLogger().info("Camp installé dans le biome " + found.getBiome().getKey().getKey());
                return found.getLocation();
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Recherche de biome impossible : " + e.getMessage());
        }
        plugin.getLogger().warning("Aucun biome du thème " + theme.id() + " à moins de " + BIOME_SEARCH_RADIUS + " blocs : camp au spawn");
        return spawn;
    }

    private void checkRequiredPlugins(List<String> required) {
        List<String> missing = required.stream().filter(name -> !Bukkit.getPluginManager().isPluginEnabled(name)).toList();
        if (!missing.isEmpty()) {
            blocked = true;
            plugin.getLogger().severe("Plugins requis absents ou en erreur : " + missing + " : le monde ne sera jamais proposé");
        }
    }

    /** Signe de vie (thread principal pour lire les joueurs et le TPS, écriture en tâche de fond). */
    private void beat() {
        int players = Bukkit.getOnlinePlayers().size();
        peak = Math.max(peak, players);
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        WorldDirectory.Beat beat = new WorldDirectory.Beat(isReady(), theme.id(), players, tps, openedAt, closeAt, test);
        int peakNow = peak;
        int joinsNow = joins;
        boolean logIt = ready;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                directory.heartbeat(serverName, beat);
                if (logIt) {
                    directory.log(serverName, theme.id(), openedAt, closing ? System.currentTimeMillis() : 0, peakNow, joinsNow);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Signe de vie du monde ressource non écrit : " + e.getMessage());
            }
        });
    }

    /** Prégénération finie, plugins requis là, pas en fermeture : les joueurs sont acceptés. */
    public boolean isReady() {
        return ready && !blocked && !closing;
    }

    public Theme theme() {
        return theme;
    }

    public long openedAt() {
        return openedAt;
    }

    public long closeAt() {
        return closeAt;
    }

    public boolean isClosing() {
        return closing;
    }

    /** La fermeture commence : le monde n'est plus proposé (Closing). */
    public void markClosing() {
        closing = true;
        beat();
    }

    public boolean isTest() {
        return test;
    }

    /** Mode test : le monde n'est plus proposé aux joueurs (seul le staff y entre). */
    public void setTest(boolean value) {
        test = value;
        beat();
    }

    /** Un joueur est arrivé (journal des mondes). */
    public void joined() {
        joins++;
    }

    public Camp camp() {
        return camp;
    }

    private void pregenerate(List<ChunkPos> chunks) {
        if (chunks.isEmpty()) {
            open();
            return;
        }
        plugin.getLogger().info("Prégénération de " + chunks.size() + " chunks...");
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        Runnable[] step = new Runnable[1];
        step[0] = () -> {
            int index = next.getAndIncrement();
            if (index >= chunks.size()) {
                return;
            }
            ChunkPos chunk = chunks.get(index);
            chunk.world().getChunkAtAsync(chunk.x(), chunk.z()).whenComplete((loaded, error) -> {
                int count = done.incrementAndGet();
                if (count % Math.max(1, chunks.size() / 10) == 0) {
                    plugin.getLogger().info("Prégénération : " + (count * 100 / chunks.size()) + " %");
                }
                if (count == chunks.size()) {
                    open();
                } else {
                    step[0].run();
                }
            });
        };
        for (int i = 0; i < IN_FLIGHT; i++) {
            step[0].run();
        }
    }

    private void open() {
        ready = true;
        plugin.getLogger().info("Monde ressource prêt : les joueurs sont acceptés.");
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new WorldReadyEvent(theme.id())));
    }
}
