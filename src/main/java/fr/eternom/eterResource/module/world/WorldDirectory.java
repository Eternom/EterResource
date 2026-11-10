package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Table eterresource_worlds : les mondes ressources qui tournent. Chacun y écrit toutes les 10 s son signe de vie,
 * s'il est prêt, son thème, ses joueurs, son TPS, son ouverture et sa fermeture prévue (close_at : EterVelocityResource
 * le vide à cette heure), et s'il est en mode test (réservé au staff). Un monde muet depuis 30 s, en test, ou qui ferme
 * dans moins de 5 min n'est plus proposé ; une ligne muette depuis 10 min est effacée ; l'orchestrateur efface aussi la
 * ligne d'un serveur qu'il supprime. Table eterresource_world_log : le journal des mondes passés. Bloquant (base).
 */
public class WorldDirectory {

    private static final String TABLE = "worlds";
    private static final String LOG = "world_log";
    /** Table de l'orchestrateur (EterVelocityResource), s'il est utilisé : on ne propose pas un serveur qu'il vide. */
    private static final String ORCHESTRATOR = "servers";
    private static final Duration ALIVE = Duration.ofSeconds(30);
    private static final Duration FORGOTTEN = Duration.ofMinutes(10);
    private static final Duration CLOSING_SOON = Duration.ofMinutes(5);

    /** Un monde ressource tel que l'annuaire le connaît. */
    public record WorldInfo(String name, String theme, int players, double tps, long openedAt, long closeAt) {
    }

    /** Ce qu'un monde écrit de lui-même. */
    public record Beat(boolean ready, String theme, int players, double tps, long openedAt, long closeAt, boolean test) {
    }

    /** Une ligne du journal des mondes. */
    public record LogEntry(String name, String theme, long openedAt, long closedAt, int peak, int joins) {
    }

    private final Database database;

    public WorldDirectory(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("ready", Column.Type.BOOLEAN).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
        database.addColumn(TABLE, Column.of("theme", Column.Type.STRING).length(32));
        database.addColumn(TABLE, Column.of("players", Column.Type.INT));
        database.addColumn(TABLE, Column.of("tps", Column.Type.DOUBLE));
        database.addColumn(TABLE, Column.of("opened_at", Column.Type.LONG));
        database.addColumn(TABLE, Column.of("close_at", Column.Type.LONG));
        database.addColumn(TABLE, Column.of("test", Column.Type.BOOLEAN));
        database.createTable(LOG,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("opened_at", Column.Type.LONG).primaryKey(),
                Column.of("theme", Column.Type.STRING).length(32),
                Column.of("closed_at", Column.Type.LONG),
                Column.of("peak", Column.Type.INT),
                Column.of("joins", Column.Type.INT));
    }

    /** Signe de vie de ce monde (côté monde ressource). */
    public void heartbeat(String server, Beat beat) {
        long now = System.currentTimeMillis();
        Map<String, Object> values = new HashMap<>();
        values.put("name", server);
        values.put("ready", beat.ready());
        values.put("last_seen", now);
        values.put("theme", beat.theme());
        values.put("players", beat.players());
        values.put("tps", beat.tps());
        values.put("opened_at", beat.openedAt());
        values.put("close_at", beat.closeAt());
        values.put("test", beat.test());
        database.set(TABLE, values, "name");
        database.execute("DELETE FROM " + database.table(TABLE) + " WHERE last_seen < ?", now - FORGOTTEN.toMillis());
    }

    /** Mondes prêts qui répondent, hors test et hors fermeture imminente, sauf ceux que l'orchestrateur vide. */
    public List<WorldInfo> open() {
        long now = System.currentTimeMillis();
        long since = now - ALIVE.toMillis();
        long closing = now + CLOSING_SOON.toMillis();
        String worlds = database.table(TABLE);
        String filter = " WHERE w.ready = TRUE AND w.last_seen >= ? AND (w.test IS NULL OR w.test = FALSE)"
                + " AND (w.close_at IS NULL OR w.close_at = 0 OR w.close_at > ?)";
        List<Row> rows;
        if (!orchestratorUsed()) {
            rows = database.query("SELECT w.* FROM " + worlds + " w" + filter, since, closing);
        } else {
            String servers = database.table(ORCHESTRATOR);
            rows = database.query("SELECT w.* FROM " + worlds + " w LEFT JOIN " + servers + " s ON s.name = w.name" + filter
                    + " AND (s.state IS NULL OR s.state = 'ACTIVE')", since, closing);
        }
        List<WorldInfo> infos = new ArrayList<>();
        for (Row row : rows) {
            infos.add(new WorldInfo(row.getString("name"), row.getString("theme") == null ? "" : row.getString("theme"),
                    row.get("players") == null ? 0 : row.getInt("players"), row.get("tps") == null ? 20 : row.getDouble("tps"),
                    row.get("opened_at") == null ? 0 : row.getLong("opened_at"), row.get("close_at") == null ? 0 : row.getLong("close_at")));
        }
        infos.sort((a, b) -> a.name().compareTo(b.name()));
        return infos;
    }

    private boolean orchestratorUsed() {
        return !database.query("SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                database.table(ORCHESTRATOR).replace("`", "")).isEmpty();
    }

    // ---------- Journal des mondes ----------

    /** Le monde s'ouvre (prêt) ou se met à jour (pic de joueurs, arrivées, fermeture). */
    public void log(String name, String theme, long openedAt, long closedAt, int peak, int joins) {
        database.execute("INSERT INTO " + database.table(LOG) + " (name, opened_at, theme, closed_at, peak, joins) VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE theme = ?, closed_at = ?, peak = GREATEST(peak, ?), joins = ?",
                name, openedAt, theme, closedAt, peak, joins, theme, closedAt, peak, joins);
    }

    public List<LogEntry> history(int limit) {
        return database.query("SELECT * FROM " + database.table(LOG) + " ORDER BY opened_at DESC LIMIT " + Math.max(1, limit)).stream()
                .map(row -> new LogEntry(row.getString("name"), row.getString("theme"), row.getLong("opened_at"),
                        row.get("closed_at") == null ? 0 : row.getLong("closed_at"), row.getInt("peak"), row.getInt("joins")))
                .toList();
    }
}
