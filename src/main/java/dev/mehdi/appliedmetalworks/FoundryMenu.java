package dev.mehdi.appliedmetalworks;

import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.AEBaseMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.RestrictedInputSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public final class FoundryMenu extends AEBaseMenu {
    public static final SlotSemantic CAST_STORAGE = SlotSemantics.register("appliedmetalworks_cast_storage", false);
    public static final SlotSemantic CAPACITORS=SlotSemantics.register("appliedmetalworks_capacitors",false);
    private final FoundryBlockEntity foundry;
    private long nextTelemetry=Long.MIN_VALUE;
    @GuiSync(30) public appeng.api.stacks.GenericStack recipeOutput;
    @GuiSync(31) public appeng.api.stacks.GenericStack recipeFluid;
    @GuiSync(32) public appeng.api.stacks.GenericStack recipeCast;
    @GuiSync(33) public int remaining;
    @GuiSync(34) public int totalCasts;
    @GuiSync(37) public net.minecraft.resources.ResourceLocation recipeId;
    @GuiSync(36) public boolean returnAlloyToME = true;
    @GuiSync(35) public boolean alloying;
    @GuiSync(20) public String status = "idle";
    @GuiSync(21) public int poured;
    @GuiSync(22) public int pourTotal;
    @GuiSync(23) public int cooling;
    @GuiSync(24) public int castSlot = -1;
    @GuiSync(29) public boolean paused;
    @GuiSync(25) public int station = -1;
    @GuiSync(26) public int speedCards;
    @GuiSync(27) public boolean busy;
    @GuiSync(28) public int coolingTotal;
    @GuiSync(38) public boolean core;
    @GuiSync(39) public int activeStations;
    @GuiSync(40) public int selectedStation;
    @GuiSync(41) public int fuelMode;
    @GuiSync(42) public int storedLava;
    @GuiSync(43) public int storedEnergy;
    @GuiSync(44) public String fuelStatus="idle";
    @GuiSync(45) public int energyCapacity;
    @GuiSync(46) public int temperature;
    @GuiSync(47) public int lavaCapacity;
    @GuiSync(48) public int fluidPage;
    @GuiSync(49) public long legacyFe;
    @GuiSync(50) public long legacyLava;
    @GuiSync(51) public boolean quarantined;
    @GuiSync(52) public boolean keepHot;
    @GuiSync(71) public int temperatureLimit;
    @GuiSync(53) public boolean exportingFluids;
    @GuiSync(54) public int fluidPages=1;
    @GuiSync(55) public appeng.api.stacks.GenericStack fluid0;
    @GuiSync(56) public appeng.api.stacks.GenericStack fluid1;
    @GuiSync(57) public appeng.api.stacks.GenericStack fluid2;
    @GuiSync(58) public appeng.api.stacks.GenericStack fluid3;
    @GuiSync(59) public appeng.api.stacks.GenericStack fluid4;
    @GuiSync(60) public appeng.api.stacks.GenericStack fluid5;
    @GuiSync(61) public appeng.api.stacks.GenericStack fluid6;
    @GuiSync(62) public appeng.api.stacks.GenericStack fluid7;
    @GuiSync(63) public long available0;
    @GuiSync(64) public long available1;
    @GuiSync(65) public long available2;
    @GuiSync(66) public long available3;
    @GuiSync(67) public long available4;
    @GuiSync(68) public long available5;
    @GuiSync(69) public long available6;
    @GuiSync(70) public long available7;
    private boolean fluidViewOpen;
    private final java.util.List<appeng.api.stacks.AEKey> fluidOrder=new java.util.ArrayList<>();
    public appeng.api.stacks.GenericStack fluidAt(int row) {
        return switch(row) { case 0->fluid0;case 1->fluid1;case 2->fluid2;case 3->fluid3;case 4->fluid4;case 5->fluid5;case 6->fluid6;case 7->fluid7;default->null; };
    }
    public long fluidAvailableAt(int row) {
        return switch(row) { case 0->available0;case 1->available1;case 2->available2;case 3->available3;case 4->available4;case 5->available5;case 6->available6;case 7->available7;default->0; };
    }
    private void setFluidRow(int row,appeng.api.stacks.GenericStack stack,long available) {
        switch(row) {
            case 0->{fluid0=stack;available0=available;}case 1->{fluid1=stack;available1=available;}
            case 2->{fluid2=stack;available2=available;}case 3->{fluid3=stack;available3=available;}
            case 4->{fluid4=stack;available4=available;}case 5->{fluid5=stack;available5=available;}
            case 6->{fluid6=stack;available6=available;}case 7->{fluid7=stack;available7=available;}
        }
    }
    void syncFluids() {
        var contents=foundry.internalFluids();var available=foundry.exportableFluids(contents);
        int count=0;for(var entry:contents)if(entry.getLongValue()>0)count++;
        boolean changed=count!=fluidOrder.size();
        if(!changed)for(var key:fluidOrder)if(contents.get(key)<=0) { changed=true;break; }
        // Amounts and reservations remain live. Only the key order is cached, local to this menu.
        if(changed) {
            fluidOrder.clear();for(var entry:contents)if(entry.getLongValue()>0)fluidOrder.add(entry.getKey());
            fluidOrder.sort(java.util.Comparator.comparing((appeng.api.stacks.AEKey key)->key.getId().toString()).thenComparingInt(Object::hashCode));
        }
        fluidPages=Math.max(1,(fluidOrder.size()+7)/8);fluidPage=Math.clamp(fluidPage,0,fluidPages-1);
        for(int i=0;i<8;i++) {
            int index=fluidPage*8+i;
            var stack=index<fluidOrder.size()?new appeng.api.stacks.GenericStack(fluidOrder.get(index),contents.get(fluidOrder.get(index))):null;
            setFluidRow(i,stack,stack==null?0:Math.min(stack.amount(),available.get(stack.what())));
        }
    }
    private boolean migrationLocked() { return isServerSide()?foundry.hasQuarantinedState():quarantined; }

    public FoundryMenu(MenuType<? extends FoundryMenu> type, int id, Inventory inventory, FoundryBlockEntity host) {
        super(type, id, inventory, host);
        foundry = host;
        core=host.isCore();
        if(core)addSlot(new AppEngSlot(host.getCapacitors(),0) {
            @Override public boolean mayPlace(ItemStack stack) { return !migrationLocked() && FoundryBlockEntity.isCapacityModule(stack) && super.mayPlace(stack); }
            @Override public boolean mayPickup(Player player) { return !migrationLocked() && super.mayPickup(player); }
        },CAPACITORS);
        createPlayerInventorySlots(inventory);
        for (int slot = 0; slot < host.getCasts().size(); slot++) addSlot(new CastSlot(host, slot), CAST_STORAGE);
        for (int slot = 0; slot < host.getUpgrades().size(); slot++) {
            addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.UPGRADES, host.getUpgrades(), slot) {
                @Override public boolean mayPlace(ItemStack stack) { return !migrationLocked() && super.mayPlace(stack); }
                @Override public boolean mayPickup(Player player) { return !migrationLocked() && super.mayPickup(player); }
            }, SlotSemantics.UPGRADE);
        }
        registerClientAction("alloyDestination", () -> { if (isServerSide()) foundry.toggleAlloyDestination(); });
        registerClientAction("recover", () -> { if (isServerSide()) foundry.recoverOrResume(); });
        registerClientAction("station", () -> { if(isServerSide()) foundry.selectNextLane(); });
        registerClientAction("previousStation", () -> { if(isServerSide()) foundry.selectLane(-1); });
        registerClientAction("fluidView",Boolean.class,open -> { if(isServerSide() && open!=null) { fluidViewOpen=open;nextTelemetry=Long.MIN_VALUE; } });
        registerClientAction("fluidPage",Integer.class,delta->{ if(isServerSide() && delta!=null) { fluidPage=Math.clamp(fluidPage+Integer.signum(delta),0,fluidPages-1);nextTelemetry=Long.MIN_VALUE; } });
        registerClientAction("exportAllFluids",()->{ if(isServerSide()) { foundry.exportAllStoredFluids();nextTelemetry=Long.MIN_VALUE; } });
        registerClientAction("exportFluid",String.class,data->{
            if(!isServerSide() || data==null || data.length()>65536)return;
            try {
                var stack=appeng.api.stacks.GenericStack.readTag(foundry.getLevel().registryAccess(),net.minecraft.nbt.TagParser.parseTag(data));
                if(stack!=null && stack.what() instanceof appeng.api.stacks.AEFluidKey key)foundry.exportStoredFluid(key);
                nextTelemetry=Long.MIN_VALUE;
            } catch(com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException invalid) { /* Ignore invalid client keys. */ }
        });
    }

    public void toggleAlloyDestination() { sendClientAction("alloyDestination"); }
    public void recover() { sendClientAction("recover"); }
    public void nextStation() { sendClientAction("station"); }
    public void previousStation() { sendClientAction("previousStation"); }
    public void setFluidViewOpen(boolean open) { sendClientAction("fluidView",open); }
    public void changeFluidPage(int delta) { sendClientAction("fluidPage",delta); }
    public void exportAllFluids() { sendClientAction("exportAllFluids"); }
    public void exportFluid(appeng.api.stacks.GenericStack stack) {
        sendClientAction("exportFluid",appeng.api.stacks.GenericStack.writeTag(foundry.getLevel().registryAccess(),stack).toString());
    }

    @Override public void onSlotChange(net.minecraft.world.inventory.Slot slot) {
        super.onSlotChange(slot);
        if(slot instanceof AppEngSlot aeSlot && aeSlot.getInventory()==foundry.getUpgrades()) {
            foundry.getUpgrades().sendChangeNotification(slot.getContainerSlot());
            foundry.saveChanges();
        }
    }

    @Override public ItemStack quickMoveStack(Player player,int index) {
        if(isClientSide() || index<0 || index>=slots.size())return ItemStack.EMPTY;
        var source=slots.get(index);var stack=source.getItem();
        if(isPlayerSideSlot(source) && !stack.isEmpty() && !stack.is(appeng.core.definitions.AEItems.SPEED_CARD.asItem())
                && !(core && FoundryBlockEntity.isCapacityModule(stack))) {
            if(!source.mayPickup(player))return ItemStack.EMPTY;
            int offered=Math.min(4,stack.getCount());var remainder=stack.copyWithCount(offered);
            for(boolean filled:new boolean[]{true,false})for(var destination:getSlots(CAST_STORAGE)) {
                if(destination.hasItem()==filled)remainder=destination.safeInsert(remainder);
                if(remainder.isEmpty())break;
            }
            int inserted=offered-remainder.getCount();
            if(inserted>0)source.remove(inserted);
            return ItemStack.EMPTY;
        }
        return super.quickMoveStack(player,index);
    }

    @Override protected java.util.List<net.minecraft.world.inventory.Slot> getQuickMoveDestinationSlots(ItemStack stack, boolean fromPlayerSide) {
        if(fromPlayerSide && stack.is(appeng.core.definitions.AEItems.SPEED_CARD.asItem()))
            return getSlots(SlotSemantics.UPGRADE).stream().filter(slot->slot.mayPlace(stack)).toList();
        // A source module has a dedicated destination. Do not probe 36 cast slots and
        // dispatch every optional casting handler just to insert a capacitor or tank.
        if (core && fromPlayerSide && FoundryBlockEntity.isCapacityModule(stack)) {
            var slot=slots.get(0);
            return slot.mayPlace(stack)?java.util.List.of(slot):java.util.List.of();
        }
        return super.getQuickMoveDestinationSlots(stack,fromPlayerSide);
    }

    @Override public void broadcastChanges() {
        updateTelemetry();
        super.broadcastChanges();
    }
    void updateTelemetry() {
        if (isServerSide() && (foundry.getLevel().getGameTime()>=nextTelemetry
                || fuelMode!=foundry.getFuelManager().mode().ordinal() || selectedStation!=foundry.getSelectedLane()
                || core && (energyCapacity!=foundry.getEnergyInput().getMaxEnergyStored() || lavaCapacity!=foundry.getFuelManager().lavaCapacity())
                || busy!=foundry.hasJob() || paused!=foundry.isPaused() || keepHot!=foundry.getFuelManager().keepHot() || speedCards!=foundry.getSpeedCards() || exportingFluids!=foundry.isExportingFluids())) {
            // Inventory slots still synchronize every broadcast; recipe/resource telemetry is 5 Hz.
            nextTelemetry=foundry.getLevel().getGameTime()+4;
            keepHot=foundry.getFuelManager().keepHot();
            core=foundry.isCore();activeStations=foundry.getActiveLanes();selectedStation=foundry.getSelectedLane();
            quarantined=foundry.hasQuarantinedState();legacyFe=foundry.getFuelManager().legacyFe();legacyLava=foundry.getFuelManager().legacyLava();
            fuelMode=foundry.getFuelManager().mode().ordinal();fuelStatus=foundry.getFuelManager().status();
            exportingFluids=foundry.isExportingFluids();if(fluidViewOpen)syncFluids();
            if(core) {
                storedLava=foundry.getFuelManager().lava();lavaCapacity=foundry.getFuelManager().lavaCapacity();
                storedEnergy=foundry.getEnergyInput().getEnergyStored();energyCapacity=foundry.getEnergyInput().getMaxEnergyStored();
                temperature=foundry.getStandaloneFoundry().temperature();temperatureLimit=foundry.getFuelManager().limit();
            }
            returnAlloyToME = foundry.returnsAlloyToME();
            status = foundry.getStatus(); poured = foundry.getPoured(); pourTotal = foundry.getPourTotal();
            cooling = foundry.getCooling(); castSlot = foundry.getCastSlot(); paused = foundry.isPaused(); station = foundry.getStation();
            speedCards = foundry.getSpeedCards(); busy = foundry.hasJob();
            coolingTotal = foundry.getCoolingTotal();
            recipeId = foundry.getRecipeId();
            recipeOutput = foundry.getRecipeOutput(); recipeFluid = foundry.getRecipeFluid(); recipeCast = foundry.getRecipeCast();
            remaining = foundry.getRemaining(); totalCasts = foundry.getTotalCasts(); alloying = foundry.needsAlloying();
        }
    }

    private final class CastSlot extends AppEngSlot {
        CastSlot(FoundryBlockEntity host, int slot) { super(host.getCasts(), slot); }
        // Any item can be a scripted casting substrate. Resolve recipes when a provider
        // submits work, never during client prediction, drag checks or inventory clicks.
        private boolean locked() { return isServerSide() ? foundry.hasJob() : busy; }
        @Override public boolean mayPlace(ItemStack stack) { return !locked() && !stack.isEmpty() && super.mayPlace(stack); }
        @Override public boolean mayPickup(Player player) { return !locked() && super.mayPickup(player); }
    }
}

