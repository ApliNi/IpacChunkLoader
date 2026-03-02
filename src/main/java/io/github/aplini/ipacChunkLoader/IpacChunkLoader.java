package io.github.aplini.ipacChunkLoader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bukkit.persistence.PersistentDataType.LONG;
import static org.bukkit.persistence.PersistentDataType.INTEGER;

public final class IpacChunkLoader extends JavaPlugin implements Listener, CommandExecutor {

    private Pattern pattern;
    private String displayFormat;
    private String expiryFormat;
    private String defaultId;
    private int maxHours;
    private int addHours;
    private int maxRadius;
    private int maxCount;
    private int warnHours;
    private boolean persistence;
    private List<String> warnCommands;
    private Plugin plugin;

    private NamespacedKey KEY_RADIUS;
    private NamespacedKey KEY_EXPIRY;
    private NamespacedKey KEY_WARNED;
    private NamespacedKey KEY_ID;

    // 内存追踪：正在工作的加载器 UUID 及其当前加载的区块集合
    private final Map<UUID, Set<Long>> activeLoaderChunks = new HashMap<>();
    
    // 内存追踪：所有活跃的加载器盔甲架实体引用
    private final Set<ArmorStand> activeLoaders = new HashSet<>();

    @Override
    public void onEnable() {
        plugin = this;
        loadPluginConfig();

        KEY_RADIUS = new NamespacedKey(this, "radius");
        KEY_EXPIRY = new NamespacedKey(this, "expiry");
        KEY_WARNED = new NamespacedKey(this, "warned");
        KEY_ID = new NamespacedKey(this, "id");

        getServer().getPluginManager().registerEvents(this, this);
        
        // 注册命令
        Objects.requireNonNull(getCommand("icl")).setExecutor(this);

        // 每秒执行一次更新任务 (20 ticks)
        new LoaderTask().runTaskTimer(this, 20L, 20L);

        // 启动时的扫描逻辑 (持久化恢复或崩溃清理)
        scanAndProcessLoaders();

        getLogger().info("[IpacChunkLoader] 已加载");
    }

    @Override
    public void onDisable() {
        // 插件卸载时，如果不开启持久化，则释放由本插件管理的区块
        if (!persistence) {
            activeLoaderChunks.values().forEach(chunks -> {
                for (World world : Bukkit.getWorlds()) {
                    for (long key : chunks) {
                        world.setChunkForceLoaded((int) key, (int) (key >> 32), false);
                    }
                }
            });
        }
        activeLoaderChunks.clear();
        activeLoaders.clear();
    }

    private void loadPluginConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        pattern = Pattern.compile(plugin.getConfig().getString("name-regex", "^\\s*(?<id>.{0,24}?)\\s*(?:区块加载|区块加载器)\\s*(?<radius>\\d+)\\s*$"));
        defaultId = plugin.getConfig().getString("default-id", "未命名");
        displayFormat = plugin.getConfig().getString("display-format", "[区块加载器] %id% %radius% - %time%");
        expiryFormat = plugin.getConfig().getString("expiry-format", "[区块加载器] %id% %radius% - %time%");
        maxHours = plugin.getConfig().getInt("max-hours", 24);
        addHours = plugin.getConfig().getInt("add-hours", 4);
        maxRadius = plugin.getConfig().getInt("max-radius", 10);
        maxCount = plugin.getConfig().getInt("max-count", 10);
        persistence = plugin.getConfig().getBoolean("persistence", true);
        warnHours = plugin.getConfig().getInt("warn-hours", 1);
        warnCommands = plugin.getConfig().getStringList("warn-commands");
    }

    private void scanAndProcessLoaders() {
        int recoveredCount = 0;
        int cleanedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getForceLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof ArmorStand as) {
                        PersistentDataContainer pdc = as.getPersistentDataContainer();
                        if (pdc.has(KEY_RADIUS, INTEGER) && pdc.has(KEY_EXPIRY, LONG)) {
                            
                            // 如果开启持久化，则将其添加到活跃列表重新接管
                            if (persistence) {
                                activeLoaders.add(as);
                                recoveredCount++;
                            } 
                            // 否则在启动时清理残留的强加载状态 (应对崩溃情况)
                            else {
                                Integer r = pdc.get(KEY_RADIUS, INTEGER);
                                if (r != null) {
                                    Chunk center = as.getLocation().getChunk();
                                    for (int x = -r; x <= r; x++) {
                                        for (int z = -r; z <= r; z++) {
                                            world.setChunkForceLoaded(center.getX() + x, center.getZ() + z, false);
                                        }
                                    }
                                    cleanedCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (persistence && recoveredCount > 0) {
            getLogger().info("[IpacChunkLoader] 启动扫描: 已自动恢复 " + recoveredCount + " 个活跃加载器");
        } else if (!persistence && cleanedCount > 0) {
            getLogger().info("[IpacChunkLoader] 启动清理: 已重置 " + cleanedCount + " 个残留加载器的强加载状态");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerNaming(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ArmorStand armorStand)) return;

        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (!item.hasItemMeta()) return;

        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        // 使用 Adventure API 获取 Component 并转换为纯文本
        Component displayName = meta.displayName();
        if (displayName == null) return;

        String customName = PlainTextComponentSerializer.plainText().serialize(displayName);

        Matcher matcher = pattern.matcher(customName);
        if (matcher.find()) {
            int radiusVal;
            String idVal;
            try {
                idVal = matcher.group("id").trim();
            } catch (IllegalArgumentException e) {
                idVal = "";
            }
            try {
                radiusVal = Integer.parseInt(matcher.group("radius"));
            } catch (IllegalArgumentException e) {
                radiusVal = -1;
            }
            if (idVal.isEmpty()) {
                idVal = defaultId;
            }
            final int radius = radiusVal;
            final String id = idVal;

            // 检查半径是否有效 (0:1格, 1:9格)
            if (radius < 0 || radius > maxRadius) {
                removeLoader(armorStand);
                return;
            }

            long now = System.currentTimeMillis();
            PersistentDataContainer pdc = armorStand.getPersistentDataContainer();
            Long currentExpiry = pdc.get(KEY_EXPIRY, LONG);
            long newExpiry;

            // 检查是否已经是活跃的加载器且未过期
            if (currentExpiry != null && currentExpiry > now) {
                // 累计时间
                long remaining = currentExpiry - now;
                long increment = (long) addHours * 3600 * 1000;
                long maxDuration = (long) maxHours * 3600 * 1000;
                newExpiry = now + Math.min(remaining + increment, maxDuration);
            } else {
                // 新激活或已过期，检查数量限制 (如果已经在活跃列表里则不需要检查，防止重置导致的问题)
                if (!activeLoaders.contains(armorStand) && activeLoaders.size() >= maxCount) {
                    event.getPlayer().sendMessage(plugin.getConfig().getString("msg.quant-limit", ""));
                    return;
                }
                newExpiry = now + ((long) addHours * 3600 * 1000);

                // 只有在全新激活或过期重启时才打印日志
                getLogger().info("[IpacChunkLoader] 加载器已激活: " +
                        armorStand.getLocation().getBlockX() + ", " +
                        armorStand.getLocation().getBlockZ() + " [" +
                        armorStand.getWorld().getName() + "]");
                event.getPlayer().sendMessage(plugin.getConfig().getString("msg.activate", ""));
            }

            pdc.set(KEY_RADIUS, INTEGER, radius);
            pdc.set(KEY_EXPIRY, LONG, newExpiry);
            pdc.set(KEY_ID, PersistentDataType.STRING, id);

            // 如果新时间超过了预警阈值，重置预警标记
            if (newExpiry - now > (long) warnHours * 3600 * 1000) {
                pdc.remove(KEY_WARNED);
            }

            // 立即更新盔甲架名称显示
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long remaining = newExpiry - now;
                String nameToSet = displayFormat
                        .replace("%id%", id)
                        .replace("%radius%", String.valueOf(radius))
                        .replace("%time%", formatTime(remaining));
                armorStand.customName(Component.text(nameToSet));
                armorStand.setCustomNameVisible(true);
            }, 1);
            
            // 添加到活跃加载器集合
            activeLoaders.add(armorStand);
        } else {
            // 如果玩家改了一个不符合正则的名字，取消加载器功能
            removeLoader(armorStand);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof ArmorStand) {
            removeLoader(event.getEntity());
        }
    }

    /**
     * 彻底移除一个区块加载器及相关的区块 Ticket
     */
    private void removeLoader(Entity entity) {
        UUID uuid = entity.getUniqueId();
        Set<Long> chunks = activeLoaderChunks.remove(uuid);
        if (chunks != null) {
            World world = entity.getWorld();
            for (long key : chunks) {
                // 仅当没有其他活跃加载器需要此区块时才卸载
                if (!isChunkNeededByOthers(uuid, key)) {
                    world.setChunkForceLoaded((int) key, (int) (key >> 32), false);
                }
            }
        }
        // 从活跃加载器集合中移除
        if (entity instanceof ArmorStand) {
            activeLoaders.remove(entity);
        }
        // 清理 PDC 标记
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.remove(KEY_RADIUS);
        pdc.remove(KEY_EXPIRY);
        pdc.remove(KEY_WARNED);
        pdc.remove(KEY_ID);
    }

    private boolean isChunkNeededByOthers(UUID excludeUuid, long chunkKey) {
        for (Map.Entry<UUID, Set<Long>> entry : activeLoaderChunks.entrySet()) {
            if (!entry.getKey().equals(excludeUuid) && entry.getValue().contains(chunkKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 核心任务类：每秒执行一次
     */
    private class LoaderTask extends BukkitRunnable {
        @Override
        public void run() {
            // 只遍历活跃的加载器盔甲架
            for (ArmorStand as : new ArrayList<>(activeLoaders)) {
                processArmorStand(as);
            }
        }

        private void processArmorStand(ArmorStand as) {
            // 检查盔甲架是否仍然存在且有效
            if (!as.isValid() || as.isDead()) {
                removeLoader(as);
                return;
            }

            PersistentDataContainer pdc = as.getPersistentDataContainer();

            Integer radius = pdc.get(KEY_RADIUS, PersistentDataType.INTEGER);
            Long expiry = pdc.get(KEY_EXPIRY, LONG);
            String id = pdc.get(KEY_ID, PersistentDataType.STRING);
            if (id == null) id = "";

            if (radius == null || expiry == null) {
                removeLoader(as);
                return;
            }

            long remaining = expiry - System.currentTimeMillis();

            // 检查过期预警
            if (remaining > 0 && remaining < (long) warnHours * 3600 * 1000) {
                if (!pdc.has(KEY_WARNED, PersistentDataType.BYTE)) {
                    pdc.set(KEY_WARNED, PersistentDataType.BYTE, (byte) 1);
                    if (warnCommands != null && !warnCommands.isEmpty()) {
                        for (String warnCommand : warnCommands) {
                            String cmd = warnCommand
                                    .replace("%world%", as.getWorld().getName())
                                    .replace("%x%", String.valueOf(as.getLocation().getBlockX()))
                                    .replace("%y%", String.valueOf(as.getLocation().getBlockY()))
                                    .replace("%z%", String.valueOf(as.getLocation().getBlockZ()))
                                    .replace("%name%", PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(as.customName())))
                                    .replace("%id%", id)
                                    .replace("%radius%", String.valueOf(radius))
                                    .replace("%time%", formatTime(remaining));
                            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                        }
                    }
                }
            } else if (remaining > (long) warnHours * 3600 * 1000) {
                pdc.remove(KEY_WARNED);
            }

            String nameToSet;
            if (remaining <= 0) {
                nameToSet = expiryFormat
                        .replace("%id%", id)
                        .replace("%radius%", String.valueOf(radius))
                        .replace("%time%", "00:00:00");
                // 使用 Component.text 设置名称
                as.customName(Component.text(nameToSet));
                removeLoader(as);
                return;
            } else {
                nameToSet = displayFormat
                        .replace("%id%", id)
                        .replace("%radius%", String.valueOf(radius))
                        .replace("%time%", formatTime(remaining));
                // 使用 Component.text 设置名称
                as.customName(Component.text(nameToSet));
            }

            as.setCustomNameVisible(true);
            updateChunkTickets(as, radius);
        }

        private void updateChunkTickets(ArmorStand as, int r) {
            World world = as.getWorld();
            Chunk center = as.getLocation().getChunk();
            Set<Long> newChunks = new HashSet<>();

            // 计算当前应加载的区块集合
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    newChunks.add(Chunk.getChunkKey(center.getX() + x, center.getZ() + z));
                }
            }

            UUID uuid = as.getUniqueId();
            Set<Long> oldChunks = activeLoaderChunks.getOrDefault(uuid, new HashSet<>());

            // 如果区块位置没有变动，直接跳过节约计算
            if (oldChunks.equals(newChunks)) return;

            // 释放不再需要的旧区块
            for (long key : oldChunks) {
                if (!newChunks.contains(key) && !isChunkNeededByOthers(uuid, key)) {
                    world.setChunkForceLoaded((int) key, (int) (key >> 32), false);
                }
            }

            // 加载新进入范围的区块
            for (long key : newChunks) {
                if (!oldChunks.contains(key)) {
                    world.setChunkForceLoaded((int) key, (int) (key >> 32), true);
                }
            }

            // 更新内存缓存
            activeLoaderChunks.put(uuid, newChunks);
        }
    }

    private String formatTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    @Override // 指令补全
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if(args.length == 1){
            List<String> list = new ArrayList<>();
            list.add("reload"); // 重载配置
            list.add("list");   // 加载器列表
            list.add("clear");  // 清理所有加载器
            return list;
        }
        return null;
    }
    @Override // 执行指令
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args){

        // 默认输出插件信息
        if(args.length == 0){
            sender.sendMessage(
                    "\n"+
                            "IpacEL > IpacChunkLoader: 区块加载器\n"+
                            "  指令:\n"+
                            "    - /icl reload          - 重载配置\n"+
                            "    - /icl list            - 查看所有加载器\n"+
                            "    - /icl clear           - 清理所有加载器和强加载区块\n"+
                            "  统计信息:\n"+
                            "    - 加载器数量: "+ activeLoaders.size() +"\n"+
                            "    - 强加载区块数量: "+ getForceLoadedChunksCount() +"\n"
            );
            return true;
        }

        // 重载配置
        else if(args[0].equals("reload")){
            if(!sender.hasPermission("IpacChunkLoader.reload")) return false;
            loadPluginConfig();
            sender.sendMessage("[ICL] 已完成重载");
            return true;
        }

        // 加载器列表
        else if(args[0].equals("list")){
            if(!sender.hasPermission("IpacChunkLoader.list")) return false;
            sender.sendMessage(plugin.getConfig().getString("msg.list-header", ""));
            long now = System.currentTimeMillis();
            for (ArmorStand as : activeLoaders) {
                Long expiry = as.getPersistentDataContainer().get(KEY_EXPIRY, LONG);
                if (expiry == null) continue;

                long remaining = expiry - now;
                String timeStr = remaining > 0 ? formatTime(remaining) : "00:00:00";
                org.bukkit.Location loc = as.getLocation();
                String worldName = loc.getWorld().getName();
                String worldKey = loc.getWorld().key().asString();
                int x = loc.getBlockX();
                int y = loc.getBlockY();
                int z = loc.getBlockZ();
                int radius = Optional.ofNullable(as.getPersistentDataContainer().get(KEY_RADIUS, INTEGER)).orElse(-1);
                String id = Optional.ofNullable(as.getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING)).orElse("");

                Component asNameComp = as.customName();
                String asName = asNameComp != null ? PlainTextComponentSerializer.plainText().serialize(asNameComp) : "";

                String lineText = plugin.getConfig().getString("msg.list-line", "")
                        .replace("%world%", worldName)
                        .replace("%x%", String.valueOf(x))
                        .replace("%y%", String.valueOf(y))
                        .replace("%z%", String.valueOf(z))
                        .replace("%name%", asName)
                        .replace("%id%", id)
                        .replace("%radius%", String.valueOf(radius))
                        .replace("%time%", timeStr);

                Component line = Component.text()
                        .append(Component.text(lineText))
                        .hoverEvent(Component.text(plugin.getConfig().getString("msg.list-hover", "")))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/minecraft:execute in " + worldKey + " run tp @s " + x + " " + y + " " + z))
                        .build();
                sender.sendMessage(line);
            }
            return true;
        }

        // 清理所有加载器和强加载区块
        else if(args[0].equals("clear")){
            if(!sender.hasPermission("IpacChunkLoader.clear")) return false;
            
            int worldCount = 0;
            int chunkCount = 0;
            
            // 1. 清理所有世界的所有强加载区块 (包括非插件添加的)
            for (World world : Bukkit.getWorlds()) {
                Collection<Chunk> forcedChunks = world.getForceLoadedChunks();
                if (!forcedChunks.isEmpty()) {
                    worldCount++;
                    chunkCount += forcedChunks.size();
                    for (Chunk chunk : forcedChunks) {
                        world.setChunkForceLoaded(chunk.getX(), chunk.getZ(), false);
                    }
                }
            }
            
            // 2. 清理所有本插件追踪的实体 PDC 数据 (如果实体还在已加载区块中)
            for (ArmorStand as : activeLoaders) {
                if (as.isValid()) {
                    PersistentDataContainer pdc = as.getPersistentDataContainer();
                    pdc.remove(KEY_RADIUS);
                    pdc.remove(KEY_EXPIRY);
                    pdc.remove(KEY_WARNED);
                    pdc.remove(KEY_ID);
                }
            }
            
            // 3. 清理内存状态
            activeLoaders.clear();
            activeLoaderChunks.clear();
            
            sender.sendMessage("[ICL] 已清理 " + worldCount + " 个世界的 " + chunkCount + " 个强加载区块, 并释放所有活跃加载器");
            return true;
        }

        // 返回 false 时, 玩家将收到命令不存在的错误
        return false;
    }

    private int getForceLoadedChunksCount() {
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            count += world.getForceLoadedChunks().size();
        }
        return count;
    }
}
