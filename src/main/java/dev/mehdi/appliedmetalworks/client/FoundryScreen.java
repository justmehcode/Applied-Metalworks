package dev.mehdi.appliedmetalworks.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.menu.SlotSemantics;
import dev.mehdi.appliedmetalworks.FoundryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import java.util.List;

/** Compact AE2 inventory with a read-only Metalworks recipe overlay. */
public final class FoundryScreen extends AEBaseScreen<FoundryMenu> {
    private static final ResourceLocation FE_TEXTURE=ResourceLocation.fromNamespaceAndPath("productivemetalworks","textures/gui/container/foundry_controller.png");
    private static final GenericStack LAVA_ICON=new GenericStack(appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.LAVA),1);
    private int labelStored=Integer.MIN_VALUE,labelCapacity,labelTemperature=Integer.MIN_VALUE;
    private boolean labelLava;
    private String resourceLabel="";
    private Component temperatureLabel=Component.empty();
    private final IconButton recipeButton;
    private final IconButton alloyDestination;
    private boolean recipeOpen;
    private IconButton fluidButton;
    private boolean fluidsOpen;
    private boolean messagesInitialized,lastBusy,lastReturnAlloy;
    private String lastStatus;
    private net.minecraft.locale.Language messageLanguage;
    private final appeng.api.stacks.AEKey[] fluidNames=new appeng.api.stacks.AEKey[8];
    private final net.minecraft.util.FormattedCharSequence[] fluidNameLines=new net.minecraft.util.FormattedCharSequence[8];
    private net.minecraft.locale.Language fluidLanguage;
    public FoundryScreen(FoundryMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
        recipeButton = new IconButton(button -> { recipeOpen = !recipeOpen;if(fluidsOpen)menu.setFluidViewOpen(false);fluidsOpen=false; }) {
            @Override protected Icon getIcon() { return Icon.CRAFT_HAMMER; }
        };
        widgets.add("recipeButton", recipeButton);
        alloyDestination = addToLeftToolbar(new IconButton(button -> menu.toggleAlloyDestination()) {
            @Override protected Icon getIcon() { return menu.returnAlloyToME ? Icon.ARROW_UP : Icon.ARROW_DOWN; }
        });
        widgets.add("upgrades", new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE)));
        fluidButton=addToLeftToolbar(new IconButton(b -> { fluidsOpen=!fluidsOpen;menu.setFluidViewOpen(fluidsOpen);recipeOpen=false; }) { @Override protected Icon getIcon() { return Icon.VIEW_MODE_STORED; } });
    }
    @Override protected void updateBeforeRender() {
        super.updateBeforeRender();
        var language=net.minecraft.locale.Language.getInstance();
        boolean refresh=!messagesInitialized || messageLanguage!=language;
        if(fluidButton!=null && refresh) {
            fluidButton.setMessage(Component.translatable("gui.appliedmetalworks.stored_fluids"));
        }
        alloyDestination.active = !menu.busy;
        if(refresh || lastBusy!=menu.busy || lastReturnAlloy!=menu.returnAlloyToME)
        alloyDestination.setMessage(Component.translatable("gui.appliedmetalworks." + (menu.returnAlloyToME ? "alloy_to_me" : menu.core ? "alloy_keep_core" : "alloy_keep"))
                .append("\n").append(Component.translatable("gui.appliedmetalworks." + (menu.busy ? "destination_locked" : "destination_hint")))
                .append(menu.returnAlloyToME ? Component.empty() : Component.literal("\n").append(Component.translatable("gui.appliedmetalworks.keep_cpu_hint"))));
        recipeButton.active = menu.core || menu.busy || menu.paused;
        if(refresh || !java.util.Objects.equals(lastStatus,menu.status))
        recipeButton.setMessage(Component.translatable("gui.appliedmetalworks.show_recipe")
                .append("\n").append(Component.translatable("status.appliedmetalworks." + menu.status)));
        messagesInitialized=true;messageLanguage=language;
        lastBusy=menu.busy;lastReturnAlloy=menu.returnAlloyToME;lastStatus=menu.status;
        if (!menu.core && !menu.busy && !menu.paused) recipeOpen = false;
    }
    @Override public void drawBG(GuiGraphics g, int x, int y, int mouseX, int mouseY, float partialTick) {
        super.drawBG(g, x, y, mouseX, mouseY, partialTick);
        for (var semantic : new appeng.menu.SlotSemantic[]{FoundryMenu.CAST_STORAGE,FoundryMenu.CAPACITORS, SlotSemantics.PLAYER_INVENTORY, SlotSemantics.PLAYER_HOTBAR})
            for (var slot : menu.getSlots(semantic))
                Icon.SLOT_BACKGROUND.getBlitter().dest(x + slot.x - 1, y + slot.y - 1).blit(g);
        if(menu.core) {
            boolean lava=menu.fuelMode==2;
            drawResourceBar(g,x+32,y+110,lava?menu.storedLava:menu.storedEnergy,lava?menu.lavaCapacity:menu.energyCapacity,lava,""+(lava?"mB":"FE"));
            if(labelTemperature!=menu.temperature) { labelTemperature=menu.temperature;temperatureLabel=Component.literal(menu.temperature+" °C"); }
            g.drawString(font,temperatureLabel,x+8,y+132,0xff45455b,false);
        }
    }
    private void drawResourceBar(GuiGraphics g,int x,int y,int stored,int capacity,boolean lava,String unit) {
        g.fill(x,y,x+134,y+18,0xff45455b);
        g.fill(x+1,y+1,x+133,y+17,0xffa6a6bc);
        g.fill(x+2,y+2,x+132,y+16,0xff626279);
        int fill=(int)(130*Math.clamp(capacity==0?0:(double)stored/capacity,0,1));
        if(fill>0) {
            g.enableScissor(x+2,y+2,x+2+fill,y+16);
            for(int offset=0;offset<fill;offset+=16) {
                if(lava)drawKey(g,LAVA_ICON,x+2+offset,y+2);
                else g.blit(FE_TEXTURE,x+2+offset,y+2,239,59,16,14);
            }
            g.disableScissor();
        }
        if(stored!=labelStored || capacity!=labelCapacity || lava!=labelLava) {
            labelStored=stored;labelCapacity=capacity;labelLava=lava;
            resourceLabel=capacity==0?"—":stored+" / "+capacity+" "+unit;
        }
        g.drawCenteredString(font,resourceLabel,x+67,y+5,0xfff1f1f4);
    }
    private int recipeX() { return (width - 189) / 2; }
    private int recipeHeight() { return menu.core?124:102; }
    private int recipeY() { return (height - recipeHeight()) / 2; }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, recipeOpen || fluidsOpen ? -1000 : mouseX, recipeOpen || fluidsOpen ? -1000 : mouseY, partialTick);
        if(menu.core && !recipeOpen && !fluidsOpen && inside(mouseX,mouseY,leftPos+32,topPos+110,134,18))
            g.renderComponentTooltip(font,menu.legacyFe>0 || menu.legacyLava>0?List.of(Component.translatable("gui.appliedmetalworks.source."+(menu.fuelMode==1?"energy":menu.fuelMode==2?"lava":"none")),Component.translatable("gui.appliedmetalworks.legacy_reserve",menu.legacyFe,menu.legacyLava)):List.of(Component.translatable("gui.appliedmetalworks.source."+(menu.fuelMode==1?"energy":menu.fuelMode==2?"lava":"none")),Component.translatable("fuel.appliedmetalworks."+menu.fuelStatus)),mouseX,mouseY);
        if(fluidsOpen) { drawFluidInventory(g,mouseX,mouseY);return; }
        if (!recipeOpen) return;
        int x = recipeX(), y = recipeY();
        g.pose().pushPose(); g.pose().translate(0, 0, 500);
        g.fill(0, 0, width, height, 0x88000000);
        BackgroundGenerator.draw(189, recipeHeight(), g, x, y);
        if (menu.station == 2 && menu.recipeOutput != null) drawAlloyRecipe(g, x, y, mouseX, mouseY);
        if (menu.station != 2 && menu.recipeOutput != null && menu.recipeFluid != null) {
            var texture = ResourceLocation.fromNamespaceAndPath("productivemetalworks", "textures/gui/jei/" + (menu.station == 1 ? "block_casting" : "item_casting") + ".png");
            g.blit(texture, x + 12, y + 8, 0, 0, 165, 68, 165, 68);
            drawKey(g, menu.recipeFluid, x + 38, y + 34);
            // Match Metalworks' JEI stream and cast positions using the actual fluid renderer.
            g.pose().pushPose(); g.pose().translate(x + 80, y + 24, 0);
            g.pose().scale(6f / 16, (menu.recipeCast == null ? 26f : 10f) / 16, 1);
            drawKey(g, menu.recipeFluid, 0, 0); g.pose().popPose();
            drawKey(g, menu.recipeCast, x + 75, y + 34);
            Icon.SLOT_BACKGROUND.getBlitter().dest(x + 131, y + 33).blit(g);
            drawKey(g, menu.recipeOutput, x + 132, y + 34);
            double progress = menu.coolingTotal > 0 && menu.poured == menu.pourTotal
                    ? 1.0 - (double) menu.cooling / menu.coolingTotal
                    : menu.pourTotal == 0 ? 0 : (double) menu.poured / menu.pourTotal;
            g.fill(x + 12, y + 80, x + 151, y + 83, 0xff777789);
            g.fill(x + 12, y + 80, x + 12 + (int) (139 * Math.clamp(progress, 0, 1)), y + 83, 0xffa8b6ec);
            if (inside(mouseX, mouseY, x + 38, y + 34, 16, 16) || inside(mouseX, mouseY, x + 80, y + 24, 6, 26))
                g.renderComponentTooltip(font, List.of(menu.recipeFluid.what().getDisplayName(), Component.literal(menu.recipeFluid.amount() + " mB")), mouseX, mouseY);
            if (menu.recipeCast != null && inside(mouseX, mouseY, x + 75, y + 34, 16, 16))
                g.renderTooltip(font, menu.recipeCast.what().getDisplayName(), mouseX, mouseY);
            if (inside(mouseX, mouseY, x + 132, y + 34, 16, 16))
                g.renderComponentTooltip(font, List.of(menu.recipeOutput.what().getDisplayName(), Component.literal("x " + menu.recipeOutput.amount()), Component.translatable("gui.appliedmetalworks.batch", menu.totalCasts - menu.remaining, menu.totalCasts)), mouseX, mouseY);
            if (inside(mouseX, mouseY, x + 12, y + 78, 139, 10))
                g.renderTooltip(font, Component.translatable("status.appliedmetalworks." + menu.status), mouseX, mouseY);
        }
        Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter().dest(x + 165, y + 77).blit(g);
        (menu.paused ? Icon.ARROW_RIGHT : Icon.CLEAR).getBlitter().dest(x + 166, y + 79).blit(g);
        if (inside(mouseX, mouseY, x + 165, y + 77, 18, 20))
            g.renderComponentTooltip(font, List.of(Component.translatable("gui.appliedmetalworks." + (menu.paused ? "resume" : "recover")), Component.translatable("gui.appliedmetalworks.recover_hint")), mouseX, mouseY);
        if(menu.core) {
            for(int nx:new int[]{12,159})Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter().dest(x+nx,y+100).blit(g);
            Icon.ARROW_LEFT.getBlitter().dest(x+13,y+102).blit(g);
            Icon.ARROW_RIGHT.getBlitter().dest(x+160,y+102).blit(g);
            g.drawCenteredString(font,Component.translatable("gui.appliedmetalworks.station_number",menu.selectedStation+1),x+94,y+106,0xff45455b);
        }
        g.pose().popPose();
    }
    private void drawFluidInventory(GuiGraphics g,int mouseX,int mouseY) {
        int x=(width-189)/2,y=(height-170)/2;
        g.pose().pushPose();g.pose().translate(0,0,500);g.fill(0,0,width,height,0x88000000);BackgroundGenerator.draw(189,170,g,x,y);
        g.drawString(font,Component.translatable("gui.appliedmetalworks.stored_fluids"),x+8,y+7,0xff45455b,false);
        var language=net.minecraft.locale.Language.getInstance();
        if(fluidLanguage!=language) { java.util.Arrays.fill(fluidNames,null);fluidLanguage=language; }
        for(int i=0;i<8;i++) {
            var row=menu.fluidAt(i);if(row==null)continue;var key=row.what();long available=menu.fluidAvailableAt(i);
            int rowY=y+22+i*16;drawKey(g,row,x+8,rowY);
            if(!key.equals(fluidNames[i])) { fluidNames[i]=key;fluidNameLines[i]=font.split(key.getDisplayName(),127).getFirst(); }
            g.drawString(font,fluidNameLines[i],x+28,rowY,0xff45455b,false);
            g.drawString(font,row.amount()+" mB",x+28,rowY+8,0xff696979,false);
            if(available>0 && !menu.quarantined)Icon.ARROW_UP.getBlitter().dest(x+164,rowY).blit(g);
            if(inside(mouseX,mouseY,x+8,rowY,172,16))g.renderComponentTooltip(font,List.of(key.getDisplayName(),
                    Component.translatable("gui.appliedmetalworks.fluid_available",available),
                    Component.translatable("gui.appliedmetalworks.fluid_reserved",row.amount()-available),
                    Component.translatable("gui.appliedmetalworks.export_fluid")),mouseX,mouseY);
        }
        Icon.TOOLBAR_BUTTON_BACKGROUND.getBlitter().dest(x+8,y+149).blit(g);
        (menu.exportingFluids?Icon.CLEAR:Icon.ARROW_UP).getBlitter().dest(x+9,y+151).blit(g);
        Icon.ARROW_LEFT.getBlitter().dest(x+139,y+151).blit(g);Icon.ARROW_RIGHT.getBlitter().dest(x+163,y+151).blit(g);
        g.drawString(font,(menu.fluidPage+1)+" / "+menu.fluidPages,x+80,y+154,0xff45455b,false);
        if(inside(mouseX,mouseY,x+8,y+149,18,20))g.renderComponentTooltip(font,List.of(
                Component.translatable("gui.appliedmetalworks."+(menu.exportingFluids?"export_stop":"export_all")),
                Component.translatable("gui.appliedmetalworks.export_hint")),mouseX,mouseY);
        g.pose().popPose();
    }
    private void drawAlloyRecipe(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        g.blit(ResourceLocation.fromNamespaceAndPath("productivemetalworks", "textures/gui/jei/fluid_alloying.png"), x+12, y+8, 0, 0, 165, 68, 165, 68);
        var holder = menu.recipeId == null || minecraft.level == null ? null : minecraft.level.getRecipeManager().byKey(menu.recipeId).orElse(null);
        if (holder != null && holder.value() instanceof cy.jdkdigital.productivemetalworks.recipe.FluidAlloyingRecipe recipe && !recipe.fluids.isEmpty()) {
            int count = recipe.fluids.size();
            for (int i=0;i<count;i++) {
                var choices = recipe.fluids.get(i).getFluids(); if (choices.length == 0) continue;
                var fluid = choices[0];
                int start = 42*i/count, end = 42*(i+1)/count;
                var key = appeng.api.stacks.AEFluidKey.of(fluid);
                drawFluidArea(g, new GenericStack(key, fluid.getAmount()), x+24+start, y+16, end-start, 52);
                if (inside(mouseX,mouseY,x+24+start,y+16,end-start,52))
                    g.renderComponentTooltip(font, List.of(key.getDisplayName(), Component.literal(recipe.fluids.get(i).amount() + " mB / batch")),mouseX,mouseY);
            }
        }
        drawFluidArea(g,menu.recipeOutput,x+124,y+16,42,52);
        if (inside(mouseX,mouseY,x+124,y+16,42,52))
            g.renderComponentTooltip(font,List.of(menu.recipeOutput.what().getDisplayName(),Component.literal(menu.recipeOutput.amount()+" mB")),mouseX,mouseY);
        g.fill(x+12,y+80,x+151,y+83,0xff777789);
        g.fill(x+12,y+80,x+12+(int)(139*Math.clamp(menu.pourTotal == 0 ? 0 : (double)menu.poured/menu.pourTotal,0,1)),y+83,0xffa8b6ec);
        if (inside(mouseX,mouseY,x+12,y+78,139,10))
            g.renderTooltip(font,Component.translatable("status.appliedmetalworks."+menu.status),mouseX,mouseY);
    }
    private void drawFluidArea(GuiGraphics g, GenericStack stack, int x, int y, int w, int h) {
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(w/16f,h/16f,1);drawKey(g,stack,0,0);g.pose().popPose();
    }
    private void drawKey(GuiGraphics g, GenericStack stack, int x, int y) {
        if (stack != null) AEKeyRendering.drawInGui(minecraft, g, x, y, stack.what());
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if(fluidsOpen) {
            int px=(width-189)/2,py=(height-170)/2;
            if(button==0 && inside(x,y,px+8,py+149,18,20))menu.exportAllFluids();
            else if(button==0 && inside(x,y,px+139,py+150,18,16))menu.changeFluidPage(-1);
            else if(button==0 && inside(x,y,px+163,py+150,18,16))menu.changeFluidPage(1);
            else if(button==0 && inside(x,y,px+164,py+22,16,128)) {
                int row=(int)(y-py-22)/16;
                if(menu.fluidAt(row)!=null && menu.fluidAvailableAt(row)>0 && !menu.quarantined)menu.exportFluid(menu.fluidAt(row));
            } else if(!inside(x,y,px,py,189,170)) { fluidsOpen=false;menu.setFluidViewOpen(false); }
            return true;
        }
        if (!recipeOpen) return super.mouseClicked(x, y, button);
        if(button==0 && menu.core && inside(x,y,recipeX()+12,recipeY()+100,18,20))menu.previousStation();
        else if(button==0 && menu.core && inside(x,y,recipeX()+159,recipeY()+100,18,20))menu.nextStation();
        else if (button == 0 && inside(x, y, recipeX() + 165, recipeY() + 77, 18, 20)) menu.recover();
        else if (!inside(x, y, recipeX(), recipeY(), 189, recipeHeight())) recipeOpen = false;
        return true;
    }
    @Override public boolean mouseReleased(double x, double y, int button) { return recipeOpen || fluidsOpen || super.mouseReleased(x, y, button); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return recipeOpen || fluidsOpen || super.mouseDragged(x, y, button, dx, dy); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return recipeOpen || fluidsOpen || super.mouseScrolled(x, y, horizontal, vertical); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (!recipeOpen && !fluidsOpen) return super.keyPressed(key, scan, modifiers);
        if (key == 256 || minecraft.options.keyInventory.matches(key, scan)) { recipeOpen = false;if(fluidsOpen)menu.setFluidViewOpen(false);fluidsOpen=false; }
        else if(recipeOpen && menu.core && key==263)menu.previousStation();
        else if(recipeOpen && menu.core && key==262)menu.nextStation();
        return true;
    }
    @Override public boolean charTyped(char character, int modifiers) { return recipeOpen || fluidsOpen || super.charTyped(character, modifiers); }
}
