package caddymcauth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"go.uber.org/zap"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go/v2"
)

// recordingAPI answers membership checks from a function of the request and records each request.
type recordingAPI struct {
	mu       sync.Mutex
	requests []string
}

func (r *recordingAPI) seen() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.requests...)
}

func newRecordingApp(t *testing.T, answer func(*http.Request) (int, string)) (*App, *recordingAPI) {
	t.Helper()
	rec := &recordingAPI{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.mu.Lock()
		rec.requests = append(rec.requests, r.URL.Path+"?"+r.URL.RawQuery)
		rec.mu.Unlock()
		status, body := answer(r)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	app := &App{
		client: &mcauth.Client{BaseURL: srv.URL, UserAgent: "Test/1.0", Credentials: mcauth.ServerToken("tok")},
		lp:     newLuckPermsClient(&LuckPerms{URL: srv.URL}),
		cache:  newMembershipCache(time.Minute),
		logger: zap.NewNop(),
	}
	return app, rec
}

func yes(*http.Request) (int, string) { return 200, `{"result":true}` }
func no(*http.Request) (int, string)  { return 200, `{"result":false}` }

func TestMembershipEndpoints(t *testing.T) {
	const q = "minecraft=" + notch
	for _, tc := range []struct {
		rule Rule
		want string
	}{
		{Rule{Type: "discord_server", Values: []string{"111"}}, "/api/v2/discord/present?" + q + "&server=111"},
		{Rule{Type: "discord_role", Values: []string{"123"}}, "/api/v2/subscribed?data=123&" + q + "&platform=discord"},
		{Rule{Type: "discord_role", Values: []string{"987", "Mod Team"}}, "/api/v2/subscribed?data=Mod+Team&" + q + "&platform=discord&server=987"},
		{Rule{Type: "twitch_follower"}, "/api/v2/following?" + q + "&platform=twitch"},
		{Rule{Type: "twitch_subscriber"}, "/api/v2/subscribed?" + q + "&platform=twitch"},
		{Rule{Type: "twitch_subscriber", Values: []string{"2000"}}, "/api/v2/subscribed?data=2&" + q + "&platform=twitch"},
		{Rule{Type: "patreon_member"}, "/api/v2/subscribed?" + q + "&platform=patreon"},
		{Rule{Type: "patreon_member", Values: []string{"Gold Tier"}}, "/api/v2/subscribed?data=Gold+Tier&" + q + "&platform=patreon"},
		{Rule{Type: "youtube_subscriber"}, "/api/v2/following?" + q + "&platform=google"},
		{Rule{Type: "youtube_member"}, "/api/v2/subscribed?" + q + "&platform=google"},
	} {
		app, rec := newRecordingApp(t, yes)
		ok, err := app.checkMembership(context.Background(), tc.rule, notch)
		if !ok || err != nil {
			t.Errorf("%v: got %v, %v", tc.rule, ok, err)
		}
		if got := rec.seen(); len(got) != 1 || got[0] != tc.want {
			t.Errorf("%v:\n got %v\nwant %s", tc.rule, got, tc.want)
		}
	}
}

func TestEveryAPIRuleHasAnEndpoint(t *testing.T) {
	for typ, arity := range ruleArity {
		rule := Rule{Type: typ}
		for range arity[0] {
			rule.Values = append(rule.Values, "x")
		}
		if !rule.needsAPI() {
			continue
		}
		app, rec := newRecordingApp(t, yes)
		if ok, _ := app.checkMembership(context.Background(), rule, notch); !ok || len(rec.seen()) != 1 {
			t.Errorf("%s: no API call made", typ)
		}
	}
}

func TestLocalRulesSkipTheAPI(t *testing.T) {
	app, rec := newRecordingApp(t, yes)
	realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "player", Values: []string{"notch"}}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch, Name: "Notch"}); v != allowed {
		t.Fatalf("want allowed, got %v", v)
	}
	if len(rec.seen()) != 0 {
		t.Fatalf("matching local rule should skip the API, got %v", rec.seen())
	}
	// no local match: falls through to the API rule
	if v := app.evaluate(context.Background(), realm, &session{UUID: "other", Name: "jeb_"}); v != allowed || len(rec.seen()) != 1 {
		t.Fatalf("want allowed via API, got %v after %v", v, rec.seen())
	}
}

func TestLinkedRuleAllowsAnyone(t *testing.T) {
	app, _ := newRecordingApp(t, no)
	realm := &Realm{Rules: []Rule{{Type: "linked"}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: "anything", Name: "x"}); v != allowed {
		t.Fatalf("got %v", v)
	}
}

func TestPlayerMatching(t *testing.T) {
	s := &session{UUID: notch, Name: "Notch"}
	for value, want := range map[string]bool{
		notch:                                  true,
		strings.ToUpper(notch):                 true,
		strings.ReplaceAll(notch, "-", ""):     true,
		"NOTCH":                                true,
		"notch":                                true,
		"Notc":                                 false,
		notch[:8]:                              false,
		"853c80ef-3c37-49fd-aa49-938b674adae6": false,
	} {
		if got := matchLocal(Rule{Type: "player", Values: []string{value}}, s); got != want {
			t.Errorf("player %q: got %v, want %v", value, got, want)
		}
	}
	if sameUUID("", "") {
		t.Error("empty UUIDs matched")
	}
}

func TestAnyMatchingRuleAllows(t *testing.T) {
	app, rec := newRecordingApp(t, func(r *http.Request) (int, string) {
		if strings.Contains(r.URL.RawQuery, "platform=patreon") {
			return 200, `{"result":true}`
		}
		return 200, `{"result":false}`
	})
	realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "patreon_member"}, {Type: "youtube_member"}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != allowed {
		t.Fatalf("got %v", v)
	}
	if n := len(rec.seen()); n != 2 {
		t.Errorf("evaluation should stop at the first match, made %d requests", n)
	}
}

func TestFailureThenMatchAllows(t *testing.T) {
	app, _ := newRecordingApp(t, func(r *http.Request) (int, string) {
		if strings.Contains(r.URL.RawQuery, "platform=twitch") {
			return 502, `{"error":"twitch down"}`
		}
		return 200, `{"result":true}`
	})
	realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "discord_server", Values: []string{"1"}}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != allowed {
		t.Fatalf("a later match should win over an earlier failure, got %v", v)
	}
}

func TestFailureAndNoMatchIsUnavailable(t *testing.T) {
	for _, status := range []int{403, 409, 429, 500} {
		app, _ := newRecordingApp(t, func(r *http.Request) (int, string) {
			if strings.Contains(r.URL.RawQuery, "platform=twitch") {
				return status, `{"error":"x"}`
			}
			return 200, `{"result":false}`
		})
		realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "youtube_subscriber"}}}
		if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != unavailable {
			t.Errorf("status %d: want unavailable, got %v", status, v)
		}
	}
}

func TestNotLinkedIsDenied(t *testing.T) {
	// 404: the player hasn't linked the platform; that's a "no", not an outage
	app, _ := newRecordingApp(t, func(*http.Request) (int, string) { return 404, `{"error":"not linked"}` })
	realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != denied {
		t.Fatalf("got %v", v)
	}
}

func TestCacheKeys(t *testing.T) {
	app, rec := newRecordingApp(t, yes)
	ctx := context.Background()
	check := func(rule Rule, uuid string) {
		if ok, err := app.checkMembership(ctx, rule, uuid); !ok || err != nil {
			t.Fatalf("%v: %v, %v", rule, ok, err)
		}
	}
	roleA := Rule{Type: "discord_role", Values: []string{"1", "A"}}
	roleB := Rule{Type: "discord_role", Values: []string{"1", "B"}}
	check(roleA, notch)
	check(roleA, notch)
	check(roleB, notch)
	check(roleA, "other")
	check(Rule{Type: "patreon_member", Values: []string{"A"}}, notch)
	if n := len(rec.seen()); n != 4 {
		t.Fatalf("want one request per player+rule+value, got %d: %v", n, rec.seen())
	}
}

func TestErrorsAreNotCached(t *testing.T) {
	var failing atomic.Bool
	failing.Store(true)
	app, rec := newRecordingApp(t, func(*http.Request) (int, string) {
		if failing.Load() {
			return 503, `{"error":"down"}`
		}
		return 200, `{"result":true}`
	})
	rule := Rule{Type: "twitch_follower"}
	if _, err := app.checkMembership(context.Background(), rule, notch); err == nil {
		t.Fatal("want error")
	}
	failing.Store(false)
	if ok, err := app.checkMembership(context.Background(), rule, notch); !ok || err != nil {
		t.Fatalf("after recovery: %v, %v", ok, err)
	}
	if len(rec.seen()) != 2 {
		t.Fatalf("failure was cached: %v", rec.seen())
	}
}

func TestConcurrentEvaluation(t *testing.T) {
	app, _ := newRecordingApp(t, yes)
	realm := &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "discord_role", Values: []string{"1", "x"}}}}
	var wg sync.WaitGroup
	for i := range 32 {
		wg.Go(func() {
			uuid := notch
			if i%2 == 0 {
				uuid = "other"
			}
			if v := app.evaluate(context.Background(), realm, &session{UUID: uuid}); v != allowed {
				t.Errorf("got %v", v)
			}
		})
	}
	wg.Wait()
}
