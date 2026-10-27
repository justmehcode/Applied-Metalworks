package dev.mehdi.appliedmetalworks;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.level.Level;

/** Explicit factory reset: retain only the machine item/appearance, never its saved components. */
public final class MachineResetRecipe extends CustomRecipe {
    public MachineResetRecipe(CraftingBookCategory category) { super(category); }
    private ItemStack machine(CraftingInput input) {
        ItemStack result=ItemStack.EMPTY;
        for(var stack:input.items())if(!stack.isEmpty()) {
            if(!result.isEmpty() || !(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof FoundryBlock))return ItemStack.EMPTY;
            result=stack;
        }
        return result;
    }
    @Override public boolean matches(CraftingInput input,Level level) { return !machine(input).isEmpty(); }
    @Override public ItemStack assemble(CraftingInput input,HolderLookup.Provider registries) {
        var stack=machine(input);return stack.isEmpty()?ItemStack.EMPTY:new ItemStack(stack.getItem());
    }
    @Override public boolean canCraftInDimensions(int width,int height) { return width>0 && height>0; }
    @Override public RecipeSerializer<?> getSerializer() { return AppliedMetalworks.RESET.get(); }
}
