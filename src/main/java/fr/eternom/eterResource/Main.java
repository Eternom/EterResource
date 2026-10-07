package fr.eternom.eterResource;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterResource.listeners.Commands;
import fr.eternom.eterResource.listeners.Events;
import fr.eternom.eterResource.module.access.AccessRepository;
import fr.eternom.eterResource.module.access.AccessService;
import fr.eternom.eterResource.module.bonus.JobBonuses;
import fr.eternom.eterResource.module.world.Sessions;
import fr.eternom.eterResource.module.world.WorldDirectory;
import fr.eternom.eterResource.module.world.WorldSetup;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Les mondes ressources, côté Paper. Partout (lobby, survie) : /ressource pour acheter du temps, utiliser une clé et
 * partir sur le monde le moins rempli. Sur un monde ressource (server-name qui commence par world-server-prefix,
 * serveurs créés par EterVelocityResource) : préparation du monde, temps qui s'écoule, bonus de métier.
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : joueurs par serveur et connect depuis 1.7.0. */
    private static final String REQUIRED_ETERLIB = "1.7.0";

    /** Préfixe des tables d'EterResource : eterresource_access, eterresource_worlds (et eterresource_servers, de l'orchestrateur). */
    private static final String TABLE_PREFIX = "eterresource_";
    /** Tables d'EterMarket, lues seulement (métier des joueurs). */
    private static final String MARKET_PREFIX = "etermarket_";

    private EterLib lib;
    private Messages messages;
    private AccessRepository access;
    private AccessService service;
    private WorldSetup world;
    private Sessions sessions;
    private JobBonuses bonuses;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        access = new AccessRepository(database);
        WorldDirectory worlds = new WorldDirectory(database);
        String prefix = getConfig().getString("world-server-prefix", "ressource").toLowerCase(Locale.ROOT);
        boolean worldServer = !prefix.isEmpty() && lib.getServerName().toLowerCase(Locale.ROOT).startsWith(prefix);
        service = new AccessService(this, lib, messages, access, worlds, worldServer);

        if (worldServer) {
            world = new WorldSetup(this, worlds, lib.getServerName());
            sessions = new Sessions(this, lib, messages, access, world);
            bonuses = new JobBonuses(this, lib.database(MARKET_PREFIX));
            service.onTimeChanged(sessions::reload);
            world.start();
            sessions.start();
            bonuses.start();
            getLogger().info("Monde ressource : " + lib.getServerName());
        }
        new Commands(this);
        new Events(this);
        service.start();
    }

    @Override
    public void onDisable() {
        // Les joueurs encore là quittent après l'arrêt des plugins : leur temps part en base maintenant
        if (sessions != null) {
            sessions.stop();
        }
    }

    public EterLib getLib() {
        return lib;
    }

    public Messages getMessages() {
        return messages;
    }

    public AccessRepository getAccess() {
        return access;
    }

    public AccessService getService() {
        return service;
    }

    /** null hors d'un monde ressource. */
    public Sessions getSessions() {
        return sessions;
    }

    /** null hors d'un monde ressource. */
    public JobBonuses getBonuses() {
        return bonuses;
    }
}
