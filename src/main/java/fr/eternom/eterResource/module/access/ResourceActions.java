package fr.eternom.eterResource.module.access;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.action.Actions;
import fr.eternom.eterLib.module.action.Actions.Argument;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Les actions d'EterResource dans EterLib (PNJ, portails, menus, récompenses de quêtes) :
 * <pre>
 *   eterresource:menu              le menu /resource (au lobby : un PNJ ou un portail vers les mondes ressources)
 *   eterresource:go                partir sur le monde ouvert le moins rempli
 *   eterresource:addtime <minutes> ajouter du temps (récompense ; sans plafond)
 * </pre>
 * Sur un monde ressource s'y ajoutent celles du camp (sac de retour, marchand, carnet) : ResourceContent.
 */
public final class ResourceActions {

    private ResourceActions() {
    }

    public static void register(JavaPlugin plugin, AccessService service, AccessRepository access, Messages messages, BackButton back) {
        Actions actions = EterLib.get().getActions();
        actions.register(plugin, "eterresource:menu", "Le menu des mondes ressources", List.of(),
                (player, args) -> AccessMenu.open(plugin, service, messages, back, player));
        actions.register(plugin, "eterresource:go", "Partir sur le monde ressource le moins rempli", List.of(),
                (player, args) -> service.load(player, current -> service.go(player, current)));
        actions.register(plugin, "eterresource:addtime", "Ajoute du temps de monde ressource (récompense)",
                List.of(Argument.required("minutes")), (player, args) -> addTime(plugin, service, access, messages, player, args.getFirst()));
    }

    private static void addTime(JavaPlugin plugin, AccessService service, AccessRepository access, Messages messages, Player player,
                                String text) {
        int minutes;
        try {
            minutes = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("eterresource:addtime : nombre illisible " + text);
            return;
        }
        if (minutes <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long millis = Duration.ofMinutes(minutes).toMillis();
        Tasks.async(plugin, player, () -> {
            access.addTime(uuid, millis, Long.MAX_VALUE / 2);
            access.log(uuid, millis, "reward");
            return true;
        }, done -> {
            messages.send(player, "access.reward", "time", service.formatMillis(player, millis));
            service.timeChanged(player);
        }, () -> messages.send(player, "error.generic"));
    }
}
