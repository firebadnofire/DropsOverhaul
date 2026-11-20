package org.infotoast.dropsoverhaul.forge;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.command.*;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.*;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
import org.infotoast.dropsoverhaul.common.*;
import org.infotoast.dropsoverhaul.config.YamlSettings;

import java.io.IOException;
import java.util.*;

@Mod(modid = "dropsoverhaul", name = "DropsOverhaul", useMetadata = true, acceptedMinecraftVersions = "[1.12.2]",
        acceptableRemoteVersions = "*", serverSideOnly = true)
public final class DropsOverhaulForge {
    private static DropsOverhaulForge instance;
    private YamlSettings settings;
    private DropPolicy policy;

    public static DropsOverhaulForge instance() { return instance; }
    public YamlSettings settings() { return settings; }
    public void refreshPolicy() { policy = settings.policy(); }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) throws IOException {
        instance = this;
        settings = new YamlSettings(event.getModConfigurationDirectory().toPath().resolve("DropsOverhaul/config.yml"));
        refreshPolicy();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        for (String command : Arrays.asList("reload", "addblock", "removeblock", "list", "killdrops.radius")) {
            PermissionAPI.registerNode("dropsoverhaul." + command, DefaultPermissionLevel.OP, "DropsOverhaul " + command);
        }
        PermissionAPI.registerNode("dropsoverhaul.killdrops", DefaultPermissionLevel.ALL, "Remove nearby item entities");
    }

    @Mod.EventHandler
    public void start(FMLServerStartingEvent event) {
        event.registerServerCommand(new AdminCommand());
        event.registerServerCommand(new KillCommand());
        event.getServer().sendMessage(new TextComponentString("DropsOverhaul has been enabled! Native Forge 1.12.2."));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void harvest(BlockEvent.HarvestDropsEvent event) {
        EntityPlayer player = event.getHarvester();
        if (event.getWorld().isRemote || player == null || player.isCreative() || player.isSpectator()
                || !event.getWorld().getGameRules().getBoolean("doTileDrops")) return;
        ResourceLocation id = event.getState().getBlock().getRegistryName();
        if (id == null) return;
        String name = id.getNamespace().equals("minecraft")
                ? LegacyBlocks.canonical(id.getPath().toUpperCase(Locale.ROOT), event.getState().getBlock().getMetaFromState(event.getState()))
                : id.toString();
        if (!policy.selects(name)) return;
        List<ItemStack> drops = new ArrayList<ItemStack>();
        for (ItemStack stack : event.getDrops()) {
            // Match Forge's native per-stack probability, including its RNG consumption.
            if (event.getWorld().rand.nextFloat() <= event.getDropChance()) drops.add(stack.copy());
        }
        event.getDrops().clear();
        policy.transfer(drops, stack -> {
            player.inventory.addItemStackToInventory(stack);
            return stack.isEmpty() ? Collections.emptyList() : Collections.singletonList(stack);
        }, stack -> player.dropItem(stack, false));
    }

    private boolean allowed(ICommandSender sender, String node) {
        return sender instanceof EntityPlayer ? PermissionAPI.hasPermission((EntityPlayer) sender, "dropsoverhaul." + node)
                : sender.canUseCommand(2, "dropsoverhaul");
    }

    private void tell(ICommandSender sender, String key, String fallback) {
        sender.sendMessage(new TextComponentString(settings.message(key, fallback)));
    }

    private String resolve(String input) {
        String name = input.toUpperCase(Locale.ROOT);
        for (int data = 0; data <= 6; data++) {
            if (LegacyBlocks.canonical("STONE", data).equals(name)) return name;
        }
        if (Arrays.asList("GRASS_BLOCK", "COARSE_DIRT", "PODZOL", "RED_SAND").contains(name)) return name;
        ResourceLocation id = new ResourceLocation(input.toLowerCase(Locale.ROOT));
        if (!Block.REGISTRY.containsKey(id)) return null;
        return id.getNamespace().equals("minecraft") ? LegacyBlocks.canonical(id.getPath().toUpperCase(Locale.ROOT), 0) : id.toString();
    }

    private final class AdminCommand extends CommandBase {
        @Override public String getName() { return "dropsoverhaul"; }
        @Override public List<String> getAliases() { return Arrays.asList("do"); }
        @Override public String getUsage(ICommandSender sender) { return "/dropsoverhaul <reload|addblock|removeblock|list> [block]"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length == 0) {
                tell(sender, "Command.Help-Header", "Drops Overhaul Commands");
                String[][] entries = {{"reload", "Reload-Command"}, {"addblock", "AddBlock-Command"},
                        {"removeblock", "RemoveBlock-Command"}, {"list", "List-Command"}};
                for (String[] entry : entries) if (allowed(sender, entry[0])) tell(sender, "Command." + entry[1], "/do " + entry[0]);
                return;
            }
            String action = args[0].toLowerCase(Locale.ROOT);
            if (!Arrays.asList("reload", "addblock", "removeblock", "list").contains(action)) throw new WrongUsageException(getUsage(sender));
            if (!allowed(sender, action)) { tell(sender, "No-Permission", "Access denied."); return; }
            try {
                if (action.equals("reload")) {
                    settings.reload(); refreshPolicy(); tell(sender, "Command.Reloaded", "Configuration reloaded.");
                } else if (action.equals("list")) {
                    tell(sender, "Command.List-Header", "DropsOverhaul selected blocks:");
                    for (String name : settings.blocks()) sender.sendMessage(new TextComponentString(settings.message("Command.List-Item", "- %block%").replace("%block%", name)));
                } else {
                    if (args.length != 2) throw new WrongUsageException(getUsage(sender));
                    String name = resolve(args[1]);
                    List<String> blocks = settings.blocks();
                    if (action.equals("addblock")) {
                        if (name == null) throw new CommandException("Block is unavailable on Minecraft 1.12.2: " + args[1]);
                        if (!blocks.contains(name)) blocks.add(name);
                    } else {
                        // Permit removal of retained names for unavailable modern blocks.
                        if (name == null) name = args[1].toUpperCase(Locale.ROOT);
                        if (!blocks.remove(name)) throw new CommandException("Block is not selected: " + name);
                    }
                    settings.setBlocks(blocks); refreshPolicy();
                    String key = action.equals("addblock") ? "Command.Block-Added" : "Command.Block-Removed";
                    sender.sendMessage(new TextComponentString(settings.message(key, "%block%").replace("%block%", name)));
                }
            } catch (IOException error) {
                throw new CommandException("Configuration operation failed: " + error.getMessage());
            }
        }
    }

    private final class KillCommand extends CommandBase {
        @Override public String getName() { return "killdrops"; }
        @Override public List<String> getAliases() { return Arrays.asList("kd"); }
        @Override public String getUsage(ICommandSender sender) { return "/killdrops [radius]"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (!allowed(sender, "killdrops")) { tell(sender, "No-Permission", "Access denied."); return; }
            if (!(sender instanceof EntityPlayerMP)) { tell(sender, "Player-Only", "Only players can use this command."); return; }
            if (args.length > 1) throw new WrongUsageException(getUsage(sender));
            int radius = settings.radius();
            if (args.length == 1) {
                if (!allowed(sender, "killdrops.radius")) { tell(sender, "No-Permission-Killdrops-Radius", "Access denied."); return; }
                radius = parseInt(args[0], 0);
            }
            if (radius < 0) throw new CommandException("Configured radius must be non-negative");
            EntityPlayerMP player = (EntityPlayerMP) sender;
            List<EntityItem> items = player.world.getEntitiesWithinAABB(EntityItem.class,
                    new net.minecraft.util.math.AxisAlignedBB(player.posX - radius, player.posY - radius, player.posZ - radius,
                            player.posX + radius, player.posY + radius, player.posZ + radius));
            for (EntityItem item : items) item.setDead();
            sender.sendMessage(new TextComponentString(settings.message("Item-Delete", "Removed %amount% items.").replace("%amount%", Integer.toString(items.size()))));
        }
    }
}
