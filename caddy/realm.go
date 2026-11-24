package caddymcauth

import (
	"context"
	"fmt"
	"strings"

	"go.uber.org/zap"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go"
)

type verdict int

const (
	denied verdict = iota
	allowed
	unavailable // couldn't be decided because MinecraftAuth.me or an upstream platform failed
)

// evaluate decides whether the logged-in player may enter realm: any matching rule lets them in.
func (a *App) evaluate(ctx context.Context, realm *Realm, s *session) verdict {
	return a.combine(ctx, realm.Rules, s, allowed)
}

// combine evaluates rules until one gives the deciding verdict: allowed for "any" (a realm), denied for
// "all". If none does, a failed check makes the result unavailable; otherwise it's the opposite verdict.
// Local rules (player, linked) go first, so they can decide without calling the API.
func (a *App) combine(ctx context.Context, rules []Rule, s *session, deciding verdict) verdict {
	failed := false
	for _, local := range []bool{true, false} {
		for _, rule := range rules {
			if rule.needsAPI() == local {
				continue
			}
			switch v := a.evaluateRule(ctx, rule, s); v {
			case deciding:
				return v
			case unavailable:
				failed = true
			}
		}
	}
	switch {
	case failed:
		return unavailable
	case deciding == allowed:
		return denied
	default:
		return allowed
	}
}

// evaluateRule checks one rule. not swaps allowed and denied; unavailable stays unavailable, so an outage
// never lets anyone through a not rule.
func (a *App) evaluateRule(ctx context.Context, rule Rule, s *session) verdict {
	var v verdict
	switch {
	case rule.Type == "all":
		v = a.combine(ctx, rule.All, s, denied)
	case !rule.needsAPI():
		v = verdictOf(matchLocal(rule, s))
	default:
		ok, err := a.checkMembership(ctx, rule, s.UUID)
		if err != nil {
			level := a.logger.Warn
			if mcauth.IsConfig(err) {
				level = a.logger.Error
			}
			level("mcauth: membership check failed", zap.String("rule", rule.Type), zap.String("player", s.Name), zap.Error(err))
			return unavailable
		}
		v = verdictOf(ok)
	}
	if rule.Not && v != unavailable {
		v = verdictOf(v == denied)
	}
	return v
}

func verdictOf(ok bool) verdict {
	if ok {
		return allowed
	}
	return denied
}

func matchLocal(rule Rule, s *session) bool {
	switch rule.Type {
	case "linked":
		return true
	case "player":
		for _, v := range rule.Values {
			if sameUUID(v, s.UUID) || strings.EqualFold(v, s.Name) {
				return true
			}
		}
	}
	return false
}

func sameUUID(a, b string) bool {
	clean := func(s string) string { return strings.ToLower(strings.ReplaceAll(s, "-", "")) }
	return len(clean(a)) == 32 && clean(a) == clean(b)
}

func (a *App) checkMembership(ctx context.Context, rule Rule, uuid string) (bool, error) {
	value := ""
	if len(rule.Values) > 0 {
		value = rule.Values[0]
	}
	key := uuid + "|" + rule.Type + "|" + fmt.Sprintf("%q", rule.Values)
	if v, ok := a.cache.get(key); ok {
		return v, nil
	}
	var (
		result bool
		err    error
	)
	switch rule.Type {
	case "discord_server":
		result, err = a.client.DiscordPresent(ctx, uuid, value)
	case "discord_role":
		// validated as <role id> or <guild id> <role id|name>
		if len(rule.Values) == 2 {
			result, err = a.client.DiscordRole(ctx, uuid, rule.Values[1], rule.Values[0])
		} else {
			result, err = a.client.DiscordRole(ctx, uuid, value, "")
		}
	case "twitch_follower":
		result, err = a.client.Following(ctx, uuid, mcauth.Twitch)
	case "twitch_subscriber":
		result, err = a.client.Subscribed(ctx, uuid, mcauth.Twitch, normalizeTier(value))
	case "patreon_member":
		result, err = a.client.Subscribed(ctx, uuid, mcauth.Patreon, value)
	case "youtube_subscriber":
		result, err = a.client.Following(ctx, uuid, mcauth.Google)
	case "youtube_member":
		result, err = a.client.Subscribed(ctx, uuid, mcauth.Google, "")
	case "permission", "group":
		permission, contexts, perr := parseLuckPermsRule(rule)
		if perr != nil {
			return false, perr
		}
		result, err = a.lp.hasPermission(ctx, uuid, permission, contexts)
	}
	if err != nil {
		return false, err
	}
	a.cache.put(key, result)
	return result, nil
}
