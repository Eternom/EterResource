package fr.eternom.eterResource.listeners;

import fr.eternom.eterResource.Main;
import fr.eternom.eterResource.module.bonus.BonusListener;
import org.bukkit.event.Listener;

public class Events {

    public Events(Main main) {
        register(main, new fr.eternom.eterResource.module.access.AccessEvents(main, main.getService(), main.getAccess(), main.getMessages()));
        // Seulement sur un monde ressource : ailleurs, EterResource ne fait que l'accès (/resource)
        if (main.getSessions() != null) {
            register(main, main.getSessions());
            register(main, main.getCamp());
            register(main, new BonusListener(main, main.getBonuses()));
        }
    }

    private static void register(Main main, Listener listener) {
        main.getServer().getPluginManager().registerEvents(listener, main);
    }
}
