package dev.mehdi.appliedmetalworks.compat;
import dev.mehdi.appliedmetalworks.*;
import appeng.api.stacks.GenericStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin
public final class FoundryJadePlugin implements IWailaPlugin {
    @Override public void register(IWailaCommonRegistration registration) { registration.registerBlockDataProvider(Provider.INSTANCE,FoundryBlockEntity.class); }
    @Override public void registerClient(IWailaClientRegistration registration) { registration.registerBlockComponent(Provider.INSTANCE,FoundryBlock.class); }
    public enum Provider implements IBlockComponentProvider,IServerDataProvider<BlockAccessor> {
        INSTANCE;
        @Override public ResourceLocation getUid() { return AppliedMetalworks.id("foundry"); }
        @Override public void appendServerData(CompoundTag tag,BlockAccessor accessor) {
            if(accessor.getBlockEntity() instanceof FoundryBlockEntity host)tag.put("AppliedMetalworks",JadeInfo.snapshot(host,accessor.getLevel().registryAccess()));
        }
        @Override public void appendTooltip(ITooltip tooltip,BlockAccessor accessor,IPluginConfig config) {
            var tag=accessor.getServerData().getCompound("AppliedMetalworks");if(tag.isEmpty())return;
            if(tag.getBoolean("core")) {
                tooltip.add(Component.translatable("jade.appliedmetalworks.temperature",tag.getInt("temperature")));
                tooltip.add(Component.translatable("jade.appliedmetalworks.stations",tag.getInt("active")));
            }
            var output=GenericStack.readTag(accessor.getLevel().registryAccess(),tag.getCompound("recipe"));
            if(output!=null)tooltip.add(Component.translatable("jade.appliedmetalworks.recipe",tag.getInt("selected"),output.what().getDisplayName(),output.amount()));
            String status=tag.getString("status");
            if(!status.equals("idle") && !status.equals("pouring") && !status.equals("cooling"))tooltip.add(Component.translatable("status.appliedmetalworks."+status));
        }
    }
}
