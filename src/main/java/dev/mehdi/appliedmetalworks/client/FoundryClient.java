package dev.mehdi.appliedmetalworks.client;

import appeng.client.gui.style.StyleManager;
import dev.mehdi.appliedmetalworks.AppliedMetalworks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = AppliedMetalworks.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FoundryClient {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.<dev.mehdi.appliedmetalworks.FoundryMenu, FoundryScreen>register(AppliedMetalworks.CORE_MENU.get(), (menu, inventory, title) ->
                new FoundryScreen(menu, inventory, title, StyleManager.loadStyleDoc("/screens/appliedmetalworks/foundry_automation_core.json")));
        event.<dev.mehdi.appliedmetalworks.FoundryMenu, FoundryScreen>register(AppliedMetalworks.EXTENDED_MENU.get(), (menu, inventory, title) ->
                new FoundryScreen(menu, inventory, title, StyleManager.loadStyleDoc("/screens/appliedmetalworks/extended_foundry_interface.json")));
        event.<dev.mehdi.appliedmetalworks.FoundryMenu, FoundryScreen>register(AppliedMetalworks.MENU.get(), (menu, inventory, title) ->
                new FoundryScreen(menu, inventory, title, StyleManager.loadStyleDoc("/screens/appliedmetalworks/foundry_interface.json")));
    }
}
