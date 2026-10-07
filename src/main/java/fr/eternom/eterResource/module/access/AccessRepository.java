package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.Map;
import java.util.UUID;

/**
 * Table eterresource_access : le temps de monde ressource qui reste à chaque joueur (en millisecondes) et ses clés.
 * Commune à tout le réseau ; chaque changement est une seule requête qui vérifie elle-même ses conditions (clé
 * disponible, plafond), pour qu'un double clic ou deux serveurs en même temps ne donnent jamais de temps en trop.
 * Bloquant (base) : à appeler en tâche de fond.
 */
public class AccessRepository {

    private static final String TABLE = "access";

    /** Temps restant et clés d'un joueur. */
    public record Access(long remainingMillis, int keys) {
        static final Access NONE = new Access(0, 0);
    }

    private final Database database;
    private final String table;

    public AccessRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("remaining_ms", Column.Type.LONG).notNull(),
                Column.of("key_count", Column.Type.INT).notNull());
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

    /** Une clé contre millis de temps : false s'il n'a pas de clé ou si le total dépasserait max. */
    public boolean useKey(UUID player, long millis, long max) {
        return database.execute("UPDATE " + table + " SET key_count = key_count - 1, remaining_ms = remaining_ms + ?"
                + " WHERE uuid = ? AND key_count > 0 AND remaining_ms + ? <= ?", millis, player, millis, max) > 0;
    }

    public void giveKeys(UUID player, int keys) {
        ensure(player);
        database.execute("UPDATE " + table + " SET key_count = key_count + ? WHERE uuid = ?", keys, player);
    }

    /** La ligne du joueur existe (sans rien changer si elle est déjà là). */
    private void ensure(UUID player) {
        database.execute("INSERT IGNORE INTO " + table + " (uuid, remaining_ms, key_count) VALUES (?, 0, 0)", player);
    }
}
