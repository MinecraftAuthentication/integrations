package me.minecraftauth.mod.mixin

import me.minecraftauth.mod.ModCore
import net.minecraft.network.chat.Component
import net.minecraft.server.players.PlayerList
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.net.SocketAddress
//? if >=1.21.9 {
/*import net.minecraft.server.players.NameAndId
*///?} else {
import com.mojang.authlib.GameProfile
//?}

/**
 * Runs the gatekeeper after vanilla's own login checks (bans, whitelist, full server) have passed.
 * Kept free of Kotlin lambdas and non-null parameter checks so the merged bytecode stays plain.
 */
@Mixin(PlayerList::class)
abstract class PlayerListMixin {

    @Inject(method = ["canPlayerLogin"], at = [At("RETURN")], cancellable = true)
    //? if >=1.21.9 {
    /*private fun minecraftauth_canPlayerLogin(address: SocketAddress?, profile: NameAndId?, cir: CallbackInfoReturnable<Component?>?) {
        if (cir == null || profile == null || cir.returnValue != null) return
        val id = profile.id()
        val name = profile.name()
    *///?} else {
    private fun minecraftauth_canPlayerLogin(address: SocketAddress?, profile: GameProfile?, cir: CallbackInfoReturnable<Component?>?) {
        if (cir == null || profile == null || cir.returnValue != null) return
        val id = profile.id
        val name = profile.name
    //?}
        @Suppress("CAST_NEVER_SUCCEEDS")
        val playerList = (this as Any) as PlayerList
        val denial = ModCore.check(id, name, playerList.isOp(profile))
        if (denial != null) cir.returnValue = denial
    }
}
