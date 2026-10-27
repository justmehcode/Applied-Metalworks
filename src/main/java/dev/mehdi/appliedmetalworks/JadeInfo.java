package dev.mehdi.appliedmetalworks;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Extra Jade information only. Generic Jade providers own FE and fluid displays. */
public final class JadeInfo {
    public static CompoundTag snapshot(FoundryBlockEntity host,HolderLookup.Provider registries) {
        var tag=new CompoundTag();tag.putBoolean("core",host.isCore());tag.putInt("active",host.getActiveLanes());
        tag.putString("status",host.getStatus());tag.putInt("selected",host.getSelectedLane()+1);
        if(host.isCore())tag.putInt("temperature",host.getStandaloneFoundry().temperature());
        var output=host.getRecipeOutput();if(output!=null)tag.put("recipe",GenericStack.writeTag(registries,output));
        return tag;
    }
    private JadeInfo() {}
}
