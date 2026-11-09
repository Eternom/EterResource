package fr.eternom.eterResource.module.bonus;

import fr.eternom.eterLib.EterLib;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les avantages de métier en jeu, selon le rang (EterJob > perks) : effets, abattage d'arbre (et pousse replantée,
 * pommes), petits filons d'un coup et fonte directe, récoltes multipliées (replantées, houe en zone), minerais et butin
 * en plus, XP et têtes des créatures, trésors et pêche plus rapide. Chaque bloc cassé d'un coup passe par son propre
 * BlockBreakEvent : les protections s'appliquent, et les quêtes d'EterJob le comptent.
 */
public class BonusListener implements Listener {

    /** Bûches abattues d'un coup au plus. */
    private static final int MAX_LOGS = 64;
    /** Minerais qui profitent des avantages du mineur (pas les débris antiques : la netherite reste rare). */
    private static final List<Tag<Material>> ORES = List.of(Tag.COAL_ORES, Tag.IRON_ORES, Tag.COPPER_ORES, Tag.GOLD_ORES,
            Tag.REDSTONE_ORES, Tag.LAPIS_ORES, Tag.DIAMOND_ORES, Tag.EMERALD_ORES);
    private static final Map<Material, Material> SMELTED = Map.of(Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT, Material.RAW_COPPER, Material.COPPER_INGOT);
    private static final Map<Material, Material> SEEDS = Map.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART);
    private static final Map<EntityType, Material> HEADS = Map.of(EntityType.ZOMBIE, Material.ZOMBIE_HEAD,
            EntityType.SKELETON, Material.SKELETON_SKULL, EntityType.CREEPER, Material.CREEPER_HEAD, EntityType.PIGLIN, Material.PIGLIN_HEAD,
            EntityType.WITHER_SKELETON, Material.WITHER_SKELETON_SKULL);
    private static final List<Material> TREASURES = List.of(Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL,
            Material.ENCHANTED_BOOK, Material.BOW, Material.FISHING_ROD);

    private final JavaPlugin plugin;
    private final JobBonuses bonuses;
    /** Pendant un abattage, un filon ou une récolte en zone : nos propres BlockBreakEvent ne relancent rien. */
    private boolean chain;
    /**
     * Minerais posés par un joueur, cassés ce tick : pas de bonus (sinon : récupérer au Toucher de soie, reposer, recasser
     * à la Fortune, en boucle). La note « posé » d'EterLib disparaît à la casse, avant BlockDropItemEvent : on la retient ici.
     */
    private final Set<Block> placedOres = new HashSet<>();

    public BonusListener(JavaPlugin plugin, JobBonuses bonuses) {
        this.plugin = plugin;
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (chain) {
            return; // un bloc cassé par nous-mêmes
        }
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        if (EterLib.get().getPlacedBlocks().isPlaced(origin)) {
            rememberPlacedOre(origin);
            return; // bloc posé (maison, décor) : jamais d'avantage
        }
        if (player.isSneaking() || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Optional<JobBonuses.Bonus> bonus = bonuses.of(player);
        if (bonus.isEmpty()) {
            return;
        }
        Material tool = player.getInventory().getItemInMainHand().getType();
        if (Tag.LOGS.isTagged(origin.getType()) && Tag.ITEMS_AXES.isTagged(tool) && bonus.get().treeFelling()) {
            fell(player, origin, bonus.get());
        } else if (isOre(origin.getType()) && Tag.ITEMS_PICKAXES.isTagged(tool) && bonus.get().value("vein-mining") > 0) {
            breakAll(player, connected(origin, (int) bonus.get().value("vein-mining") - 1, BonusListener::sameOre), Tag.ITEMS_PICKAXES);
        } else if (origin.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge()
                && Tag.ITEMS_HOES.isTagged(tool) && bonus.get().value("hoe-area") > 0) {
            breakAll(player, ripeAround(origin, (int) bonus.get().value("hoe-area")), Tag.ITEMS_HOES);
        }
    }

    /** Bûcheron : les bûches du même bois qui touchent tombent ; la pousse est replantée (sapling-replant). */
    private void fell(Player player, Block origin, JobBonuses.Bonus bonus) {
        Material wood = origin.getType();
        Block ground = origin.getRelative(BlockFace.DOWN);
        breakAll(player, connected(origin, MAX_LOGS, (a, b) -> b.getType() == a), Tag.ITEMS_AXES);
        if (bonus.has("sapling-replant") && Tag.DIRT.isTagged(ground.getType())) {
            Material sapling = Material.matchMaterial(wood.name().replace("STRIPPED_", "").replace("_LOG", "_SAPLING")
                    .replace("_STEM", "_FUNGUS"));
            if (sapling != null && sapling.isBlock()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (origin.getType().isAir()) {
                        origin.setType(sapling);
                    }
                });
            }
        }
    }

    /** Casse chaque bloc avec l'outil en main, en passant par son propre BlockBreakEvent (protections, quêtes). */
    private void breakAll(Player player, List<Block> blocks, Tag<Material> tools) {
        for (Block block : blocks) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (!tools.isTagged(tool.getType())) {
                return; // outil cassé
            }
            BlockBreakEvent each = new BlockBreakEvent(block, player);
            chain = true;
            try {
                Bukkit.getPluginManager().callEvent(each);
            } finally {
                chain = false;
            }
            if (each.isCancelled()) {
                continue;
            }
            block.breakNaturally(tool, true);
            player.damageItemStack(EquipmentSlot.HAND, 1);
        }
    }

    /** Fermier : récolte multipliée (crop-multiplier), culture replantée (replant). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropDrop(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        boolean crop = Tag.CROPS.isTagged(state.getType()) || state.getType() == Material.NETHER_WART;
        if (!crop || !(state.getBlockData() instanceof Ageable age) || age.getAge() < age.getMaximumAge()) {
            return;
        }
        Optional<JobBonuses.Bonus> bonus = bonuses.of(event.getPlayer());
        if (bonus.isEmpty()) {
            return;
        }
        int multiplier = bonus.get().cropMultiplier();
        if (multiplier > 1) {
            for (Item item : event.getItems()) {
                ItemStack stack = item.getItemStack();
                stack.setAmount(Math.min(stack.getAmount() * multiplier, stack.getMaxStackSize()));
                item.setItemStack(stack);
            }
        }
        Material seed = SEEDS.get(state.getType());
        if (bonus.get().has("replant") && seed != null && takeOne(event.getItems(), seed)) {
            Block block = event.getBlock();
            Material type = state.getType();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (block.getType().isAir()) {
                    block.setType(type); // âge 0
                }
            });
        }
    }

    /** Mineur : minerais en plus (ore-drop-bonus), fer, or et cuivre fondus directement (auto-smelt). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOreDrop(BlockDropItemEvent event) {
        Material ore = event.getBlockState().getType();
        if (!isOre(ore) || placedOres.contains(event.getBlock())) {
            return;
        }
        Optional<JobBonuses.Bonus> bonus = bonuses.of(event.getPlayer());
        if (bonus.isEmpty()) {
            return;
        }
        double extra = bonus.get().oreDropBonus();
        boolean smelt = bonus.get().has("auto-smelt");
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            if (stack.getType() == ore) {
                continue; // Toucher de soie : le minerai entier, sans bonus
            }
            if (extra > 0) {
                stack.setAmount(withBonus(stack, extra));
            }
            Material smelted = smelt ? SMELTED.get(stack.getType()) : null;
            if (smelted != null) {
                stack = stack.withType(smelted);
            }
            item.setItemStack(stack);
        }
    }

    /** Bûcheron : une chance de pomme en plus par bûche (apple-chance). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLogDrop(BlockDropItemEvent event) {
        if (!Tag.LOGS.isTagged(event.getBlockState().getType())) {
            return;
        }
        double chance = bonuses.of(event.getPlayer()).map(bonus -> bonus.value("apple-chance")).orElse(0.0);
        if (chance > 0 && ThreadLocalRandom.current().nextDouble() < chance) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), ItemStack.of(Material.APPLE));
        }
    }

    /** Chasseur : butin en plus (mob-drop-bonus), XP multipliée (xp-multiplier), une chance de tête (head-chance). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (entity instanceof Player || killer == null) {
            return;
        }
        Optional<JobBonuses.Bonus> bonus = bonuses.of(killer);
        if (bonus.isEmpty()) {
            return;
        }
        double extra = bonus.get().mobDropBonus();
        if (extra > 0) {
            for (ItemStack drop : event.getDrops()) {
                drop.setAmount(withBonus(drop, extra));
            }
        }
        double xp = bonus.get().value("xp-multiplier");
        if (xp > 1) {
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * xp));
        }
        Material head = HEADS.get(entity.getType());
        if (head != null && ThreadLocalRandom.current().nextDouble() < bonus.get().value("head-chance")) {
            event.getDrops().add(ItemStack.of(head));
        }
    }

    /** Pêcheur : attente plus courte (lure : 5 s de moins par niveau), une chance de trésor (treasure-bonus). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Optional<JobBonuses.Bonus> bonus = bonuses.of(event.getPlayer());
        if (bonus.isEmpty()) {
            return;
        }
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            int lure = (int) bonus.get().value("lure");
            FishHook hook = event.getHook();
            if (lure > 0) {
                hook.setMinWaitTime(Math.max(20, hook.getMinWaitTime() - lure * 100));
                hook.setMaxWaitTime(Math.max(hook.getMinWaitTime() + 20, hook.getMaxWaitTime() - lure * 100));
            }
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item caught
                && ThreadLocalRandom.current().nextDouble() < bonus.get().value("treasure-bonus")) {
            Material treasure = TREASURES.get(ThreadLocalRandom.current().nextInt(TREASURES.size()));
            ItemStack stack = ItemStack.of(treasure == Material.ENCHANTED_BOOK ? Material.BOOK : treasure);
            caught.setItemStack(treasure == Material.ENCHANTED_BOOK
                    ? Bukkit.getItemFactory().enchantWithLevels(stack, 30, true, ThreadLocalRandom.current()) : stack);
        }
    }

    /** Quantité de stack augmentée de bonus (0.5 = +50 %), la partie décimale tirée au hasard, sans dépasser une pile. */
    private static int withBonus(ItemStack stack, double bonus) {
        double extra = stack.getAmount() * bonus;
        int whole = (int) extra;
        int amount = stack.getAmount() + whole + (ThreadLocalRandom.current().nextDouble() < extra - whole ? 1 : 0);
        return Math.min(amount, stack.getMaxStackSize());
    }

    private static boolean takeOne(List<Item> items, Material material) {
        for (Item item : items) {
            ItemStack stack = item.getItemStack();
            if (stack.getType() == material) {
                if (stack.getAmount() <= 1) {
                    items.remove(item); // pas encore apparu : il ne tombera pas
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                    item.setItemStack(stack);
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isOre(Material material) {
        return material == Material.NETHER_QUARTZ_ORE || ORES.stream().anyMatch(tag -> tag.isTagged(material));
    }

    /** Le même minerai, ou sa version des abîmes. */
    private static boolean sameOre(Material origin, Block other) {
        return isOre(other.getType()) && family(other.getType()) == family(origin);
    }

    private static Material family(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") ? Optional.ofNullable(Material.getMaterial(name.substring(10))).orElse(material) : material;
    }

    /** Blocs reliés à origin (diagonales comprises), naturels, acceptés par same, max au plus, sans origin. */
    private static List<Block> connected(Block origin, int max, java.util.function.BiPredicate<Material, Block> same) {
        Material type = origin.getType();
        Set<Block> seen = new HashSet<>(List.of(origin));
        Deque<Block> queue = new ArrayDeque<>(List.of(origin));
        List<Block> found = new ArrayList<>();
        while (!queue.isEmpty() && found.size() < max) {
            Block current = queue.poll();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Block next = current.getRelative(x, y, z);
                        if (found.size() < max && seen.add(next) && same.test(type, next)
                                && !EterLib.get().getPlacedBlocks().isPlaced(next)) {
                            found.add(next);
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Les cultures mûres autour (rayon radius, même hauteur), sans origin. */
    private static List<Block> ripeAround(Block origin, int radius) {
        List<Block> ripe = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Block block = origin.getRelative(x, 0, z);
                if ((x != 0 || z != 0) && block.getBlockData() instanceof Ageable crop && crop.getAge() >= crop.getMaximumAge()
                        && (Tag.CROPS.isTagged(block.getType()) || block.getType() == Material.NETHER_WART)) {
                    ripe.add(block);
                }
            }
        }
        return ripe;
    }

    private void rememberPlacedOre(Block block) {
        if (placedOres.add(block) && placedOres.size() == 1) {
            Bukkit.getScheduler().runTask(plugin, placedOres::clear);
        }
    }
}
