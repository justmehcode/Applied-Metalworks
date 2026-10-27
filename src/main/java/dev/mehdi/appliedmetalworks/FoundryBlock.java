package dev.mehdi.appliedmetalworks;

import appeng.block.AEBaseEntityBlock;
import appeng.menu.locator.MenuLocators;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class FoundryBlock extends AEBaseEntityBlock<FoundryBlockEntity> implements cy.jdkdigital.productivelib.common.block.IMultiBlockPeripheral {
    private net.minecraft.world.level.block.entity.BlockEntityTicker<FoundryBlockEntity> foundryServerTicker;
    public FoundryBlock() {
        super(metalProps().strength(4.0f));
        registerDefaultState(defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,net.minecraft.core.Direction.NORTH));
    }

    @Override public appeng.api.orientation.IOrientationStrategy getOrientationStrategy() {
        return appeng.api.orientation.OrientationStrategies.horizontalFacing();
    }

    @Override protected BlockState rotate(BlockState state,net.minecraft.world.level.block.Rotation rotation) {
        var facing=net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        return state.setValue(facing,rotation.rotate(state.getValue(facing)));
    }

    @Override protected BlockState mirror(BlockState state,net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)));
    }

    @Override public void setBlockEntity(Class<FoundryBlockEntity> entityClass,
            net.minecraft.world.level.block.entity.BlockEntityType<FoundryBlockEntity> type,
            net.minecraft.world.level.block.entity.BlockEntityTicker<FoundryBlockEntity> clientTicker,
            net.minecraft.world.level.block.entity.BlockEntityTicker<FoundryBlockEntity> serverTicker) {
        super.setBlockEntity(entityClass,type,clientTicker,serverTicker);
        foundryServerTicker=serverTicker;
    }

    @SuppressWarnings("unchecked")
    @Override public <T extends net.minecraft.world.level.block.entity.BlockEntity>
            net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level,BlockState state,
                    net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        // Some AE2 addons overwrite the parent's ticker with grid-only ticking.
        // Our local heater and casting stations require the registered world ticker.
        return level.isClientSide() || type!=getBlockEntityType()?null:
                (net.minecraft.world.level.block.entity.BlockEntityTicker<T>)foundryServerTicker;
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if(stack.is(appeng.core.definitions.AEItems.SPEED_CARD.asItem())) {
            if(!level.isClientSide()) {
                var entity=getBlockEntity(level,pos);
                if(entity!=null && !entity.hasQuarantinedState()) {
                    int offered=player.isShiftKeyDown()?stack.getCount():1;
                    var remaining=entity.getUpgrades().addItems(stack.copyWithCount(offered));
                    int inserted=offered-remaining.getCount();
                    if(inserted>0 && !player.getAbilities().instabuild)stack.shrink(inserted);
                }
            }
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.ITEM) == null)
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        // Claim container interactions even if full/unformed: never fall through to bucket world placement.
        if (!level.isClientSide()) {
            var entity = getBlockEntity(level, pos);
            var controller = entity == null ? null : entity.findFoundry();
            if (entity!=null && !entity.hasQuarantinedState() && (controller != null || entity.isCore())) {
                var inventory = player.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY);
                if (inventory != null) {
                    var tank=entity.isCore()?entity.getStandaloneFoundry():controller.getFluidHandler();
                    var transfer = net.neoforged.neoforge.fluids.FluidUtil.tryEmptyContainerAndStow(
                            player.getItemInHand(hand), tank, inventory, Integer.MAX_VALUE, player, true);
                    if(!transfer.isSuccess())transfer=net.neoforged.neoforge.fluids.FluidUtil.tryFillContainerAndStow(
                            player.getItemInHand(hand),tank,inventory,Integer.MAX_VALUE,player,true);
                    if (transfer.isSuccess()) {
                        player.setItemInHand(hand, transfer.getResult());
                        entity.saveChanges();if(controller!=null)controller.setChanged();
                    }
                }
            }
        }
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        var entity = getBlockEntity(level, pos);
        if (entity == null) return InteractionResult.PASS;
        if (!level.isClientSide()) entity.openMenu(player, MenuLocators.forBlockEntity(entity));
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

}
