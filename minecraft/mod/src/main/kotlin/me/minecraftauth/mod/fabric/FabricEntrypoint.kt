package me.minecraftauth.mod.fabric

import me.minecraftauth.mod.ModCore
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.loader.api.FabricLoader
//? if >=1.19 {
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
//?} else {
/*import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback
*///?}

class FabricEntrypoint : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        ModCore.init(FabricLoader.getInstance().configDir.resolve(ModCore.MOD_ID), "Fabric")
        //? if >=1.19 {
        CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ -> ModCore.registerCommands(dispatcher) })
        //?} else {
        /*CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _ -> ModCore.registerCommands(dispatcher) })
        *///?}
    }
}
