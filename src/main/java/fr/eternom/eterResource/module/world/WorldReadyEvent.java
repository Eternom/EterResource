package fr.eternom.eterResource.module.world;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Le monde ressource est prêt (prégénération finie) : structures, gisements et annonce peuvent se faire. */
public class WorldReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String theme;

    public WorldReadyEvent(String theme) {
        this.theme = theme;
    }

    public String theme() {
        return theme;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
