package dev.mehdi.appliedmetalworks;

import cy.jdkdigital.productivemetalworks.common.block.entity.FoundryCapacitorBlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;
import java.util.List;

/** Receive-only view of the capacitors belonging to this interface's formed foundry. */
final class ControllerEnergyInput implements IEnergyStorage {
    private final FoundryBlockEntity host;
    ControllerEnergyInput(FoundryBlockEntity host) { this.host=host; }
    private java.util.Collection<net.minecraft.core.BlockPos> capacitorPositions() {
        if(host.hasQuarantinedState())return List.of();
        var controller=host.findFoundry();
        if(controller==null || controller.getCoilType()!=cy.jdkdigital.productivemetalworks.util.CoilType.ENERGY)return List.of();
        return controller.getMultiblockData().peripherals();
    }
    @Override public int receiveEnergy(int amount,boolean simulate) {
        int received=0;int offered=Math.min(32768,Math.max(0,amount));
        if(offered==0)return 0;
        for(var pos:capacitorPositions()) {
            if(!host.getLevel().hasChunkAt(pos) || !(host.getLevel().getBlockEntity(pos) instanceof FoundryCapacitorBlockEntity capacitor))continue;
            int sent=capacitor.getEnergyHandler().receiveEnergy(offered-received,simulate);
            received+=sent;if(sent>0 && !simulate)capacitor.setChanged();
            if(received==offered)break;
        }
        return received;
    }
    @Override public int extractEnergy(int amount,boolean simulate) { return 0; }
    private int energy(boolean capacity) {
        long total=0;
        for(var pos:capacitorPositions()) {
            if(!host.getLevel().hasChunkAt(pos) || !(host.getLevel().getBlockEntity(pos) instanceof FoundryCapacitorBlockEntity capacitor))continue;
            var handler=capacitor.getEnergyHandler();total+=capacity?handler.getMaxEnergyStored():handler.getEnergyStored();
        }
        return (int)Math.min(Integer.MAX_VALUE,total);
    }
    @Override public int getEnergyStored() { return energy(false); }
    @Override public int getMaxEnergyStored() { return energy(true); }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return true; }
}
