package me.minecraftauth.minecraft

import me.minecraftauth.lib.LookupResult
import me.minecraftauth.lib.MinecraftAuth
import me.minecraftauth.lib.Platform
import java.util.UUID

/** The questions conditions can ask about a player. Backed by [MinecraftAuth]; swapped for a fake in tests. */
interface Membership {
    fun following(player: UUID, platform: Platform): Boolean
    fun subscribed(player: UUID, platform: Platform, data: String?): Boolean
    fun discordRole(player: UUID, role: String, serverId: String?): Boolean
    fun discordPresent(player: UUID, serverId: String): Boolean
    fun lookup(player: UUID, include: Set<Platform>): LookupResult?
}

class ApiMembership(private val client: MinecraftAuth) : Membership {
    override fun following(player: UUID, platform: Platform) = client.following(player, platform)
    override fun subscribed(player: UUID, platform: Platform, data: String?) = client.subscribed(player, platform, data)
    override fun discordRole(player: UUID, role: String, serverId: String?) = client.discordRole(player, role, serverId)
    override fun discordPresent(player: UUID, serverId: String) = client.discordPresent(player, serverId)
    override fun lookup(player: UUID, include: Set<Platform>) = client.lookup(player, include)
}
