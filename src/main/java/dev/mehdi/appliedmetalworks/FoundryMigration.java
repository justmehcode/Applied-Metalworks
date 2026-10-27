package dev.mehdi.appliedmetalworks;

import appeng.api.stacks.GenericStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;

/** Validate before loading anything; undecodable state stays intact instead of partly disappearing. */
final class FoundryMigration {
    static final int VERSION=1;
    static CompoundTag prepare(CompoundTag input,boolean core,int castSlots,HolderLookup.Provider registries) {
        var tag=input.copy();
        int version=tag.getInt("foundryDataVersion");
        if(version>VERSION)throw new IllegalArgumentException("Future foundry data version");
        validateInventory(tag,"casts",castSlots,registries);
        validateInventory(tag,"upgrades",4,registries);
        validateInventory(tag,"capacitors",core?1:0,registries);
        validateInventory(tag,"inputs",9,registries);
        validateList(tag,"outputs");validateList(tag,"migrationReturns");validateList(tag,"storedMolten");validateList(tag,"stations");
        for(var entry:tag.getList("outputs",Tag.TAG_COMPOUND))if(!((CompoundTag)entry).isEmpty())validateStack((CompoundTag)entry,registries);
        validateStacks(tag.getList("migrationReturns",Tag.TAG_COMPOUND),registries);
        validateFluids(tag.getList("storedMolten",Tag.TAG_COMPOUND),registries);
        if(tag.contains("castingJob"))validateJob(tag.getCompound("castingJob"),registries);
        var stations=tag.getList("stations",Tag.TAG_COMPOUND);
        if(stations.size()>4)throw new IllegalArgumentException("Too many stations");
        for(var entry:stations)if(((CompoundTag)entry).contains("job"))validateJob(((CompoundTag)entry).getCompound("job"),registries);
        // Schema 0: unversioned interfaces and controller/standalone cores released before 1.0.
        if(version==0) {
            var backup=tag.copy();backup.remove("migrationBackup");backup.remove("migrationQuarantine");
            tag.put("migrationBackup",backup);
            var returns=tag.getList("migrationReturns",Tag.TAG_COMPOUND).copy();
            for(var entry:tag.getList("inputs",Tag.TAG_COMPOUND)) {
                var item=ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),entry).result().orElseThrow();
                returns.add(GenericStack.writeTag(registries,new GenericStack(appeng.api.stacks.AEItemKey.of(item),item.getCount())));
            }
            for(var entry:tag.getList("outputs",Tag.TAG_COMPOUND))if(!((CompoundTag)entry).isEmpty())returns.add(entry.copy());
            tag.remove("inputs");tag.remove("outputs");
            if(!returns.isEmpty()) { tag.put("migrationReturns",returns);tag.putBoolean("paused",true); }
            if(core && tag.contains("controller") && !tag.contains("storedMolten")) {
                tag.putBoolean("legacyExternalController",true);
                tag.putLong("legacyController",tag.getLong("controller"));
                tag.putBoolean("paused",true);
            }
            tag.putInt("foundryDataVersion",1);
        }
        return tag;
    }
    private static void validateInventory(CompoundTag tag,String name,int size,HolderLookup.Provider registries) {
        if(!tag.contains(name))return;
        validateList(tag,name);
        var seen=new HashSet<Integer>();
        for(var raw:tag.getList(name,Tag.TAG_COMPOUND)) {
            var entry=(CompoundTag)raw;int slot=entry.getInt("Slot");
            var parsed=ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),entry).result();
            if(slot<0 || slot>=size || !seen.add(slot) || parsed.isEmpty() || parsed.get().isEmpty())
                throw new IllegalArgumentException("Unresolved inventory item");
        }
    }
    private static void validateJob(CompoundTag tag,HolderLookup.Provider registries) {
        for(String key:new String[]{"fluid","output","cast"})if(tag.contains(key))validateStack(tag.getCompound(key),registries);
        for(String key:new String[]{"pending","coreFluids","reservations","inputReservations","forwardedItems"}) { validateList(tag,key);validateStacks(tag.getList(key,Tag.TAG_COMPOUND),registries); }
        for(String key:new String[]{"coreFluids","reservations","inputReservations"})validateFluids(tag.getList(key,Tag.TAG_COMPOUND),registries);
        if(tag.contains("cast") && !(GenericStack.readTag(registries,tag.getCompound("cast")).what() instanceof appeng.api.stacks.AEItemKey))throw new IllegalArgumentException("Invalid cast key type");
        CastingJob.load(registries,tag);
    }
    private static void validateStacks(ListTag tags,HolderLookup.Provider registries) {
        for(var entry:tags)validateStack((CompoundTag)entry,registries);
    }
    private static void validateList(CompoundTag tag,String key) {
        if(tag.contains(key) && (!(tag.get(key) instanceof ListTag list) || !list.isEmpty() && list.getElementType()!=Tag.TAG_COMPOUND))throw new IllegalArgumentException("Unsupported saved list format");
    }
    private static void validateFluids(ListTag tags,HolderLookup.Provider registries) {
        validateStacks(tags,registries);
        for(var raw:tags)if(!(GenericStack.readTag(registries,(CompoundTag)raw).what() instanceof appeng.api.stacks.AEFluidKey))throw new IllegalArgumentException("Invalid fluid key type");
    }
    private static void validateStack(CompoundTag tag,HolderLookup.Provider registries) {
        var decoded=GenericStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),tag).result();
        if(decoded.isEmpty() || decoded.get().amount()<0)throw new IllegalArgumentException("Unresolved material key");
    }
}
