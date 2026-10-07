package fr.eternom.eterResource.module.world;

import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prépare les mondes d'un serveur ressource (surface, Nether, End, générés neufs) : on garde son inventaire en
 * mourant, pas de PvP, une bordure par dimension, puis la prégénération autour du spawn. Le serveur n'accepte les
 * joueurs (ready) qu'une fois la prégénération finie ; son signe de vie part toutes les 10 s (WorldDirectory).
 */
public class WorldSetup {

    private static final long HEARTBEAT_TICKS = 10 * 20;
    /** Chunks générés en même temps : assez pour aller vite, sans bloquer le serveur. */
    private static final int IN_FLIGHT = 8;

    private record ChunkPos(World world, int x, int z) {
    }

    private final JavaPlugin plugin;
    private final WorldDirectory directory;
    private final String serverName;
    private volatile boolean ready;

    public WorldSetup(JavaPlugin plugin, WorldDirectory directory, String serverName) {
        this.plugin = plugin;
        this.directory = directory;
        this.serverName = serverName;
    }

    public void start() {
        FileConfiguration config = plugin.getConfig();
        List<ChunkPos> chunks = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            String dimension = world.getEnvironment().name().toLowerCase(Locale.ROOT);
            world.setGameRule(GameRules.KEEP_INVENTORY, true);
            world.setGameRule(GameRules.PVP, false);
            Location spawn = world.getSpawnLocation();
            double border = config.getDouble("world.border." + dimension, 0);
            if (border > 0) {
                world.getWorldBorder().setCenter(spawn);
                world.getWorldBorder().setSize(border);
            }
            int radius = config.getInt("world.pregenerate." + dimension, 0) >> 4;
            int centerX = spawn.getBlockX() >> 4;
            int centerZ = spawn.getBlockZ() >> 4;
            List<ChunkPos> around = new ArrayList<>();
            for (int x = -radius; x <= radius && radius > 0; x++) {
                for (int z = -radius; z <= radius; z++) {
                    around.add(new ChunkPos(world, centerX + x, centerZ + z));
                }
            }
            // Du centre vers l'extérieur : le spawn est prêt en premier
            around.sort(Comparator.comparingInt(chunk -> Math.max(Math.abs(chunk.x() - centerX), Math.abs(chunk.z() - centerZ))));
            chunks.addAll(around);
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                directory.heartbeat(serverName, ready);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Signe de vie du monde ressource non écrit : " + e.getMessage());
            }
        }, 0, HEARTBEAT_TICKS);
        pregenerate(chunks);
    }

    /** Prégénération finie : les joueurs sont acceptés. */
    public boolean isReady() {
        return ready;
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
    }
}
