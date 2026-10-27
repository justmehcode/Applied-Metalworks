package dev.mehdi.appliedmetalworks;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.upgrades.*;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.storage.StorageHelper;
import cy.jdkdigital.productivelib.common.block.entity.IMultiBlockPeripheralBlockEntity;
import net.minecraft.network.chat.Component;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuHostLocator;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import cy.jdkdigital.productivemetalworks.Config;
import cy.jdkdigital.productivemetalworks.common.block.entity.FoundryControllerBlockEntity;
import cy.jdkdigital.productivemetalworks.common.block.entity.FoundryDrainBlockEntity;
import cy.jdkdigital.productivemetalworks.recipe.ItemCastingRecipe;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import java.util.*;

public final class FoundryBlockEntity extends AENetworkedBlockEntity
        implements ICraftingMachine, IMultiBlockPeripheralBlockEntity, IUpgradeableObject, InternalInventoryHost {
    private BlockPos controllerPosition;
    private boolean paused;
    private CompoundTag migrationQuarantine,migrationBackup;
    private boolean legacyExternalController;
    private BlockPos legacyController;
    private final KeyCounter migrationReturns=new KeyCounter();
    private boolean migrationRecoveryActive;
    public long legacyRecoveryAmount() { long amount=0;for(var entry:migrationReturns)amount+=entry.getLongValue();return amount; }
    public boolean hasQuarantinedState() { return migrationQuarantine!=null; }
    private boolean returnAlloyToME = true;
    private boolean fluidExportRequested;
    private long nextFluidExport=Long.MIN_VALUE;
    private int fluidExportCursor;
    public boolean isExportingFluids() { return fluidExportRequested; }
    public boolean returnsAlloyToME() { return returnAlloyToME; }
    public void toggleAlloyDestination() { if (!hasJob()) { returnAlloyToME = !returnAlloyToME; saveChanges(); } }
    private final AppEngInternalInventory casts;
    private final IUpgradeInventory upgrades;
    private final AppEngInternalInventory capacitors;
    private CastingJob job;
    private CompoundTag damagedJob;
    private String status = "idle";
    private record AvailableCast(int slot,GenericStack stack) {}
    private record RejectedRequest(AEItemKey pattern,List<GenericStack> supplied,List<AvailableCast> casts,List<GenericStack> stock,int temperature) {}
    private Object rejectedRecipeVersion;
    private final Map<RejectedRequest,Long> rejectedRequests=new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<RejectedRequest,Long> entry) { return size()>32; }
    };
    private static List<GenericStack> snapshot(KeyCounter counter) {
        var result=new ArrayList<GenericStack>();for(var e:counter)if(e.getLongValue()>0)result.add(new GenericStack(e.getKey(),e.getLongValue()));return List.copyOf(result);
    }
    private final boolean core;
    private final CastingJob[] lanes = new CastingJob[4];
    private final CompoundTag[] brokenLanes = new CompoundTag[4];
    private final String[] laneStatus = {"idle", "idle", "idle", "idle"};
    private int selectedLane;
    private int tickStart;
    private final CoreFuelManager fuelManager = new CoreFuelManager(this);
    private final StandaloneFoundry standalone=new StandaloneFoundry(this);
    private final ControllerEnergyInput controllerEnergy=new ControllerEnergyInput(this);
    public StandaloneFoundry getStandaloneFoundry() { return standalone; }
    public AppEngInternalInventory getCapacitors() { return capacitors; }
    public static boolean isCapacitor(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof cy.jdkdigital.productivemetalworks.common.block.FoundryCapacitorBlock;
    }
    public static boolean isFuelTank(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.BlockItem item && item.getBlock() instanceof cy.jdkdigital.productivemetalworks.common.block.FoundryTankBlock;
    }
    public static boolean isCapacityModule(ItemStack stack) { return isCapacitor(stack) || isFuelTank(stack); }
    public boolean isCore() { return core; }
    public net.neoforged.neoforge.energy.IEnergyStorage getEnergyInput() { return core?fuelManager.energy:controllerEnergy; }
    public CoreFuelManager getFuelManager() { return fuelManager; }
    public int getSelectedLane() { return selectedLane; }
    public void selectNextLane() { selectLane(1); }
    public void selectLane(int delta) { if(core) { selectedLane=Math.floorMod(selectedLane+delta,4); selectDisplay(); saveChanges(); } }
    public int getActiveLanes() {
        if(!core)return hasJob()?1:0;
        int count=0;for(int i=0;i<lanes.length;i++)if(lanes[i]!=null || brokenLanes[i]!=null)count++;return count;
    }
    int stationCount() { return core?lanes.length:1; }
    CastingJob jobAt(int slot) { return core?lanes[slot]:job; }
    boolean hasProcessingJob() {
        for(int i=0;i<stationCount();i++) { var request=jobAt(i);if(request!=null && !request.recovering)return true; }
        return false;
    }
    private void selectDisplay() { if(core) { job=lanes[selectedLane]; damagedJob=brokenLanes[selectedLane]; status=laneStatus[selectedLane]; } }
    List<CastingJob> activeJobs() { return core ? Arrays.stream(lanes).filter(Objects::nonNull).toList() : job == null ? List.of() : List.of(job); }

    public FoundryBlockEntity(BlockPos pos, BlockState state) {
        super(AppliedMetalworks.ENTITY.get(), pos, state);
        core=AppliedMetalworks.isFamily(state,"foundry_automation_core");
        casts = new AppEngInternalInventory(this, core || AppliedMetalworks.isFamily(state,"extended_foundry_interface") ? 36 : 9, 4);
        upgrades = UpgradeInventories.forMachine(state.getBlock().asItem(), 4, this::saveChanges);
        capacitors=new AppEngInternalInventory(this,core?1:0,64);
        getMainNode().setIdlePowerUsage(core ? 8 : 1);
    }

    @Override public IUpgradeInventory getUpgrades() { return upgrades; }
    public AppEngInternalInventory getCasts() { return casts; }
    public boolean isPaused() { return paused; }
    public int getCastSlot() { return job == null ? -1 : job.castSlot; }
    public void openMenu(Player player, MenuHostLocator locator) { MenuOpener.open(core ? AppliedMetalworks.CORE_MENU.get() : casts.size() == 36 ? AppliedMetalworks.EXTENDED_MENU.get() : AppliedMetalworks.MENU.get(), player, locator); }
    @Override public PatternContainerGroup getCraftingMachineInfo() {
        return new PatternContainerGroup(AEItemKey.of(getBlockState().getBlock().asItem()),
                getBlockState().getBlock().getName(), List.of());
    }
    // Always advertise the protocol. Returning false while busy would let AE2 fall back to untyped inventory insertion.
    @Override public boolean acceptsPlans() { return true; }
    @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, Direction side) { return startJob(pattern, inputs); }
    @Override public void setMultiblockController(BlockPos pos) {
        if(core)return;
        controllerPosition = pos == null ? null : pos.immutable(); saveChanges();
    }
    @Override public BlockPos getMultiblockController() { return controllerPosition; }
    @Override public void saveChangedInventory(AppEngInternalInventory inv) { saveChanges(); }
    @Override public void onChangeInventory(AppEngInternalInventory inv, int slot) { if(core && inv==capacitors)fuelManager.refreshCapacity();saveChanges(); }
    @Override public boolean isClientSide() { return level == null || level.isClientSide(); }
    public boolean hasJob() { return hasQuarantinedState() || legacyExternalController || !migrationReturns.isEmpty() || (core ? getActiveLanes()>0 : job != null || damagedJob != null); }
    public String getStatus() { return hasQuarantinedState()?"migration_unresolved":legacyExternalController?"migration_controller":!migrationReturns.isEmpty()?"migration_materials":status; }
    public net.minecraft.resources.ResourceLocation getRecipeId() { return job == null ? null : job.recipeId; }
    public int getPoured() {
        if (job == null) return 0;
        if (job.station != 2) return job.poured;
        var foundry = findFoundry();
        int available = core?standalone.amount(job,job.amountPerCast):foundry == null ? 0 : foundry.getFluidHandler().drain(job.fluid.toStack(job.amountPerCast), FluidAction.SIMULATE).getAmount();
        return Math.min(job.amountPerCast, available + job.outputDelivered + job.poured);
    }
    public int getPourTotal() { return job == null ? 0 : job.amountPerCast; }
    public int getCooling() { return job == null ? 0 : Math.max(0, job.cooling); }
    public int getCoolingTotal() { return job == null || job.station == 2 ? 0 : CastingSpeed.coolingWork(job.amountPerCast, Config.foundryCoolingModifier); }
    public int getStation() { return job == null ? -1 : job.station; }
    public GenericStack getRecipeOutput() { return job == null ? null : new GenericStack(job.output, (long) job.total * job.outputPerCast); }
    public GenericStack getRecipeFluid() { return job == null ? null : new GenericStack(job.fluid, job.amountPerCast); }
    public GenericStack getRecipeCast() { return job == null || job.cast == null ? null : new GenericStack(job.cast, 1); }
    public int getTotalCasts() { return job == null ? 0 : job.total; }
    public boolean needsAlloying() { return job != null && job.requiresAlloy; }
    public int getRemaining() { return job == null ? 0 : job.remaining; }
    public int getSpeedCards() {
        // Read the four physical slots, including in-place GUI stack edits; never retain a removed card's boost.
        int count=0;for(int i=0;i<upgrades.size();i++) {
            var stack=upgrades.getStackInSlot(i);if(!stack.isEmpty() && stack.is(AEItems.SPEED_CARD.asItem()))count++;
        }
        return Math.min(4,count);
    }
    /** Only the controller assigned by PMW may be used, and only while this block belongs to its formed structure. */
    FoundryControllerBlockEntity findFoundry() {
        if (core || level == null || controllerPosition == null || !level.hasChunkAt(controllerPosition)) return null;
        if (level.getBlockEntity(controllerPosition) instanceof FoundryControllerBlockEntity controller
                && controller.getMultiblockData() != null
                && controller.getMultiblockData().peripherals().contains(worldPosition)) return controller;
        return null;
    }
    private int temperature(FoundryControllerBlockEntity foundry) {
        FoundryControllerBlockEntity.IMelterProcessor melter = switch (foundry.getCoilType()) {
            case FLUID -> new FoundryControllerBlockEntity.LiquidMelter();
            case ENERGY -> new FoundryControllerBlockEntity.EnergyMelter();
            default -> null;
        };
        if (melter == null) return -1;
        var fuel = melter.getFoundryFuel(level, foundry).getFuelData();
        return fuel == null ? -1 : fuel.temperature();
    }
    public boolean canMelt(ItemStack stack) {
        if(core)return level!=null && !stack.isEmpty() && CastingRecipes.meltingForCore(level,stack,fuelManager.limit())!=null;
        var foundry = findFoundry();
        if (foundry == null || stack.isEmpty()) return false;
        int temp = temperature(foundry);
        return CastingRecipes.melting(level, stack, temp) != null;
    }
    public KeyCounter internalFluids() {
        if(core)return standalone.allFluids();
        var result=new KeyCounter();var controller=findFoundry();if(controller==null)return result;
        var tank=controller.getFluidHandler();
        for(int i=0;i<tank.getTanks();i++) {
            var fluid=tank.getFluidInTank(i);if(!fluid.isEmpty())result.add(AEFluidKey.of(fluid),fluid.getAmount());
        }
        return result;
    }
    public KeyCounter exportableFluids() {
        return core?standalone.available():exportableFluids(internalFluids());
    }
    KeyCounter exportableFluids(KeyCounter contents) {
        if(core)return standalone.available();
        var result=new KeyCounter();result.addAll(contents);var controller=findFoundry();if(controller==null)return result;
        var claims=new KeyCounter();
        for(var pos:controller.getMultiblockData().peripherals())if(level.getBlockEntity(pos) instanceof FoundryBlockEntity host)
            for(var request:host.activeJobs())for(var entry:result)if(entry.getKey() instanceof AEFluidKey key)
                claims.add(key,request.claim(key));
        for(var entry:claims)result.remove(entry.getKey(),Math.min(result.get(entry.getKey()),entry.getLongValue()));
        result.removeZeros();return result;
    }
    public long exportStoredFluid(AEFluidKey key) {
        if(level==null || level.isClientSide() || hasQuarantinedState() || legacyExternalController || !getMainNode().isActive())return 0;
        long available=exportableFluids().get(key);if(available<=0)return 0;
        int accepted=(int)Math.min(Integer.MAX_VALUE,insertIntoME(key,available,Actionable.SIMULATE));if(accepted<=0)return 0;
        accepted=(int)Math.min(accepted,exportableFluids().get(key));if(accepted<=0)return 0;
        var controller=findFoundry();if(!core && controller==null)return 0;
        var tank=core?standalone:controller.getFluidHandler();
        var drained=core?key.toStack(standalone.drainStored(key,accepted)):tank.drain(key.toStack(accepted),FluidAction.EXECUTE);if(drained.isEmpty())return 0;
        long inserted=insertIntoME(key,drained.getAmount(),Actionable.MODULATE);
        int remainder=drained.getAmount()-(int)inserted;
        if(remainder>0) {
            int restored;
            if(core) { standalone.restoreStored(key,remainder);restored=remainder; }
            else restored=tank.fill(key.toStack(remainder),FluidAction.EXECUTE);
            // Preserve a remainder even if another capability callback filled the tank.
            if(restored<remainder)migrationReturns.add(key,remainder-restored);
        }
        if(controller!=null)controller.setChanged();saveChanges();return inserted;
    }
    public void exportAllStoredFluids() {
        if(hasQuarantinedState() || legacyExternalController)return;
        fluidExportRequested=!fluidExportRequested;nextFluidExport=Long.MIN_VALUE;saveChanges();
    }
    private void tickFluidExport() {
        if(!fluidExportRequested || level==null || !getMainNode().isActive() || level.getGameTime()<nextFluidExport)return;
        var keys=new ArrayList<AEFluidKey>();for(var entry:exportableFluids())if(entry.getKey() instanceof AEFluidKey key)keys.add(key);
        if(keys.isEmpty()) { fluidExportRequested=false;saveChanges();return; }
        boolean moved=false;int work=Math.min(4,keys.size());
        for(int i=0;i<work;i++)moved|=exportStoredFluid(keys.get(Math.floorMod(fluidExportCursor+i,keys.size())))>0;
        fluidExportCursor=Math.floorMod(fluidExportCursor+work,keys.size());
        nextFluidExport=level.getGameTime()+(moved?1:20);
    }
    /** Snapshot the controller's shared molten pool and currently meltable queued items; do not take ownership. */
    private KeyCounter controllerMaterials(FoundryControllerBlockEntity foundry) {
        var materials = new KeyCounter();
        var tank = foundry.getFluidHandler();
        for (int i = 0; i < tank.getTanks(); i++) {
            var fluid = tank.getFluidInTank(i);
            if (!fluid.isEmpty()) materials.add(AEFluidKey.of(fluid), fluid.getAmount());
        }
        int temp = temperature(foundry);
        var inventory = foundry.getItemHandler();
        for (int i = 0; i < inventory.getSlots(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            var recipe = CastingRecipes.melting(level, stack, temp);
            if (recipe != null) for (var fluid : recipe.value().result) {
                if (!fluid.isEmpty()) materials.add(AEFluidKey.of(fluid), (long) fluid.getAmount() * stack.getCount());
            }
        }
        var deductions=new KeyCounter();
        for(var entry:materials) if(entry.getKey() instanceof AEFluidKey fluid) deductions.add(fluid,Math.min(entry.getLongValue(),claimedByOthers(foundry,fluid)));
        for(var entry:deductions) materials.remove(entry.getKey(),entry.getLongValue());
        return materials;
    }
    private long claimedByOthers(FoundryControllerBlockEntity foundry, AEFluidKey fluid) {
        long claimed=0;
        for(var pos:foundry.getMultiblockData().peripherals()) if(level.getBlockEntity(pos) instanceof FoundryBlockEntity host)
            for(var request:host.activeJobs()) if(request!=job || host!=this) claimed+=request.claim(fluid);
        return claimed;
    }
    private int drainable(FoundryControllerBlockEntity foundry,int requested) {
        int available=foundry.getFluidHandler().drain(job.fluid.toStack(Integer.MAX_VALUE),FluidAction.SIMULATE).getAmount();
        long physical=0;
        for(var pos:foundry.getMultiblockData().peripherals()) if(level.getBlockEntity(pos) instanceof FoundryBlockEntity host)
            for(var request:host.activeJobs())if(request!=job || host!=this)physical+=request.physicalClaim(job.fluid);
        return (int)Math.min(requested,Math.max(0,available-physical));
    }
    boolean startJob(IPatternDetails pattern, KeyCounter[] holders) {
        if(hasQuarantinedState() || legacyExternalController || !migrationReturns.isEmpty())return false;
        if(!core) return startSingleJob(pattern,holders);
        if(!fuelManager.hasSource()) { status="missing_source";return false; }
        if(paused || level==null || !getMainNode().isActive()) return false;
        fuelManager.tick();
        for(int i=0;i<4;i++) if(lanes[i]==null && brokenLanes[i]==null) {
            job=null; damagedJob=null;
            boolean accepted=startSingleJob(pattern,holders);
            lanes[i]=job; laneStatus[i]=status;
            if(accepted)splitQueuedWork(job);
            selectDisplay(); return accepted;
        }
        return false;
    }
    private boolean startSingleJob(IPatternDetails pattern, KeyCounter[] holders) {
        if (paused || job!=null || damagedJob!=null || level == null || !getMainNode().isActive()) return false;
        var foundry = findFoundry();
        if (!core && foundry == null) { status = "no_foundry"; return false; }
        var storedCasts = new ItemStack[casts.size()];
        for (int i = 0; i < casts.size(); i++) storedCasts[i] = casts.getStackInSlot(i).copy();
        if(core) for(var request:activeJobs()) if(!request.consumeCast && !request.castFromPattern && request.castSlot>=0 && request.castSlot<storedCasts.length)
            storedCasts[request.castSlot].shrink(1);
        int temperature=core?fuelManager.limit():temperature(foundry);
        var materials=core?standalone.available():controllerMaterials(foundry);
        RejectedRequest rejected=null;
        if(core) {
            var version=CastingRecipes.version(level);
            if(rejectedRecipeVersion!=version) { rejectedRequests.clear();rejectedRecipeVersion=version; }
            var supplied=new KeyCounter();for(var holder:holders)supplied.addAll(holder);
            var available=new ArrayList<AvailableCast>();for(int i=0;i<storedCasts.length;i++)if(!storedCasts[i].isEmpty())available.add(new AvailableCast(i,new GenericStack(AEItemKey.of(storedCasts[i]),storedCasts[i].getCount())));
            rejected=new RejectedRequest(pattern.getDefinition(),snapshot(supplied),List.copyOf(available),snapshot(materials),temperature);
            if(rejectedRequests.getOrDefault(rejected,Long.MIN_VALUE)>level.getGameTime()) { status="invalid_pattern";return false; }
        }
        var planned = CastingPlanner.plan(level, storedCasts, pattern, holders,temperature,materials,core);
        if (planned == null) {
            if(rejected!=null)rejectedRequests.put(rejected,level.getGameTime()+20);
            status = "invalid_pattern"; return false;
        }
        // A native controller has one shared alloying pool. Different alloy chains must not mix.
        if(!core)for(var pos:foundry.getMultiblockData().peripherals()) if(level.getBlockEntity(pos) instanceof FoundryBlockEntity host)
            for(var request:host.activeJobs()) if(!request.recovering && request.remaining>0
                    && (planned.requiresAlloy || request.requiresAlloy) && !planned.fluid.equals(request.fluid)) {
                status="alloy_busy";return false;
            }
        if(core && ((planned.station!=2 || !returnAlloyToME) && planned.amountPerCast>CoreConfig.FLUID_CAPACITY.get()
                || planned.alloyBatchAmount>CoreConfig.FLUID_CAPACITY.get() || !standalone.allocate(planned))) { status="fluid_full";return false; }
        if (planned.castFromBank) {
            int left=planned.castCount;
            for(int slot=0;slot<casts.size() && left>0;slot++)if(planned.cast.matches(casts.getStackInSlot(slot))) {
                var stored=casts.getStackInSlot(slot);int taken=Math.min(left,storedCasts[slot].getCount());
                if(taken<=0)continue;
                casts.setItemDirect(slot,stored.copyWithCount(stored.getCount()-taken));left-=taken;
            }
        }
        planned.returnFluidToME = returnAlloyToME;
        job = planned; for (var holder : holders) holder.clear(); status = "waiting_fluid"; saveChanges(); return true;
    }
    private long insertIntoME(AEKey what, long amount, Actionable mode) {
        var grid = getMainNode().getGrid();
        if (grid == null || !getMainNode().isActive()) return 0;
        return StorageHelper.poweredInsert(grid.getEnergyService(), grid.getStorageService().getInventory(),
                what, amount, IActionSource.ofMachine(this), mode);
    }
    private List<Integer> freeLanes() {
        var free=new ArrayList<Integer>();for(int i=0;i<lanes.length;i++)if(lanes[i]==null && brokenLanes[i]==null)free.add(i);return free;
    }
    private List<Integer> freeCastSlots(CastingJob source,int limit) {
        var result=new ArrayList<Integer>();
        if(source.cast==null || source.consumeCast) { for(int i=0;i<limit;i++)result.add(source.castSlot);return result; }
        for(int slot=0;slot<casts.size() && result.size()<limit;slot++)if(source.cast.matches(casts.getStackInSlot(slot))) {
            int available=casts.getStackInSlot(slot).getCount();
            for(var active:lanes)if(active!=null && !active.recovering && !active.consumeCast && !active.castFromPattern && active.castSlot==slot)available--;
            for(int i=0;i<available && result.size()<limit;i++)result.add(slot);
        }
        return result;
    }
    /** Divide independently valid input portions before any processing; no materials are invented. */
    private void splitQueuedWork(CastingJob source) {
        if(source==null || source.station==2 || source.remaining<2)return;
        var free=freeLanes();var castSlots=freeCastSlots(source,free.size());
        int count=Math.min(source.total,1+Math.min(free.size(),castSlots.size()));if(count<2)return;
        int cycles=source.total;
        for(var input:source.pending)if(input.amount()%cycles!=0)return;
        for(var entry:source.coreFluids)if(entry.getLongValue()%cycles!=0)return;
        if(source.fluidBuffer%cycles!=0)return;
        var parts=new ArrayList<CastingJob>();
        for(int i=0;i<count;i++) {
            int share=cycles/count+(i<cycles%count?1:0);
            var part=source.stationCopy(share,i==0?source.castSlot:castSlots.get(i-1));
            if(i==0) { part.castFromPattern=source.castFromPattern;if(!source.consumeCast)part.castCount=source.castCount; }
            for(var input:source.pending)part.pending.add(new GenericStack(input.what(),input.amount()/cycles*share));
            for(var entry:source.coreFluids)part.coreFluids.add(entry.getKey(),entry.getLongValue()/cycles*share);
            part.fluidBuffer=source.fluidBuffer/cycles*share;
            var budget=new KeyCounter();budget.addAll(part.coreFluids);budget.add(part.fluid,part.fluidBuffer);
            for(var input:part.pending) {
                if(input.what() instanceof AEFluidKey)budget.add(input.what(),input.amount());
                else if(input.what() instanceof AEItemKey item) {
                    var melt=CastingRecipes.meltingForCore(level,item.toStack(),fuelManager.limit());if(melt==null)return;
                    for(var fluid:melt.value().result)budget.add(AEFluidKey.of(fluid),(long)fluid.getAmount()*input.amount());
                }
            }
            if(!CastingPlanner.canProduce(level,budget,part.fluid,(long)share*part.amountPerCast))return;
            parts.add(part);
        }
        for(int i=0;i<lanes.length;i++)if(lanes[i]==source) { lanes[i]=parts.getFirst();break; }
        for(int i=1;i<parts.size();i++) { lanes[free.get(i-1)]=parts.get(i);laneStatus[free.get(i-1)]="waiting_fluid"; }
        saveChanges();
    }
    /** Indivisible alloy batches can still distribute their actual molten output to free stations. */
    private void splitMoltenWork(CastingJob source) {
        if(source.station==2 || source.recovering || source.remaining<2)return;
        var free=freeLanes();if(free.isEmpty())return;
        var castSlots=freeCastSlots(source,free.size());int count=Math.min(free.size(),castSlots.size());
        for(int i=0;i<count && source.remaining>1;i++) {
            int share=(int)Math.min(source.remaining/(count-i+1),source.coreFluids.get(source.fluid)/source.amountPerCast);
            if(share<=0)break;
            var child=source.stationCopy(share,castSlots.get(i));child.requiresAlloy=false;
            long amount=(long)share*source.amountPerCast;child.coreFluids.add(source.fluid,amount);
            source.coreFluids.remove(source.fluid,amount);source.coreFluids.removeZeros();
            source.remaining-=share;source.total-=share;if(source.consumeCast)source.castCount-=share;
            lanes[free.get(i)]=child;laneStatus[free.get(i)]="waiting_fluid";saveChanges();
        }
    }
    /** Keep the cooled cast in its station until ME accepts it; there is no output inventory. */
    private boolean outputReady() {
        if(level.getGameTime()<job.nextOutputAttempt) { status="output_full";return false; }
        int left=job.outputPerCast-job.outputDelivered;
        if(insertIntoME(job.output,left,Actionable.SIMULATE)<left) {
            job.nextOutputAttempt=level.getGameTime()+20;status="output_full";return false;
        }
        job.nextOutputAttempt=0;return true;
    }
    private void deliverCast() {
        int left = job.outputPerCast - job.outputDelivered;
        if(!outputReady())return;
        int inserted = (int) insertIntoME(job.output, left, Actionable.MODULATE);
        if (inserted <= 0) { job.nextOutputAttempt=level.getGameTime()+20;status = "output_full"; return; }
        if (job.outputDelivered == 0) {
            if (job.consumeCast) job.castCount--;
            job.poured = 0;
        }
        job.outputDelivered += inserted;
        if (job.outputDelivered == job.outputPerCast) {
            job.outputDelivered = 0; job.cooling = -1; job.remaining--;
            status = "pouring";
        } else status = "output_full";
        saveChanges();
    }
    public void serverTick() {
        if(hasQuarantinedState())return;
        if(level==null || level.isClientSide())return;
        if(core)fuelManager.tick();
        // Legacy controller claims lock processing, not the independent local heater.
        // Never touch old controller materials until explicit recovery resolves ownership.
        if(legacyExternalController)return;
        tickFluidExport();
        if(!migrationReturns.isEmpty()) {
            if(migrationRecoveryActive) {
                var sent=new KeyCounter();for(var entry:migrationReturns) {
                    long amount=insertIntoME(entry.getKey(),entry.getLongValue(),Actionable.MODULATE);
                    if(amount>0)sent.add(entry.getKey(),amount);
                }
                for(var entry:sent)migrationReturns.remove(entry.getKey(),entry.getLongValue());migrationReturns.removeZeros();
                if(!sent.isEmpty())saveChanges();
            }
            return;
        }
        if(!core) { fuelManager.tickControllerEnergy();tickSingle(); return; }
        for(int n=0;n<4;n++) {
            int i=(tickStart+n)%4; job=lanes[i]; damagedJob=brokenLanes[i]; status=laneStatus[i];
            tickSingle(); lanes[i]=job; brokenLanes[i]=damagedJob; laneStatus[i]=status;
        }
        tickStart=(tickStart+1)%4; selectDisplay();
    }
    private void tickSingle() {
        if (level == null || level.isClientSide()) return;
        if (!getMainNode().isActive()) { status = "offline"; return; }
        if (damagedJob != null) { status = "damaged_job"; return; }
        // Recovery returns already-owned materials even when the source module was removed.
        if (core && !fuelManager.hasSource() && (job == null || !job.recovering)) { status = "missing_source"; return; }
        if (job == null) { status = paused ? "paused" : !core && findFoundry() == null ? "no_foundry" : "idle"; return; }
        if (!core && !job.recovering && findFoundry() == null) { status = "no_foundry"; return; }
        var grid = getMainNode().getGrid();
        if (grid == null) { status = "offline"; return; }
        // Only the active request owns material; leftovers go directly back into the ME network.
        if (job.remaining == 0) {
            if(level.getGameTime()<job.nextOutputAttempt) { status="output_full";return; }
            if(job.recovering && !core && !job.forwardedItems.isEmpty()) {
                // A temporarily unformed/unloaded controller must not erase the handoff ledger.
                if(findFoundry()==null) { status="no_foundry";return; }
                recoverForwardedItems(job);saveChanges();
            }
            if (!job.recovering && !routePendingInputs()) { status = core?standalone.stage:"foundry_full"; return; }
            if (!returnLeftovers()) { job.nextOutputAttempt=level.getGameTime()+20;status = "output_full"; return; }
            job = null; status = "idle"; saveChanges(); return;
        }
        if (job.station == 2) { tickAlloy(); return; }
        if (job.outputDelivered > 0) { deliverCast(); return; }
        // A full network cannot finish the cooled cast. Avoid addon recipe dispatch,
        // melting and AE work charges until storage can accept the actual output.
        if(job.cooling==0 && !outputReady())return;
        var activeCast = job.consumeCast || job.castFromPattern ? (job.cast == null ? ItemStack.EMPTY : job.cast.toStack()) : (job.castSlot < 0 ? ItemStack.EMPTY : casts.getStackInSlot(job.castSlot));
        if (!validateCastingRecipe(activeCast)) {
            status = "recipe_changed"; return;
        }
        double energy = CastingSpeed.energyPerTick(getSpeedCards());
        if (grid.getEnergyService().extractAEPower(energy, Actionable.SIMULATE, PowerMultiplier.CONFIG) < energy - 0.0001) {
            status = "no_power"; return;
        }
        grid.getEnergyService().extractAEPower(energy, Actionable.MODULATE, PowerMultiplier.CONFIG);
        // Keep pouring while inputs are still queued, so large batches cannot deadlock a full foundry.
        boolean inputsSent = routePendingInputs();
        if (job.cooling < 0) {
            int pouredBefore = job.poured;
            int wanted = Math.min(CastingSpeed.pourRate(getSpeedCards()), job.amountPerCast - job.poured);
            if (wanted > 0) {
                var foundry = findFoundry();
                if (!core && foundry == null) { status = "no_foundry"; saveChanges(); return; }
                wanted=core?standalone.amount(job,wanted):drainable(foundry,wanted);
                var drained = wanted==0 ? net.neoforged.neoforge.fluids.FluidStack.EMPTY : core?job.fluid.toStack(standalone.drain(job,wanted)):foundry.getFluidHandler().drain(job.fluid.toStack(wanted), FluidAction.EXECUTE);
                job.poured += drained.getAmount();
                job.release(job.fluid,drained.getAmount());
                if(foundry!=null)foundry.setChanged();
            }
            status = job.poured == job.amountPerCast ? "cooling" : job.poured > pouredBefore ? "pouring" : core?standalone.stage:nativeProcessingStatus(inputsSent);
            if (job.poured == job.amountPerCast) job.cooling = CastingSpeed.coolingWork(job.amountPerCast, Config.foundryCoolingModifier);
            saveChanges();
            return;
        }
        if (job.cooling > 0) {
            job.cooling = Math.max(0, job.cooling - CastingSpeed.multiplier(getSpeedCards()));
            status = "cooling"; saveChanges(); return;
        }
        deliverCast();
    }

    private String nativeProcessingStatus(boolean inputsSent) {
        var foundry=findFoundry();if(foundry==null)return "no_foundry";
        var inventory=foundry.getItemHandler();boolean melting=false;
        for(int i=0;i<inventory.getSlots();i++)if(!inventory.getStackInSlot(i).isEmpty()) { melting=true;break; }
        if(melting || job.pending.stream().anyMatch(p->p.what() instanceof AEItemKey)) {
            if(foundry.getCoilType()==cy.jdkdigital.productivemetalworks.util.CoilType.ENERGY && foundry.getPower()==0)return "missing_energy";
            int heat=temperature(foundry);if(heat<0)return "missing_fuel";
            for(var pending:job.pending)if(pending.what() instanceof AEItemKey item && CastingRecipes.melting(level,item.toStack(),heat)==null)return "wrong_tier";
            return inputsSent?"waiting_melt":"foundry_full";
        }
        return !inputsSent?"foundry_full":job.requiresAlloy?"alloying":"waiting_fluid";
    }
    private boolean validateCastingRecipe(ItemStack activeCast) {
        var version=CastingRecipes.version(level);var castKey=AEItemKey.of(activeCast);long now=level.getGameTime();
        boolean finishing=job.cooling==0 && job.recipeValid;
        if(!finishing && job.validatedRecipes==version && Objects.equals(job.validatedCast,castKey)
                && job.validatedCastCount==activeCast.getCount() && now<job.validateAfter)return job.recipeValid;
        var live=level.getRecipeManager().byKey(job.recipeId).orElse(null);
        var fluid=job.fluid.toStack(job.amountPerCast);
        var recipe=job.compatibilityRecipe?CastingRecipes.compatibility(level,activeCast,fluid,job.station)
                :live!=null && live.value() instanceof ItemCastingRecipe casting?casting:null;
        boolean valid=recipe!=null && recipe.consumeCast==job.consumeCast && recipe.matches(activeCast,fluid,level)
                && recipe.getFluidAmount(level,fluid)==job.amountPerCast;
        if(valid) {
            var result=recipe.getResultItem(level,fluid);
            valid=job.output.equals(AEItemKey.of(result)) && result.getCount()==job.outputPerCast;
        }
        job.validatedRecipes=version;job.validatedCast=castKey;job.validatedCastCount=activeCast.getCount();job.recipeValid=valid;
        job.validateAfter=now+(recipe!=null && recipe.getClass()==ItemCastingRecipe.class?20:4);
        return valid;
    }

    /** Native controller does the alloying; only actual, drained fluid can be delivered to ME. */
    private void tickAlloy() {
        if(level.getGameTime()<job.nextOutputAttempt) { status="output_full";return; }
        var foundry = findFoundry(); if (!core && foundry == null) { status = "no_foundry"; return; }
        var live = level.getRecipeManager().byKey(job.recipeId).orElse(null);
        if (!(core && job.alloyBatchAmount==0) && (live == null || !(live.value() instanceof cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe recipe)
                || !job.fluid.equals(AEFluidKey.of(recipe.result)) || recipe.result.getAmount() != job.alloyBatchAmount)) {
            status = "recipe_changed"; return;
        }
        boolean sent = routePendingInputs();
        int left = job.outputPerCast - job.outputDelivered;
        if (!job.returnFluidToME) {
            int present = core?standalone.amount(job,left):drainable(foundry,left);
            status = core?standalone.stage:sent ? "alloying" : "foundry_full";
            if (sent && present >= left) { job.remaining = 0; status = "idle"; saveChanges(); }
            return;
        }
        // A transient remainder can exist only if ME capacity changes between simulation and execution.
        // It is owned job state, not an output inventory, and is returned by recovery/save handling.
        if (job.poured > 0) {
            int inserted = (int)insertIntoME(job.fluid, job.poured, Actionable.MODULATE);
            job.poured -= inserted; job.outputDelivered += inserted; saveChanges();
            if (job.poured > 0) { job.nextOutputAttempt=level.getGameTime()+20;status = "output_full"; return; }
            left = job.outputPerCast - job.outputDelivered;
        }
        if (left > 0) {
            int available = core?standalone.amount(job,Math.min(left,1000)):drainable(foundry,Math.min(left,1000));
            if (available==0) { status = core?standalone.stage:sent ? "alloying" : "foundry_full"; return; }
            int accepted = (int)insertIntoME(job.fluid, available, Actionable.SIMULATE);
            if (accepted == 0) { job.nextOutputAttempt=level.getGameTime()+20;status = "output_full"; return; }
            var drained = core?job.fluid.toStack(standalone.drain(job,accepted)):foundry.getFluidHandler().drain(job.fluid.toStack(accepted), FluidAction.EXECUTE);
            int inserted = (int)insertIntoME(job.fluid, drained.getAmount(), Actionable.MODULATE);
            job.outputDelivered += inserted; job.poured += drained.getAmount() - inserted;
            job.release(job.fluid,drained.getAmount());
            if(foundry!=null)foundry.setChanged(); saveChanges();
        }
        if (job.outputDelivered == job.outputPerCast && job.poured == 0) {
            job.outputDelivered = 0; job.remaining = 0; status = "idle"; saveChanges();
        } else status = "alloying";
    }

    private boolean routePendingInputs() {
        if(core) { boolean sent=standalone.process(job);splitMoltenWork(job);return sent; }
        if (job.pending.isEmpty() && job.fluidBuffer == 0) return true;
        var foundry = findFoundry();
        if (foundry == null) return false;
        if (job.fluidBuffer > 0) {
            int sent = foundry.getFluidHandler().fill(job.fluid.toStack((int) Math.min(job.fluidBuffer, 1000)), FluidAction.EXECUTE);
            job.fluidBuffer -= sent; if (sent > 0) { foundry.setChanged(); saveChanges(); }
        }
        for (int i = 0; i < job.pending.size(); i++) {
            var pending = job.pending.get(i);
            long sent = 0;
            if (pending.what() instanceof AEItemKey item) {
                if (!canMelt(item.toStack())) continue;
                int amount = (int) Math.min(pending.amount(), item.toStack().getMaxStackSize());
                var remainder = ItemHandlerHelper.insertItemStacked(foundry.getItemHandler(), item.toStack(amount), false);
                sent = amount - remainder.getCount();
                if(sent>0)job.forwardedItems.add(item,sent);
            } else if (pending.what() instanceof AEFluidKey fluid) {
                sent = foundry.getFluidHandler().fill(fluid.toStack((int) Math.min(pending.amount(), 1000)), FluidAction.EXECUTE);
            }
            if (sent > 0) {
                if (sent == pending.amount()) { job.pending.remove(i); i--; }
                else job.pending.set(i, new GenericStack(pending.what(), pending.amount() - sent));
                foundry.setChanged(); saveChanges();
            }
        }
        return job.pending.isEmpty() && job.fluidBuffer == 0;
    }

    private boolean returnLeftovers() {
        if(core) {
            if(job.station==2 && !job.returnFluidToME && !job.recovering)standalone.retain(job);
            else {
                var removed=new KeyCounter();for(var entry:job.coreFluids) {
                    long sent=insertIntoME(entry.getKey(),entry.getLongValue(),Actionable.MODULATE);if(sent>0)removed.add(entry.getKey(),sent);
                }
                for(var entry:removed)job.coreFluids.remove(entry.getKey(),entry.getLongValue());job.coreFluids.removeZeros();if(!removed.isEmpty())saveChanges();
                if(!job.coreFluids.isEmpty())return false;
            }
        }
        for (int i = 0; i < job.pending.size(); i++) {
            var stack = job.pending.get(i);
            long inserted = insertIntoME(stack.what(), stack.amount(), Actionable.MODULATE);
            if (inserted == stack.amount()) { job.pending.remove(i); i--; }
            else job.pending.set(i, new GenericStack(stack.what(), stack.amount() - inserted));
            if (inserted > 0) saveChanges();
        }
        if (job.fluidBuffer > 0) {
            long inserted = insertIntoME(job.fluid, job.fluidBuffer, Actionable.MODULATE);
            job.fluidBuffer -= inserted;if(inserted>0)saveChanges();
        }
        if (job.castCount > 0 && job.cast != null) {
            int returned = (int) insertIntoME(job.cast, job.castCount, Actionable.MODULATE);
            job.castCount -= returned; if (returned > 0) saveChanges();
        }
        return job.fluidBuffer == 0 && job.pending.isEmpty() && job.castCount == 0;
    }

    /** Return held material and proven unmelted controller inputs. Already melted shared stock stays there. */
    public void recoverOrResume() {
        if(hasQuarantinedState()) {
            loadTag(saveWithoutMetadata(level.registryAccess()),level.registryAccess());saveChanges();return;
        }
        if(legacyExternalController) {
            if(legacyController==null || !level.hasChunkAt(legacyController)
                    || !(level.getBlockEntity(legacyController) instanceof FoundryControllerBlockEntity))return;
            // Shared controller contents are NOT owned by this block. Recover only held material.
            for(var request:lanes)if(request!=null && !request.recovering)recoverRequest(request);
            legacyExternalController=false;paused=true;selectDisplay();saveChanges();return;
        }
        if(!migrationReturns.isEmpty()) { migrationRecoveryActive=true;paused=true;saveChanges();return; }
        if(core) {
            if(paused) { paused=false; saveChanges(); return; }
            paused=true;
            for(var request:lanes) if(request!=null && !request.recovering) recoverRequest(request);
            saveChanges(); selectDisplay(); return;
        }
        if (paused && (job == null || !job.recovering)) { paused = false; saveChanges(); return; }
        paused = true;
        if (job == null) { saveChanges(); return; }
        if (job.recovering) return;
        recoverRequest(job);
        status = "recovering"; saveChanges();
    }
    private void recoverRequest(CastingJob request) {
        if(!core)recoverForwardedItems(request);
        var owned=request.ownedMaterials();request.pending.clear();request.pending.addAll(owned);
        request.coreFluids.clear();request.meltWork=0;
        request.reservations.clear();request.inputReservations.clear();request.fluidBuffer=0;request.poured=0;request.castCount=0;
        request.remaining=0;request.outputDelivered=0;request.recovering=true;
    }
    private void recoverForwardedItems(CastingJob request) {
        var foundry=findFoundry();if(foundry==null)return;
        if(!(foundry.getItemHandler() instanceof cy.jdkdigital.productivemetalworks.util.TickingSlotInventoryHandler inventory))return;
        for(var entry:request.forwardedItems) {
            if(!(entry.getKey() instanceof AEItemKey key))continue;
            long available=0,otherClaims=0;
            for(int slot=0;slot<inventory.getSlots();slot++)if(key.matches(inventory.getStackInSlot(slot)))available+=inventory.getStackInSlot(slot).getCount();
            for(var pos:foundry.getMultiblockData().peripherals())if(level.getBlockEntity(pos) instanceof FoundryBlockEntity host)
                for(var other:host.activeJobs())if(other!=request)otherClaims+=other.forwardedItems.get(key);
            long wanted=Math.min(entry.getLongValue(),Math.max(0,available-otherClaims));
            for(int slot=0;slot<inventory.getSlots() && wanted>0;slot++)if(key.matches(inventory.getStackInSlot(slot))) {
                var extracted=inventory.extractItem(slot,(int)Math.min(wanted,Integer.MAX_VALUE),false,false);
                if(!extracted.isEmpty()) { request.pending.add(new GenericStack(key,extracted.getCount()));wanted-=extracted.getCount();foundry.setChanged(); }
            }
        }
        request.forwardedItems.clear();
    }

    @Override public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("foundryDataVersion",FoundryMigration.VERSION);
        if(migrationBackup!=null)tag.put("migrationBackup",migrationBackup.copy());
        if(migrationQuarantine!=null)tag.put("migrationQuarantine",migrationQuarantine.copy());
        tag.putBoolean("legacyExternalController",legacyExternalController);
        if(legacyController!=null)tag.putLong("legacyController",legacyController.asLong());
        var recovered=new net.minecraft.nbt.ListTag();for(var entry:migrationReturns)if(entry.getLongValue()>0)recovered.add(GenericStack.writeTag(registries,new GenericStack(entry.getKey(),entry.getLongValue())));
        tag.put("migrationReturns",recovered);tag.putBoolean("migrationRecoveryActive",migrationRecoveryActive);
        if (controllerPosition != null) tag.putLong("controller", controllerPosition.asLong());
        tag.putBoolean("paused", paused);
        tag.putBoolean("returnAlloyToME", returnAlloyToME);
        tag.putBoolean("fluidExportRequested",fluidExportRequested);
        casts.writeToNBT(tag, "casts", registries); upgrades.writeToNBT(tag, "upgrades", registries);
        if(core) {
            var slots=new net.minecraft.nbt.ListTag();
            for(int i=0;i<4;i++) {
                var entry=new CompoundTag();
                if(lanes[i]!=null) entry.put("job",lanes[i].save(registries));
                else if(brokenLanes[i]!=null) entry.put("job",brokenLanes[i].copy());
                slots.add(entry);
            }
            tag.put("stations",slots);tag.putInt("selectedStation",selectedLane);capacitors.writeToNBT(tag,"capacitors",registries);fuelManager.save(tag);standalone.save(tag,registries);
        } else if (job != null) tag.put("castingJob", job.save(registries));
        else if (damagedJob != null) tag.put("castingJob", damagedJob.copy());
    }

    @Override public void loadTag(CompoundTag tag, HolderLookup.Provider registries) {
        var original=tag.contains("migrationQuarantine")?tag.getCompound("migrationQuarantine").copy():tag.copy();
        clearContent();
        try { tag=FoundryMigration.prepare(original,core,casts.size(),registries); }
        catch(RuntimeException unsupported) { migrationQuarantine=original;paused=true;status="migration_unresolved";return; }
        super.loadTag(tag, registries);
        migrationBackup=tag.contains("migrationBackup")?tag.getCompound("migrationBackup").copy():null;
        legacyExternalController=core && tag.getBoolean("legacyExternalController");
        legacyController=tag.contains("legacyController")?BlockPos.of(tag.getLong("legacyController")):null;
        for(var raw:tag.getList("migrationReturns",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var material=GenericStack.readTag(registries,(CompoundTag)raw);if(material!=null && material.amount()>0)migrationReturns.add(material.what(),material.amount());
        }
        migrationRecoveryActive=tag.getBoolean("migrationRecoveryActive");
        controllerPosition = tag.contains("controller") ? BlockPos.of(tag.getLong("controller")) : null;
        paused = tag.getBoolean("paused");
        returnAlloyToME = !tag.contains("returnAlloyToME") || tag.getBoolean("returnAlloyToME");
        fluidExportRequested=tag.getBoolean("fluidExportRequested");nextFluidExport=Long.MIN_VALUE;fluidExportCursor=0;
        casts.readFromNBT(tag, "casts", registries); upgrades.readFromNBT(tag, "upgrades", registries);
        normalizeSavedCasts();
        job = null; damagedJob = null;
        if(core) {
            capacitors.clear();capacitors.readFromNBT(tag,"capacitors",registries);standalone.load(tag,registries);
            Arrays.fill(lanes,null);Arrays.fill(brokenLanes,null);Arrays.fill(laneStatus,"idle");
            var slots=tag.getList("stations",net.minecraft.nbt.Tag.TAG_COMPOUND);
            for(int i=0;i<Math.min(4,slots.size());i++) if(slots.getCompound(i).contains("job")) {
                var saved=slots.getCompound(i).getCompound("job");
                try { lanes[i]=CastingJob.load(registries,saved); } catch(RuntimeException invalid) { brokenLanes[i]=saved.copy(); }
            }
            selectedLane=Math.clamp(tag.getInt("selectedStation"),0,3);fuelManager.load(tag);selectDisplay();return;
        }
        if (tag.contains("castingJob")) {
            try { job = CastingJob.load(registries, tag.getCompound("castingJob")); }
            catch (RuntimeException invalid) { damagedJob = tag.getCompound("castingJob").copy(); }
        }
    }

    @Override public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        super.addAdditionalDrops(level, pos, drops);
        if(core || hasQuarantinedState() || damagedJob!=null || !migrationReturns.isEmpty())return; // Complete state is packed in its item.
        for (var stack : casts) if (!stack.isEmpty()) drops.add(stack.copy());
        for (var stack : upgrades) if (!stack.isEmpty()) drops.add(stack.copy());
        for(var request:activeJobs()) for(var material:request.ownedMaterials()) material.what().addDrops(material.amount(),drops,level,pos);
    }
    private void normalizeSavedCasts() {
        var excess=new ArrayList<ItemStack>();
        for(int i=0;i<casts.size();i++) {
            var stack=casts.getStackInSlot(i);
            if(stack.getCount()>4) { excess.add(stack.copyWithCount(stack.getCount()-4));casts.setItemDirect(i,stack.copyWithCount(4)); }
        }
        for(var stack:excess) {
            var remainder=casts.addItems(stack);
            if(!remainder.isEmpty())migrationReturns.add(AEItemKey.of(remainder),remainder.getCount());
        }
    }

    @Override public void exportSettings(appeng.util.SettingsFrom mode, net.minecraft.core.component.DataComponentMap.Builder builder, Player player) {
        super.exportSettings(mode,builder,player);
        if((core || hasQuarantinedState() || damagedJob!=null || !migrationReturns.isEmpty()) && mode==appeng.util.SettingsFrom.DISMANTLE_ITEM) {
            var tag=new CompoundTag();tag.put(core?"standaloneCore":"preservedFoundryState",saveWithoutMetadata(level.registryAccess()));
            builder.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));
        }
    }
    @Override public void importSettings(appeng.util.SettingsFrom mode, net.minecraft.core.component.DataComponentMap input, Player player) {
        super.importSettings(mode,input,player);
        if(mode==appeng.util.SettingsFrom.DISMANTLE_ITEM) {
            var data=input.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if(data!=null && data.copyTag().contains(core?"standaloneCore":"preservedFoundryState")) { loadTag(data.copyTag().getCompound(core?"standaloneCore":"preservedFoundryState"),level.registryAccess());saveChanges(); }
            else if(core && data!=null && data.copyTag().contains("coreFuel")) { fuelManager.load(data.copyTag().getCompound("coreFuel"));saveChanges(); }
        }
    }

    @Override public void clearContent() {
        super.clearContent(); casts.clear(); upgrades.clear(); job = null; damagedJob = null;
        Arrays.fill(lanes,null);Arrays.fill(brokenLanes,null);if(core) { capacitors.clear();fuelManager.clear();standalone.clear(); }
        migrationQuarantine=null;migrationBackup=null;legacyExternalController=false;legacyController=null;
        migrationReturns.clear();migrationRecoveryActive=false;
    }
}
