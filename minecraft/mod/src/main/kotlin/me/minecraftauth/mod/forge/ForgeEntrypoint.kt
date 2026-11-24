package me.minecraftauth.mod.forge

import me.minecraftauth.mod.ModCore
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLPaths
import java.util.function.Consumer
//? if <1.17 {
/*import net.minecraftforge.fml.ExtensionPoint
import net.minecraftforge.fml.network.FMLNetworkConstants
import org.apache.commons.lang3.tuple.Pair
import java.util.function.BiPredicate
import java.util.function.Supplier
*///?} else {
import net.minecraftforge.fml.IExtensionPoint
import net.minecraftforge.network.NetworkConstants
//?}

@Mod(ModCore.MOD_ID)
class ForgeEntrypoint {
    init {
        // server-only: don't make clients without the mod show the server as incompatible
        //? if <1.17 {
        /*ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST) {
            Pair.of(Supplier { FMLNetworkConstants.IGNORESERVERONLY }, BiPredicate<String, Boolean> { _, _ -> true })
        }
        *///?} else {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest::class.java) {
            IExtensionPoint.DisplayTest({ NetworkConstants.IGNORESERVERONLY }, { _, _ -> true })
        }
        //?}

        ModCore.init(FMLPaths.CONFIGDIR.get().resolve(ModCore.MOD_ID), "Forge")
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent::class.java,
            Consumer { event -> ModCore.registerCommands(event.dispatcher) })
    }
}
