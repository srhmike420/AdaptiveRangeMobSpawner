package hasjamon.block4block;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

public final class AdaptiveRangeMobSpawner extends JavaPlugin implements Listener {
    private static final int VANILLA_SPAWNER_RANGE = 16;
    private static final String PLAYER_PLACED_KEY = "player_placed";

    private int initialSpawnerRange;
    private int spawnerRangeHigh;
    private int spawnerRangeLow;
    private double tpsThresholdLow;
    private double tpsThresholdHigh;
    private long updateIntervalTicks;
    private boolean affectNaturallyGenerated;
    private boolean updatePreexistingSpawners;
    private int chunksPerTick;
    private boolean debugMode;
    private boolean onlyUpdateNearPlayers;
    private int playerChunkRadius;
    private Set<String> disabledWorlds = Set.of();

    private int currentSpawnerRange;
    private NamespacedKey playerPlacedKey;
    private BukkitTask tpsTask;

    @Override
    public void onEnable() {
        playerPlacedKey = new NamespacedKey(this, PLAYER_PLACED_KEY);
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        currentSpawnerRange = initialSpawnerRange;

        updateChunksOnStartup();
        restartTpsMonitor();

        getLogger().info("Adaptive Range Mob Spawner 26.2 enabled (Paper 26.2 / Java 25 target).");
        getLogger().info("Initial range=" + initialSpawnerRange + ", low=" + spawnerRangeLow +
                ", high=" + spawnerRangeHigh + ", TPS low=" + tpsThresholdLow +
                ", TPS high=" + tpsThresholdHigh + ".");
    }

    @Override
    public void onDisable() {
        if (tpsTask != null) {
            tpsTask.cancel();
            tpsTask = null;
        }
    }

    private void loadSettings() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        initialSpawnerRange = positive(config.getInt("initial-spawner-range", 64), 64);
        spawnerRangeHigh = positive(config.getInt("spawner-range-high", 128), 128);
        spawnerRangeLow = positive(config.getInt("spawner-range-low", 16), 16);
        tpsThresholdLow = config.getDouble("tps-threshold-low", 18.5D);
        tpsThresholdHigh = config.getDouble("tps-threshold-high", 19.5D);
        updateIntervalTicks = Math.max(20L, config.getLong("update-interval-ticks", 200L));
        affectNaturallyGenerated = config.getBoolean("affect-naturally-generated", false);
        updatePreexistingSpawners = config.getBoolean("update-preexisting-spawners", false);
        chunksPerTick = Math.max(1, config.getInt("chunks-per-tick", 5));
        debugMode = config.getBoolean("debug-mode", false);
        onlyUpdateNearPlayers = config.getBoolean("only-update-near-players", true);
        playerChunkRadius = Math.max(0, config.getInt("player-chunk-radius", 5));
        disabledWorlds = new HashSet<>(config.getStringList("disabled-worlds"));

        if (tpsThresholdLow > tpsThresholdHigh) {
            double swap = tpsThresholdLow;
            tpsThresholdLow = tpsThresholdHigh;
            tpsThresholdHigh = swap;
            getLogger().warning("tps-threshold-low was greater than tps-threshold-high; values were swapped in memory.");
        }
    }

    private int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private void restartTpsMonitor() {
        if (tpsTask != null) {
            tpsTask.cancel();
        }

        tpsTask = new BukkitRunnable() {
            @Override
            public void run() {
                evaluateTpsAndAdjust();
            }
        }.runTaskTimer(this, updateIntervalTicks, updateIntervalTicks);
    }

    private void evaluateTpsAndAdjust() {
        double currentTps = getCurrentTps();
        int newRange = currentSpawnerRange;

        if (currentTps <= tpsThresholdLow) {
            newRange = spawnerRangeLow;
        } else if (currentTps >= tpsThresholdHigh) {
            newRange = spawnerRangeHigh;
        }

        if (newRange == currentSpawnerRange) {
            logDebug("TPS=" + String.format("%.2f", currentTps) + "; range remains " + currentSpawnerRange);
            return;
        }

        currentSpawnerRange = newRange;
        logDebug("TPS=" + String.format("%.2f", currentTps) + "; changing range to " + newRange);
        updateAllSpawners(newRange);
    }

    private double getCurrentTps() {
        double[] tps = Bukkit.getTPS();
        if (tps.length == 0 || Double.isNaN(tps[0]) || Double.isInfinite(tps[0])) {
            return 20.0D;
        }
        return Math.min(20.0D, tps[0]);
    }

    private void updateChunksOnStartup() {
        final Chunk[] chunks = Bukkit.getWorlds().stream()
                .filter(world -> !disabledWorlds.contains(world.getName()))
                .flatMap(world -> java.util.Arrays.stream(world.getLoadedChunks()))
                .toArray(Chunk[]::new);

        new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                int processedThisTick = 0;
                while (index < chunks.length && processedThisTick < chunksPerTick) {
                    Chunk chunk = chunks[index++];
                    if (chunk.isLoaded()) {
                        updateSpawnersInChunk(chunk, currentSpawnerRange);
                    }
                    processedThisTick++;
                }

                if (index >= chunks.length) {
                    cancel();
                    logDebug("Startup scan complete: " + chunks.length + " loaded chunks considered.");
                }
            }
        }.runTaskTimer(this, 20L, 1L);
    }

    private void updateAllSpawners(int range) {
        if (onlyUpdateNearPlayers) {
            updateSpawnersNearPlayers(range);
            return;
        }

        int chunks = 0;
        for (World world : Bukkit.getWorlds()) {
            if (disabledWorlds.contains(world.getName())) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                updateSpawnersInChunk(chunk, range);
                chunks++;
            }
        }
        logDebug("Updated loaded spawners across " + chunks + " chunks.");
    }

    private void updateSpawnersNearPlayers(int range) {
        Set<ChunkKey> processed = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (disabledWorlds.contains(world.getName())) {
                continue;
            }

            Chunk center = player.getLocation().getChunk();
            for (int dx = -playerChunkRadius; dx <= playerChunkRadius; dx++) {
                for (int dz = -playerChunkRadius; dz <= playerChunkRadius; dz++) {
                    if ((dx * dx) + (dz * dz) > playerChunkRadius * playerChunkRadius) {
                        continue;
                    }

                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    ChunkKey key = new ChunkKey(world.getName(), x, z);
                    if (!processed.add(key) || !world.isChunkLoaded(x, z)) {
                        continue;
                    }

                    updateSpawnersInChunk(world.getChunkAt(x, z), range);
                }
            }
        }

        logDebug("Updated spawners in " + processed.size() + " loaded chunks near players.");
    }

    private void updateSpawnersInChunk(Chunk chunk, int range) {
        if (!chunk.isLoaded() || disabledWorlds.contains(chunk.getWorld().getName())) {
            return;
        }

        int updated = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof CreatureSpawner spawner && processSpawner(spawner, range)) {
                updated++;
            }
        }

        if (updated > 0) {
            logDebug("Updated " + updated + " spawner(s) in " + chunk.getWorld().getName() +
                    " [" + chunk.getX() + "," + chunk.getZ() + "].");
        }
    }

    private boolean processSpawner(CreatureSpawner spawner, int range) {
        PersistentDataContainer pdc = spawner.getPersistentDataContainer();
        boolean playerPlaced = pdc.has(playerPlacedKey, PersistentDataType.INTEGER);
        boolean shouldUseAdaptiveRange = playerPlaced || updatePreexistingSpawners || affectNaturallyGenerated;
        int desiredRange = shouldUseAdaptiveRange ? range : VANILLA_SPAWNER_RANGE;

        if (spawner.getRequiredPlayerRange() == desiredRange) {
            return false;
        }

        spawner.setRequiredPlayerRange(desiredRange);
        spawner.update();
        return true;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (disabledWorlds.contains(event.getWorld().getName())) {
            return;
        }
        updateSpawnersInChunk(event.getChunk(), currentSpawnerRange);
    }

    @EventHandler
    public void onSpawnerPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.SPAWNER || disabledWorlds.contains(block.getWorld().getName())) {
            return;
        }

        BlockState state = block.getState();
        if (!(state instanceof CreatureSpawner spawner)) {
            return;
        }

        spawner.getPersistentDataContainer().set(playerPlacedKey, PersistentDataType.INTEGER, 1);
        spawner.setRequiredPlayerRange(currentSpawnerRange);
        spawner.update();

        logDebug("Marked player-placed spawner at " + block.getWorld().getName() + " [" +
                block.getX() + "," + block.getY() + "," + block.getZ() + "] with range " + currentSpawnerRange + ".");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("adaptivespawner")) {
            return false;
        }

        if (!sender.hasPermission("block4block.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                loadSettings();
                restartTpsMonitor();
                sender.sendMessage("§aAdaptive Range Mob Spawner config reloaded.");
                evaluateTpsAndAdjust();
                return true;
            }
            case "debug" -> {
                debugMode = !debugMode;
                getConfig().set("debug-mode", debugMode);
                saveConfig();
                sender.sendMessage("§aDebug mode " + (debugMode ? "enabled" : "disabled") + ".");
                return true;
            }
            case "update" -> {
                updateAllSpawners(currentSpawnerRange);
                sender.sendMessage("§aUpdated loaded spawners to the current adaptive range where applicable.");
                return true;
            }
            default -> {
                sender.sendMessage("§eUsage: /adaptivespawner [reload|debug|update]");
                return true;
            }
        }
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§e===== Adaptive Range Mob Spawner =====");
        sender.sendMessage("§7Paper target: §f26.2");
        sender.sendMessage("§7Java target: §f25");
        sender.sendMessage("§7Current TPS: §f" + String.format("%.2f", getCurrentTps()));
        sender.sendMessage("§7Current range: §f" + currentSpawnerRange);
        sender.sendMessage("§7TPS thresholds: §flow=" + tpsThresholdLow + ", high=" + tpsThresholdHigh);
        sender.sendMessage("§7Ranges: §flow=" + spawnerRangeLow + ", high=" + spawnerRangeHigh);
        sender.sendMessage("§7Near players only: §f" + onlyUpdateNearPlayers);
        sender.sendMessage("§7Debug: §f" + debugMode);
    }

    private void logDebug(String message) {
        if (debugMode) {
            getLogger().info("[Debug] " + message);
        }
    }

    private static final class ChunkKey {
        private final String world;
        private final int x;
        private final int z;

        private ChunkKey(String world, int x, int z) {
            this.world = world;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChunkKey other)) return false;
            return x == other.x && z == other.z && world.equals(other.world);
        }

        @Override
        public int hashCode() {
            int result = world.hashCode();
            result = 31 * result + x;
            result = 31 * result + z;
            return result;
        }
    }
}
