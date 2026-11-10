package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Le temps de monde ressource de chaque joueur, commun à tout le réseau. Chaque changement est une seule requête qui
 * vérifie elle-même ses conditions (clé disponible, plafond, temps suffisant) : un double clic ou deux serveurs en même
 * temps ne donnent jamais de temps en trop. Bloquant (base) : à appeler en tâche de fond.
 * - eterresource_access : temps restant (ms), clés, dernier jour des minutes gratuites, session ouverte (pour le
 *   remboursement si un monde plante) ;
 * - eterresource_history : l'historique du temps (achats, clés, minutes du jour, cadeaux, staff, temps passé) ;
 * - eterresource_recaps : le récapitulatif de la dernière sortie, montré à l'arrivée sur un autre serveur.
 */
public class AccessRepository {

    private static final String TABLE = "access";
    private static final String HISTORY = "history";
    private static final String RECAPS = "recaps";

    /** Temps restant et clés d'un joueur. */
    public record Access(long remainingMillis, int keys) {
        static final Access NONE = new Access(0, 0);
    }

    /** Une ligne de l'historique : temps ajouté (ou retiré, négatif), raison (clé de lang ou texte), date. */
    public record Entry(long deltaMillis, String reason, long at) {
    }

    private final Database database;
    private final String table;

    public AccessRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("remaining_ms", Column.Type.LONG).notNull(),
                Column.of("key_count", Column.Type.INT).notNull());
        database.addColumn(TABLE, Column.of("daily_day", Column.Type.LONG));
        database.addColumn(TABLE, Column.of("session_world", Column.Type.STRING).length(64));
        database.createTable(HISTORY,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("delta_ms", Column.Type.LONG).notNull(),
                Column.of("reason", Column.Type.STRING).length(96).notNull(),
                Column.of("at", Column.Type.LONG).notNull());
        database.createTable(RECAPS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("data", Column.Type.TEXT).notNull(),
                Column.of("at", Column.Type.LONG).notNull());
        database.execute("CREATE INDEX IF NOT EXISTS eterresource_history_uuid ON " + database.table(HISTORY) + " (uuid, at)");
        database.execute("DELETE FROM " + database.table(HISTORY) + " WHERE at < ?", System.currentTimeMillis() - 90L * 86_400_000L);
        this.table = database.table(TABLE);
    }

    public Access get(UUID player) {
        return database.getFirst(TABLE, Map.of("uuid", player))
                .map(row -> new Access(row.getLong("remaining_ms"), row.getInt("key_count")))
                .orElse(Access.NONE);
    }

    /** Ajoute millis, seulement si le total reste sous max ; false sinon (rien n'est changé). */
    public boolean addTime(UUID player, long millis, long max) {
        ensure(player);
        return database.execute("UPDATE " + table + " SET remaining_ms = remaining_ms + ? WHERE uuid = ? AND remaining_ms + ? <= ?",
                millis, player, millis, max) > 0;
    }

    /** Retire millis (paiement refusé, ou temps passé dans le monde), sans descendre sous zéro. */
    public void removeTime(UUID player, long millis) {
        database.execute("UPDATE " + table + " SET remaining_ms = GREATEST(remaining_ms - ?, 0) WHERE uuid = ?", millis, player);
    }

    /** Retire millis seulement s'il les a (cadeau) ; false sinon. */
    public boolean takeTime(UUID player, long millis) {
        return database.execute("UPDATE " + table + " SET remaining_ms = remaining_ms - ? WHERE uuid = ? AND remaining_ms >= ?",
                millis, player, millis) > 0;
    }

    /** Une clé contre millis de temps : false s'il n'a pas de clé ou si le total dépasserait max. */
    public boolean useKey(UUID player, long millis, long max) {
        return database.execute("UPDATE " + table + " SET key_count = key_count - 1, remaining_ms = remaining_ms + ?"
                + " WHERE uuid = ? AND key_count > 0 AND remaining_ms + ? <= ?", millis, player, millis, max) > 0;
    }

    public void giveKeys(UUID player, int keys) {
        ensure(player);
        database.execute("UPDATE " + table + " SET key_count = key_count + ? WHERE uuid = ?", keys, player);
    }

    /**
     * Les minutes gratuites du jour (une fois par jour, sur tout le réseau), sans dépasser max : renvoie le temps
     * vraiment ajouté (0 : déjà reçues aujourd'hui, ou plafond déjà atteint).
     */
    public long grantDaily(UUID player, long day, long millis, long max) {
        ensure(player);
        long before = get(player).remainingMillis();
        int changed = database.execute("UPDATE " + table + " SET remaining_ms = GREATEST(remaining_ms, LEAST(remaining_ms + ?, ?)),"
                + " daily_day = ? WHERE uuid = ? AND (daily_day IS NULL OR daily_day <> ?)", millis, max, day, player, day);
        return changed == 0 ? 0 : Math.max(0, get(player).remainingMillis() - before);
    }

    // ---------- Session (remboursement si un monde plante) ----------

    /** Le joueur est sur ce monde (null : il en est parti proprement). */
    public void setSession(UUID player, String world) {
        ensure(player);
        database.execute("UPDATE " + table + " SET session_world = ? WHERE uuid = ?", world, player);
    }

    /** Le monde d'une session restée ouverte (le monde a planté), et la referme ; vide sinon. */
    public Optional<String> takeOpenSession(UUID player) {
        Optional<String> world = database.getFirst(TABLE, Map.of("uuid", player)).map(row -> row.getString("session_world"));
        if (world.isEmpty() || world.get() == null) {
            return Optional.empty();
        }
        int closed = database.execute("UPDATE " + table + " SET session_world = NULL WHERE uuid = ? AND session_world = ?",
                player, world.get());
        return closed > 0 ? world : Optional.empty();
    }

    // ---------- Historique ----------

    public void log(UUID player, long deltaMillis, String reason) {
        if (deltaMillis == 0) {
            return;
        }
        database.insert(HISTORY, Map.of("uuid", player, "delta_ms", deltaMillis,
                "reason", reason.length() > 96 ? reason.substring(0, 96) : reason, "at", System.currentTimeMillis()));
    }

    public List<Entry> history(UUID player, int limit) {
        return database.query("SELECT delta_ms, reason, at FROM " + database.table(HISTORY) + " WHERE uuid = ? ORDER BY at DESC LIMIT "
                + Math.max(1, limit), player).stream()
                .map(row -> new Entry(row.getLong("delta_ms"), row.getString("reason"), row.getLong("at"))).toList();
    }

    // ---------- Récapitulatif de sortie ----------

    public void saveRecap(UUID player, String data) {
        database.set(RECAPS, Map.of("uuid", player, "data", data, "at", System.currentTimeMillis()), "uuid");
    }

    /** Le dernier récapitulatif (effacé une fois lu). */
    public Optional<String> takeRecap(UUID player) {
        Optional<String> data = database.getFirst(RECAPS, Map.of("uuid", player)).map(row -> row.getString("data"));
        data.ifPresent(found -> database.delete(RECAPS, Map.of("uuid", player)));
        return data;
    }

    /** La ligne du joueur existe (sans rien changer si elle est déjà là). */
    private void ensure(UUID player) {
        database.execute("INSERT IGNORE INTO " + table + " (uuid, remaining_ms, key_count) VALUES (?, 0, 0)", player);
    }
}
