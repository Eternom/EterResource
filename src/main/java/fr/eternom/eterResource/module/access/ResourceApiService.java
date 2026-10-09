package fr.eternom.eterResource.module.access;

import fr.eternom.eterResource.api.ResourceApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Consumer;

/** L'API d'EterResource (ResourceApi) : le plugin lui-même, vu de l'extérieur. */
public class ResourceApiService implements ResourceApi {

    private final JavaPlugin plugin;
    private final AccessService service;
    private final AccessRepository access;
    private final Consumer<Player> menu;

    public ResourceApiService(JavaPlugin plugin, AccessService service, AccessRepository access, Consumer<Player> menu) {
        this.plugin = plugin;
        this.service = service;
        this.access = access;
        this.menu = menu;
    }

    @Override
    public boolean isResourceServer() {
        return service.isWorldServer();
    }

    @Override
    public Access access(UUID player) {
        AccessRepository.Access found = access.get(player);
        return new Access(found.remainingMillis(), found.keys());
    }

    @Override
    public void addTime(UUID player, long millis) {
        if (millis > 0) {
            access.addTime(player, millis, Long.MAX_VALUE / 2);
            changed(player);
        }
    }

    @Override
    public void giveKeys(UUID player, int keys) {
        if (keys > 0) {
            access.giveKeys(player, keys);
            changed(player);
        }
    }

    @Override
    public void openMenu(Player player) {
        menu.accept(player);
    }

    /** Le joueur connecté ici voit son nouveau temps (menu, monde ressource). */
    private void changed(UUID uuid) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                service.timeChanged(online);
            }
        });
    }
}
