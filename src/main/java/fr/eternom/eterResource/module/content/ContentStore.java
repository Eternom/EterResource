package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.helper.ranking.RankingSource;
import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les données du contenu des mondes ressources, communes au réseau. Bloquant (base).
 * - eterresource_bags : le sac de retour de chaque joueur (objets complets : enchantements, noms...) ;
 * - eterresource_stats : blocs récoltés par semaine (classement EterLib « resource_week »).
 */
public class ContentStore implements RankingSource {

    private static final String BAGS = "bags";
    private static final String STATS = "stats";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final Database database;

    public ContentStore(Database database) {
        this.database = database;
        database.createTable(BAGS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("data", Column.Type.TEXT).notNull());
        database.createTable(STATS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("week", Column.Type.LONG).primaryKey(),
                Column.of("blocks", Column.Type.LONG).notNull());
        database.execute("DELETE FROM " + database.table(STATS) + " WHERE week < ?", week() - 8 * 7);
    }

    // ---------- Sac de retour ----------

    /** Le contenu du sac (cases vides comprises), de la taille donnée. */
    public ItemStack[] bag(UUID player, int size) {
        ItemStack[] items = new ItemStack[size];
        database.getFirst(BAGS, Map.of("uuid", player)).ifPresent(row -> {
            String[] parts = row.getString("data").split(";", -1);
            for (int i = 0; i < parts.length && i < size; i++) {
                if (!parts[i].isEmpty()) {
                    try {
                        items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[i]));
                    } catch (RuntimeException ignored) {
                        // objet illisible (version) : perdu plutôt que de bloquer le sac
                    }
                }
            }
        });
        return items;
    }

    public void saveBag(UUID player, ItemStack[] items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack item : items) {
            parts.add(item == null || item.isEmpty() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        database.set(BAGS, Map.of("uuid", player, "data", String.join(";", parts)), "uuid");
    }

    // ---------- Statistiques de la semaine ----------

    public static long week() {
        return LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
    }

    public void addBlocks(UUID player, long blocks) {
        if (blocks <= 0) {
            return;
        }
        database.execute("INSERT INTO " + database.table(STATS) + " (uuid, week, blocks) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE blocks = blocks + ?", player, week(), blocks, blocks);
    }

    @Override
    public String id() {
        return "resource_week";
    }

    @Override
    public List<Ranked> top(int limit) {
        return database.query("SELECT uuid, blocks FROM " + database.table(STATS) + " WHERE week = ? ORDER BY blocks DESC LIMIT "
                + Math.max(1, limit), week()).stream().map(row -> new Ranked(row.getUUID("uuid"), row.getLong("blocks"))).toList();
    }

    @Override
    public long[] of(UUID player) {
        long mine = database.getFirst(STATS, Map.of("uuid", player, "week", week())).map(row -> row.getLong("blocks")).orElse(0L);
        if (mine <= 0) {
            return new long[]{0, 0};
        }
        long above = database.query("SELECT COUNT(*) AS above FROM " + database.table(STATS) + " WHERE week = ? AND blocks > ?",
                week(), mine).getFirst().getLong("above");
        return new long[]{above + 1, mine};
    }
}
