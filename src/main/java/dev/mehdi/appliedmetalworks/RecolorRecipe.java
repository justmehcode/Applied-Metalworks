package dev.mehdi.appliedmetalworks;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Same-family appearance recipes preserve the core's saved fuel and FE components. */
public final class RecolorRecipe extends ShapelessRecipe {
    RecolorRecipe(ShapelessRecipe original) {
        super(original.getGroup(),original.category(),original.getResultItem(null),original.getIngredients());
    }
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registries) {
        var result=super.assemble(input,registries);
        for(var stack:input.items()) if(stack.getItem() instanceof net.minecraft.world.item.BlockItem item
                && item.getBlock() instanceof FoundryBlock) {
            var data=stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            var name=stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
            if(data!=null)result.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,data);
            if(name!=null)result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,name);
            break;
        }
        return result;
    }
    @Override public RecipeSerializer<?> getSerializer() { return AppliedMetalworks.RECOLOR.get(); }
    public static final class Serializer implements RecipeSerializer<RecolorRecipe> {
        private final ShapelessRecipe.Serializer vanilla=new ShapelessRecipe.Serializer();
        @Override public MapCodec<RecolorRecipe> codec() { return vanilla.codec().xmap(RecolorRecipe::new,r->r); }
        @Override public StreamCodec<RegistryFriendlyByteBuf,RecolorRecipe> streamCodec() { return vanilla.streamCodec().map(RecolorRecipe::new,r->r); }
    }
}
