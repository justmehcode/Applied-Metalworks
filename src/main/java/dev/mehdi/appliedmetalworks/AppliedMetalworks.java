package dev.mehdi.appliedmetalworks;

import appeng.api.AECapabilities;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.menu.implementations.MenuTypeBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

@Mod(AppliedMetalworks.ID)
public final class AppliedMetalworks {
    public static final String ID = "appliedmetalworks";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);
    private static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER,ID);
    public static final Supplier<net.minecraft.world.item.crafting.RecipeSerializer<RecolorRecipe>> RECOLOR = SERIALIZERS.register("recolor",RecolorRecipe.Serializer::new);
    public static final Supplier<net.minecraft.world.item.crafting.RecipeSerializer<MachineResetRecipe>> RESET = SERIALIZERS.register("reset_machine",()->new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(MachineResetRecipe::new));
    public static final Supplier<FoundryBlock> INTERFACE = BLOCKS.register("foundry_interface", FoundryBlock::new);
    public static final Supplier<BlockItem> INTERFACE_ITEM = ITEMS.register("foundry_interface", () -> new BlockItem(INTERFACE.get(), new Item.Properties()));
    public static final Supplier<FoundryBlock> EXTENDED_INTERFACE = BLOCKS.register("extended_foundry_interface", FoundryBlock::new);
    public static final Supplier<BlockItem> EXTENDED_INTERFACE_ITEM = ITEMS.register("extended_foundry_interface", () -> new BlockItem(EXTENDED_INTERFACE.get(), new Item.Properties()));
    public static final Supplier<FoundryBlock> CORE = BLOCKS.register("foundry_automation_core", FoundryBlock::new);
    public static final Supplier<BlockItem> CORE_ITEM = ITEMS.register("foundry_automation_core", () -> new BlockItem(CORE.get(), new Item.Properties()));
    public static final java.util.Map<String, Supplier<FoundryBlock>> APPEARANCES = new java.util.LinkedHashMap<>();
    static {
        APPEARANCES.put("foundry_interface", INTERFACE);
        APPEARANCES.put("extended_foundry_interface", EXTENDED_INTERFACE);
        APPEARANCES.put("foundry_automation_core", CORE);
        for (var color : net.minecraft.world.item.DyeColor.values()) if (color != net.minecraft.world.item.DyeColor.BLACK)
            for (var family : java.util.List.of("foundry_interface", "extended_foundry_interface", "foundry_automation_core")) {
                String name = color.getSerializedName() + "_" + family;
                var block = BLOCKS.register(name, FoundryBlock::new);
                ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
                APPEARANCES.put(name, block);
            }
    }
    public static boolean isFamily(net.minecraft.world.level.block.state.BlockState state, String family) {
        return APPEARANCES.entrySet().stream().anyMatch(e -> (e.getKey().equals(family) || e.getKey().endsWith("_"+family)) && state.is(e.getValue().get()));
    }
    public static final Supplier<MenuType<FoundryMenu>> CORE_MENU = MENUS.register("foundry_automation_core",
            () -> MenuTypeBuilder.create(FoundryMenu::new, FoundryBlockEntity.class).buildUnregistered(id("foundry_automation_core")));
    public static final Supplier<MenuType<FoundryMenu>> EXTENDED_MENU = MENUS.register("extended_foundry_interface",
            () -> MenuTypeBuilder.create(FoundryMenu::new, FoundryBlockEntity.class).buildUnregistered(id("extended_foundry_interface")));
    public static final Supplier<BlockEntityType<FoundryBlockEntity>> ENTITY = ENTITIES.register("foundry_interface",
            () -> BlockEntityType.Builder.of(FoundryBlockEntity::new, APPEARANCES.values().stream().map(Supplier::get).toArray(net.minecraft.world.level.block.Block[]::new)).build(null));
    public static final Supplier<MenuType<FoundryMenu>> MENU = MENUS.register("foundry_interface",
            () -> MenuTypeBuilder.create(FoundryMenu::new, FoundryBlockEntity.class).buildUnregistered(id("foundry_interface")));

    public AppliedMetalworks(IEventBus bus,net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,CoreConfig.SPEC);
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus);
        SERIALIZERS.register(bus);
        bus.addListener(this::setup);
        bus.addListener(this::capabilities);
        bus.addListener(this::creativeTab);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            for (var block : APPEARANCES.values()) {
                block.get().setBlockEntity(FoundryBlockEntity.class, ENTITY.get(), null, (level, pos, state, entity) -> entity.serverTick());
                Upgrades.add(AEItems.SPEED_CARD, block.get().asItem(), 4);
            }
            AEBaseBlockEntity.registerBlockEntityItem(ENTITY.get(), INTERFACE_ITEM.get());
        });
    }

    private void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,ENTITY.get(),
                (entity,side)->entity.isCore()?entity.getStandaloneFoundry():null);
        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, ENTITY.get(),
                (entity, side) -> entity.getEnergyInput());
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, ENTITY.get(), (entity, unused) -> entity);
        event.registerBlockEntity(AECapabilities.CRAFTING_MACHINE, ENTITY.get(), (entity, side) -> entity);
    }

    private void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) APPEARANCES.values().forEach(b -> event.accept(b.get().asItem()));
    }

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(ID, path); }
}
