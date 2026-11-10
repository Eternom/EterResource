package fr.eternom.eterResource.module.content;

import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;
import java.util.function.DoubleSupplier;

/**
 * Des minerais en plus à la génération (themes.<id>.ore-multiplier : 1 = rien en plus, 3 = le monde « filon ») :
 * quelques petits filons de plus par chunk, selon la profondeur, seulement dans la pierre.
 */
public class OrePopulator extends BlockPopulator {

    private final DoubleSupplier multiplier;

    public OrePopulator(DoubleSupplier multiplier) {
        this.multiplier = multiplier;
    }

    @Override
    public void populate(@NotNull WorldInfo world, @NotNull Random random, int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        double extra = multiplier.getAsDouble() - 1;
        if (extra <= 0) {
            return;
        }
        int veins = (int) Math.floor(extra * 4) + (random.nextDouble() < (extra * 4) % 1 ? 1 : 0);
        for (int vein = 0; vein < veins; vein++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            int y = world.getMinHeight() + 4 + random.nextInt(Math.max(1, 100 - world.getMinHeight()));
            Material ore = ore(y, random);
            for (int i = 0; i < 3 + random.nextInt(5); i++) {
                int bx = x + random.nextInt(3) - 1;
                int by = y + random.nextInt(3) - 1;
                int bz = z + random.nextInt(3) - 1;
                if (!region.isInRegion(bx, by, bz)) {
                    continue;
                }
                Material type = region.getType(bx, by, bz);
                if (type == Material.STONE) {
                    region.setType(bx, by, bz, ore);
                } else if (type == Material.DEEPSLATE) {
                    region.setType(bx, by, bz, deepslate(ore));
                }
            }
        }
    }

    private static Material ore(int y, Random random) {
        double roll = random.nextDouble();
        if (y < -16) {
            return roll < 0.15 ? Material.DIAMOND_ORE : roll < 0.45 ? Material.REDSTONE_ORE : roll < 0.7 ? Material.GOLD_ORE : Material.IRON_ORE;
        }
        if (y < 32) {
            return roll < 0.05 ? Material.DIAMOND_ORE : roll < 0.3 ? Material.GOLD_ORE : roll < 0.45 ? Material.LAPIS_ORE : Material.IRON_ORE;
        }
        return roll < 0.5 ? Material.COAL_ORE : roll < 0.8 ? Material.IRON_ORE : Material.COPPER_ORE;
    }

    private static Material deepslate(Material ore) {
        Material deep = Material.getMaterial("DEEPSLATE_" + ore.name());
        return deep == null ? ore : deep;
    }
}
