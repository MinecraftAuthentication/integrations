package me.minecraftauth.mod.neoforge

import me.minecraftauth.mod.ModCore
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import java.util.function.Consumer

@Mod(value = ModCore.MOD_ID, dist = [Dist.DEDICATED_SERVER])
class NeoForgeEntrypoint {
    init {
        ModCore.init(FMLPaths.CONFIGDIR.get().resolve(ModCore.MOD_ID), "NeoForge")
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RegisterCommandsEvent::class.java,
            Consumer { event -> ModCore.registerCommands(event.dispatcher) })
    }
}
