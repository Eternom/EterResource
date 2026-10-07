package fr.eternom.eterResource.module.bonus;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Les bonus de métier en jeu : effets, abattage d'arbre, récoltes doublées, butin des monstres. */
public class BonusListener implements Listener {

    /** Bûches abattues d'un coup au plus. */
    private static final int MAX_LOGS = 64;

    private final JobBonuses bonuses;

    public BonusListener(JobBonuses bonuses) {
        this.bonuses = bonuses;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bonuses.join(event.getPlayer());
    }

    /** LOWEST : avant EterSync (MONITOR), qui enregistre les effets du joueur pour le serveur suivant. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        bonuses.quit(event.getPlayer());
    }

    /** Bûcheron : une bûche cassée à la hache (sans s'accroupir) abat les bûches du même bois qui la touchent. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        if (!Tag.LOGS.isTagged(origin.getType()) || player.isSneaking() || player.getGameMode() != GameMode.SURVIVAL
                || !Tag.ITEMS_AXES.isTagged(player.getInventory().getItemInMainHand().getType())
                || bonuses.of(player).filter(JobBonuses.Bonus::treeFelling).isEmpty()) {
            return;
        }
        for (Block log : connectedLogs(origin)) {
            ItemStack axe = player.getInventory().getItemInMainHand();
            if (!Tag.ITEMS_AXES.isTagged(axe.getType())) {
                return; // hache cassée
            }
            log.breakNaturally(axe, true);
            player.damageItemStack(EquipmentSlot.HAND, 1);
        }
    }

    /** Fermier : les cultures mûres récoltées donnent crop-multiplier fois plus. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropDrop(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        boolean crop = Tag.CROPS.isTagged(state.getType()) || state.getType() == Material.NETHER_WART;
        if (!crop || !(state.getBlockData() instanceof Ageable age) || age.getAge() < age.getMaximumAge()) {
            return;
        }
        int multiplier = bonuses.of(event.getPlayer()).map(JobBonuses.Bonus::cropMultiplier).orElse(1);
        if (multiplier <= 1) {
            return;
        }
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            stack.setAmount(Math.min(stack.getAmount() * multiplier, stack.getMaxStackSize()));
            item.setItemStack(stack);
        }
    }

    /** Chasseur : le butin des monstres et animaux qu'il tue augmente de mob-drop-bonus (0.5 = +50 %, arrondi au hasard). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (entity instanceof Player || killer == null) {
            return;
        }
        double bonus = bonuses.of(killer).map(JobBonuses.Bonus::mobDropBonus).orElse(0.0);
        if (bonus <= 0) {
            return;
        }
        for (ItemStack drop : event.getDrops()) {
            double extra = drop.getAmount() * bonus;
            int whole = (int) extra;
            int amount = drop.getAmount() + whole + (ThreadLocalRandom.current().nextDouble() < extra - whole ? 1 : 0);
            drop.setAmount(Math.min(amount, drop.getMaxStackSize()));
        }
    }

    /** Bûches du même bois reliées à origin (diagonales comprises), sans origin. */
    private static List<Block> connectedLogs(Block origin) {
        Material wood = origin.getType();
        Set<Block> seen = new HashSet<>(List.of(origin));
        Deque<Block> queue = new ArrayDeque<>(List.of(origin));
        List<Block> logs = new ArrayList<>();
        while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
            Block current = queue.poll();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block next = current.getRelative(x, y, z);
                        if (next.getType() == wood && seen.add(next) && logs.size() < MAX_LOGS) {
                            logs.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return logs;
    }
}
