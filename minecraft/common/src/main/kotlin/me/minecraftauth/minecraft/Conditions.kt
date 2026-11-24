package me.minecraftauth.minecraft

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.udojava.evalex.AbstractLazyFunction
import com.udojava.evalex.AbstractLazyOperator
import com.udojava.evalex.Expression
import com.udojava.evalex.Expression.LazyNumber
import me.minecraftauth.lib.Platform
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Evaluates condition expressions such as `TwitchSubscriber(2) or DiscordRole("123")` for a player.
 *
 * Each evaluation builds a fresh [Expression] with functions bound to that player, so concurrent logins
 * never share mutable state. Expressions are parsed and validated once in [validate] when the config loads.
 * Answers from the API are cached per (function, player, argument) for [cacheSeconds].
 */
class Conditions(private val membership: Membership, cacheSeconds: Long) {

    private val cache: Cache<String, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(cacheSeconds, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build()

    /**
     * Throws [Expression.ExpressionException] if [condition] doesn't parse, uses an unknown function, or gives a
     * function bad arguments. Every function is run once without short-circuiting and without asking the API.
     */
    fun validate(condition: String) {
        expression(condition, UUID(0, 0), validating = true).eval()
    }

    /** `true` if [condition] holds for [player]. API failures propagate as [me.minecraftauth.lib.MinecraftAuthException]. */
    fun evaluate(condition: String, player: UUID): Boolean =
        expression(condition, player).eval().compareTo(BigDecimal.ZERO) != 0

    fun invalidate() = cache.invalidateAll()

    private fun expression(condition: String, player: UUID, validating: Boolean = false): Expression = Expression(condition).apply {
        (if (validating) EAGER_OPERATORS else OPERATORS).forEach { addOperator(it) }
        val answer: (String, () -> Boolean) -> Boolean = if (validating) NEVER_ASK else ::cached
        functions(player, answer).forEach { addLazyFunction(it) }
    }

    private fun cached(key: String, compute: () -> Boolean): Boolean {
        cache.getIfPresent(key)?.let { return it }
        // computed outside the cache so failures (exceptions) are never cached
        return compute().also { cache.put(key, it) }
    }

    /** [cached] answers each question; while validating it answers `false` without asking. */
    private fun functions(player: UUID, cached: (String, () -> Boolean) -> Boolean): List<AbstractLazyFunction> = listOf(
        BooleanFunction("TwitchFollower", 0) { _ ->
            cached("twitch-follow:$player") { membership.following(player, Platform.TWITCH) }
        },
        BooleanFunction("TwitchSubscriber", -1) { args ->
            val tier = args.firstOrNull()?.let { normalizeTier(it) }
            cached("twitch-sub:$player:$tier") { membership.subscribed(player, Platform.TWITCH, tier) }
        },
        BooleanFunction("YouTubeSubscriber", 0) { _ ->
            cached("youtube-sub:$player") { membership.following(player, Platform.GOOGLE) }
        },
        BooleanFunction("YouTubeMember", -1) { _ ->
            cached("youtube-member:$player") { membership.subscribed(player, Platform.GOOGLE, null) }
        },
        BooleanFunction("PatreonMember", -1) { args ->
            val tier = args.firstOrNull()?.takeIf { it.isNotBlank() }
            cached("patreon:$player:${tier?.lowercase()}") { membership.subscribed(player, Platform.PATREON, tier) }
        },
        BooleanFunction("DiscordServer", 1) { args ->
            val guild = args.single()
            cached("discord-server:$player:$guild") { membership.discordPresent(player, guild) }
        },
        BooleanFunction("DiscordRole", -1) { args ->
            // DiscordRole("role id") or DiscordRole("server id", "role id or name"). A name always needs the server:
            // anyone can add the bot to a server of their own and create a role with the same name.
            val (server, role) = when (args.size) {
                1 -> null to args[0].trim()
                2 -> args[0].trim() to args[1].trim()
                else -> throw Expression.ExpressionException("DiscordRole takes a role ID, or a server ID and a role ID or name")
            }
            if (role.isEmpty()) throw Expression.ExpressionException("DiscordRole needs a role ID or name")
            if (server != null && !server.isDiscordId()) {
                throw Expression.ExpressionException("DiscordRole's server must be a Discord server ID, not '$server'")
            }
            if (server == null && !role.isDiscordId()) {
                throw Expression.ExpressionException("A role name needs its server: DiscordRole(\"server id\", \"$role\")")
            }
            cached("discord-role:$player:$server:${role.lowercase()}") { membership.discordRole(player, role, server) }
        },
        BooleanFunction("Linked", -1) { args ->
            val platform = args.firstOrNull()?.let {
                Platform.fromId(it) ?: throw Expression.ExpressionException("Unknown platform '$it' in Linked()")
            }
            cached("linked:$player:$platform") {
                val result = membership.lookup(player, if (platform == null) emptySet() else setOf(platform))
                result != null && (platform == null || result.has(platform))
            }
        },
    )

    private fun normalizeTier(raw: String): String {
        val number = raw.trim().toBigDecimalOrNull()?.toInt()
            ?: throw Expression.ExpressionException("TwitchSubscriber tier must be 1, 2 or 3, not '$raw'")
        return when (number) {
            1, 1000 -> "1"
            2, 2000 -> "2"
            3, 3000 -> "3"
            else -> throw Expression.ExpressionException("TwitchSubscriber tier must be 1, 2 or 3, not '$raw'")
        }
    }

    /** A boolean function whose arguments are read as strings (quoted or bare numbers). */
    private class BooleanFunction(name: String, params: Int, private val body: (List<String>) -> Boolean) :
        AbstractLazyFunction(name, params, true) {

        override fun lazyEval(lazyParams: List<LazyNumber>): LazyNumber {
            val args = lazyParams.map { it.string }
            return lazy { body(args) }
        }
    }

    private companion object {
        val TRUE = BigDecimal.ONE
        val FALSE = BigDecimal.ZERO

        fun lazy(compute: () -> Boolean): LazyNumber = object : LazyNumber {
            private val value by kotlin.lazy { if (compute()) TRUE else FALSE }
            override fun eval(): BigDecimal = value
            override fun getString(): String = value.toPlainString()
        }

        fun LazyNumber.truthy(): Boolean = eval().compareTo(BigDecimal.ZERO) != 0

        val NEVER_ASK: (String, () -> Boolean) -> Boolean = { _, _ -> false }

        /** Discord snowflakes are all digits. */
        fun String.isDiscordId(): Boolean = isNotEmpty() && all { it in '0'..'9' }

        /**
         * Short-circuiting boolean operators, so `A or B` doesn't call the API for B when A already passed.
         * [eager] evaluates both sides, so validation reaches every function.
         */
        class And(symbol: String, private val eager: Boolean = false) : AbstractLazyOperator(symbol, Expression.OPERATOR_PRECEDENCE_AND, false, true) {
            override fun eval(v1: LazyNumber, v2: LazyNumber): LazyNumber =
                lazy { if (eager) v1.truthy() and v2.truthy() else v1.truthy() && v2.truthy() }
        }

        class Or(symbol: String, private val eager: Boolean = false) : AbstractLazyOperator(symbol, Expression.OPERATOR_PRECEDENCE_OR, false, true) {
            override fun eval(v1: LazyNumber, v2: LazyNumber): LazyNumber =
                lazy { if (eager) v1.truthy() or v2.truthy() else v1.truthy() || v2.truthy() }
        }

        val OPERATORS = listOf(And("and"), And("&&"), Or("or"), Or("||"))
        val EAGER_OPERATORS = listOf(And("and", true), And("&&", true), Or("or", true), Or("||", true))
    }
}
