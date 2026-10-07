package fr.eternom.eterResource.module.world;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Table eterresource_worlds : les mondes ressources qui tournent. Chacun y écrit son signe de vie toutes les 10 s et
 * s'il est prêt (prégénération finie). Un monde muet depuis 30 s n'est plus proposé, une ligne muette depuis 10 min
 * est effacée ; l'orchestrateur efface aussi la ligne d'un serveur qu'il supprime. Bloquant (base).
 */
public class WorldDirectory {

    private static final String TABLE = "worlds";
    /** Table de l'orchestrateur (EterVelocityResource), s'il est utilisé : on ne propose pas un serveur qu'il vide. */
    private static final String ORCHESTRATOR = "servers";
    private static final Duration ALIVE = Duration.ofSeconds(30);
    private static final Duration FORGOTTEN = Duration.ofMinutes(10);

    private final Database database;

    public WorldDirectory(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("name", Column.Type.STRING).length(64).primaryKey(),
                Column.of("ready", Column.Type.BOOLEAN).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
    }

    /** Signe de vie de ce monde (côté monde ressource). */
    public void heartbeat(String server, boolean ready) {
        long now = System.currentTimeMillis();
        database.set(TABLE, Map.of("name", server, "ready", ready, "last_seen", now), "name");
        database.execute("DELETE FROM " + database.table(TABLE) + " WHERE last_seen < ?", now - FORGOTTEN.toMillis());
    }

    /** Mondes prêts qui répondent, sauf ceux que l'orchestrateur est en train de vider ou de créer. */
    public List<String> open() {
        long since = System.currentTimeMillis() - ALIVE.toMillis();
        String worlds = database.table(TABLE);
        if (!orchestratorUsed()) {
            return names(database.query("SELECT name FROM " + worlds + " WHERE ready = TRUE AND last_seen >= ?", since));
        }
        String servers = database.table(ORCHESTRATOR);
        return names(database.query("SELECT w.name FROM " + worlds + " w LEFT JOIN " + servers + " s ON s.name = w.name"
                + " WHERE w.ready = TRUE AND w.last_seen >= ? AND (s.state IS NULL OR s.state = 'ACTIVE')", since));
    }

    private boolean orchestratorUsed() {
        return !database.query("SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                database.table(ORCHESTRATOR).replace("`", "")).isEmpty();
    }

    private static List<String> names(List<Row> rows) {
        return rows.stream().map(row -> row.getString("name")).sorted().toList();
    }
}
