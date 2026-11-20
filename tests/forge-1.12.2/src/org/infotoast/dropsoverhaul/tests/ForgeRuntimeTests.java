package org.infotoast.dropsoverhaul.tests;

import net.minecraft.block.state.IBlockState;
import net.minecraft.block.BlockStone;
import net.minecraft.command.*;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.*;
import org.infotoast.dropsoverhaul.forge.DropsOverhaulForge;

import java.util.*;

@Mod(modid = "dropsoverhaul_tests", name = "DropsOverhaul Runtime Tests", version = "1", acceptableRemoteVersions = "*", serverSideOnly = true)
public final class ForgeRuntimeTests {
    private String scenario;
    private final BlockPos target = new BlockPos(0, 100, 2);
    private int nativeAmount;

    @Mod.EventHandler
    public void start(FMLServerStartingEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
        event.registerServerCommand(new FixtureCommand());
    }

    @SubscribeEvent
    public void noPickup(EntityItemPickupEvent event) {
        if (scenario != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public void cancel(BlockEvent.BreakEvent event) {
        if ("cancelled".equals(scenario) && event.getPos().equals(target)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void nativeDrops(BlockEvent.HarvestDropsEvent event) {
        if (!event.getPos().equals(target)) return;
        if ("custom".equals(scenario)) {
            event.getDrops().clear();
            event.getDrops().add(new ItemStack(Items.EMERALD, 3));
        }
        for (ItemStack stack : event.getDrops()) nativeAmount += stack.getCount();
    }

    private final class FixtureCommand extends CommandBase {
        @Override public String getName() { return "dofixture"; }
        @Override public String getUsage(ICommandSender sender) { return "/dofixture <prepare|verify> <scenario>"; }
        @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length != 2) throw new WrongUsageException(getUsage(sender));
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            try {
                if (args[0].equals("prepare")) prepare(player, args[1]);
                else verify(player, args[1]);
            } catch (Exception error) {
                server.logWarning("DO_TEST_FAIL " + args[1] + " " + error);
                sender.sendMessage(new TextComponentString("DO_TEST_FAIL " + args[1] + " " + error.getMessage()));
            }
        }
    }

    private void prepare(EntityPlayerMP player, String name) throws Exception {
        scenario = name;
        nativeAmount = 0;
        WorldServer world = player.getServerWorld();
        world.setWorldTime(6000);
        for (EntityItem item : new ArrayList<EntityItem>(world.getEntities(EntityItem.class, item -> true))) item.setDead();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            world.setBlockState(new BlockPos(x, 99, z), Blocks.BEDROCK.getDefaultState());
            for (int y = 100; y <= 104; y++) world.setBlockToAir(new BlockPos(x, y, z));
        }
        player.connection.setPlayerLocation(0.5, 100, 0.5, 0, 0);
        player.setGameType(name.equals("creative") ? GameType.CREATIVE : GameType.SURVIVAL);
        player.getFoodStats().setFoodLevel(20);
        player.inventory.clear();
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        if (name.equals("silk")) tool.addEnchantment(Enchantments.SILK_TOUCH, 1);
        if (name.equals("fortune")) tool.addEnchantment(Enchantments.FORTUNE, 3);
        player.inventory.setInventorySlotContents(0, tool);
        player.inventory.currentItem = 0;
        if (name.startsWith("full") || name.equals("partial")) {
            for (int slot = 1; slot < 36; slot++) player.inventory.setInventorySlotContents(slot, new ItemStack(Blocks.BEDROCK, 64));
            if (name.equals("partial")) player.inventory.setInventorySlotContents(1, new ItemStack(Blocks.COBBLESTONE, 63));
        }
        DropsOverhaulForge.instance().settings().configure(name.equals("metadata") ? Arrays.asList("GRANITE") : Arrays.asList("STONE", "DIAMOND_ORE"), name.equals("full-delete"));
        DropsOverhaulForge.instance().refreshPolicy();
        if (name.equals("commands")) {
            MinecraftServer server = player.getServer();
            require(server.getCommandManager().executeCommand(player, "do") == 1, "help alias executes");
            server.getCommandManager().executeCommand(player, "dropsoverhaul addblock GOLD_BLOCK");
            server.getCommandManager().executeCommand(player, "dropsoverhaul addblock GOLD_BLOCK");
            List<String> blocks = DropsOverhaulForge.instance().settings().blocks();
            require(Collections.frequency(blocks, "GOLD_BLOCK") == 1, "idempotent add command");
            server.getCommandManager().executeCommand(player, "dropsoverhaul reload");
            require(DropsOverhaulForge.instance().settings().blocks().contains("GOLD_BLOCK"), "persistent config reload");
            server.getCommandManager().executeCommand(player, "dropsoverhaul removeblock GOLD_BLOCK");
            require(!DropsOverhaulForge.instance().settings().blocks().contains("GOLD_BLOCK"), "remove command");
            server.getCommandManager().executeCommand(player, "dropsoverhaul list");
            server.getPlayerList().removeOp(player.getGameProfile());
            try {
                server.getCommandManager().executeCommand(player, "dropsoverhaul addblock GOLD_BLOCK");
                require(!DropsOverhaulForge.instance().settings().blocks().contains("GOLD_BLOCK"), "permission enforcement");
            } finally { server.getPlayerList().addOp(player.getGameProfile()); }
            EntityItem near = new EntityItem(world, player.posX, player.posY, player.posZ + 1, new ItemStack(net.minecraft.init.Blocks.DIRT));
            EntityItem aboveRadius = new EntityItem(world, player.posX, player.posY + 3, player.posZ, new ItemStack(net.minecraft.init.Blocks.DIRT));
            world.spawnEntity(near);
            world.spawnEntity(aboveRadius);
            try {
                server.getCommandManager().executeCommand(player, "killdrops 2");
                require(near.isDead && !aboveRadius.isDead, "location-centered cleanup radius");
                server.getCommandManager().executeCommand(player, "kd");
                require(aboveRadius.isDead, "default cleanup alias executes");
            } finally { near.setDead(); aboveRadius.setDead(); }
        }
        IBlockState block = name.equals("unselected") ? Blocks.GOLD_BLOCK.getDefaultState() : name.equals("fortune")
                ? Blocks.DIAMOND_ORE.getDefaultState() : Blocks.STONE.getDefaultState();
        if (name.equals("metadata")) block = Blocks.STONE.getDefaultState().withProperty(BlockStone.VARIANT, BlockStone.EnumType.GRANITE);
        world.setBlockState(target, block);
        player.inventoryContainer.detectAndSendChanges();
        player.sendMessage(new TextComponentString("DO_TEST_READY " + name));
    }

    private void verify(EntityPlayerMP player, String name) {
        require(name.equals(scenario), "scenario mismatch");
        boolean cancelled = name.equals("cancelled");
        require(player.world.getBlockState(target).getBlock() == (cancelled ? Blocks.STONE : Blocks.AIR), "native block state");
        Item expected = name.equals("metadata") ? Item.getItemFromBlock(Blocks.STONE) : name.equals("custom") ? Items.EMERALD : name.equals("fortune") ? Items.DIAMOND :
                Item.getItemFromBlock(name.equals("unselected") ? Blocks.GOLD_BLOCK : name.equals("silk") ? Blocks.STONE : Blocks.COBBLESTONE);
        int inventory = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack.getItem() == expected) {
                if (name.equals("metadata")) require(stack.getMetadata() == 1, "granite item metadata");
                inventory += stack.getCount();
            }
        }
        int ground = 0;
        for (EntityItem item : player.world.getEntities(EntityItem.class, item -> !item.isDead)) {
            require(item.getItem().getItem() == expected, "unexpected ground drop");
            ground += item.getItem().getCount();
        }
        if (name.equals("creative") || cancelled || name.equals("full-delete")) require(inventory == 0 && ground == 0, "unexpected drops");
        else if (name.equals("unselected") || name.equals("full-keep")) require(inventory == 0 && ground == 1, "ground conservation " + inventory + "/" + ground);
        else if (name.equals("fortune")) require(inventory >= 1 && inventory <= 4 && inventory == nativeAmount && ground == 0, "native Fortune conservation");
        else require(inventory == (name.equals("partial") ? 64 : name.equals("custom") ? 3 : 1) && ground == 0, "inventory conservation " + inventory + "/" + ground);
        if (!cancelled && !name.equals("creative")) require(player.inventory.getStackInSlot(0).getItemDamage() == 1, "native durability");
        player.getServer().sendMessage(new TextComponentString("DO_TEST_PASS " + name + " inventory=" + inventory + " ground=" + ground));
        player.sendMessage(new TextComponentString("DO_TEST_PASS " + name));
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
