package caddymcauth

import (
	"context"
	"io"
	"net/http"
	"strings"
	"testing"
)

func player(names ...string) Rule { return Rule{Type: "player", Values: names} }

func not(r Rule) Rule { r.Not = true; return r }

func all(rules ...Rule) Rule { return Rule{Type: "all", All: rules} }

var (
	notchSession = &session{UUID: notch, Name: "Notch"}
	jebSession   = &session{UUID: "853c80ef-3c37-49fd-aa49-938b674adae6", Name: "jeb_"}
)

func TestNotAndAllLocal(t *testing.T) {
	app, rec := newRecordingApp(t, yes)
	for name, tc := range map[string]struct {
		rules []Rule
		s     *session
		want  verdict
	}{
		"not player, other":        {[]Rule{not(player("Notch"))}, jebSession, allowed},
		"not player, that player":  {[]Rule{not(player("Notch"))}, notchSession, denied},
		"not linked":               {[]Rule{not(Rule{Type: "linked"})}, notchSession, denied},
		"all match":                {[]Rule{all(player("Notch"), Rule{Type: "linked"})}, notchSession, allowed},
		"all, one fails":           {[]Rule{all(player("Notch"), player("jeb_"))}, notchSession, denied},
		"all with not":             {[]Rule{all(Rule{Type: "linked"}, not(player("jeb_")))}, notchSession, allowed},
		"all with not, excluded":   {[]Rule{all(Rule{Type: "linked"}, not(player("jeb_")))}, jebSession, denied},
		"not all (nand)":           {[]Rule{not(all(player("Notch"), Rule{Type: "linked"}))}, notchSession, denied},
		"not all (nand), other":    {[]Rule{not(all(player("Notch"), Rule{Type: "linked"}))}, jebSession, allowed},
		"any of all or player":     {[]Rule{all(player("Notch"), player("jeb_")), player("jeb_")}, jebSession, allowed},
		"nested all":               {[]Rule{all(Rule{Type: "linked"}, all(player("jeb_"), not(player("Notch"))))}, jebSession, allowed},
		"nested all, inner denies": {[]Rule{all(Rule{Type: "linked"}, all(player("jeb_"), not(player("jeb_"))))}, jebSession, denied},
	} {
		if got := app.evaluate(context.Background(), &Realm{Rules: tc.rules}, tc.s); got != tc.want {
			t.Errorf("%s: got %v, want %v", name, got, tc.want)
		}
	}
	if len(rec.seen()) != 0 {
		t.Errorf("local rules called the API: %v", rec.seen())
	}
}

func TestNotWithAPI(t *testing.T) {
	for name, tc := range map[string]struct {
		answer func(*http.Request) (int, string)
		want   verdict
	}{
		"member is excluded":       {yes, denied},
		"non-member is let in":     {no, allowed},
		"outage stays unavailable": {func(*http.Request) (int, string) { return 503, `{"error":"down"}` }, unavailable},
	} {
		app, _ := newRecordingApp(t, tc.answer)
		realm := &Realm{Rules: []Rule{not(Rule{Type: "twitch_follower"})}}
		if got := app.evaluate(context.Background(), realm, notchSession); got != tc.want {
			t.Errorf("%s: got %v, want %v", name, got, tc.want)
		}
	}
}

func TestAllWithAPI(t *testing.T) {
	down := func(*http.Request) (int, string) { return 503, `{"error":"down"}` }

	// a local rule that fails decides the all without calling the API
	app, rec := newRecordingApp(t, yes)
	if got := app.evaluate(context.Background(), &Realm{Rules: []Rule{all(Rule{Type: "twitch_follower"}, player("jeb_"))}}, notchSession); got != denied || len(rec.seen()) != 0 {
		t.Errorf("local short-circuit: got %v after %v", got, rec.seen())
	}

	// one check fails and the rest match: can't decide
	app, _ = newRecordingApp(t, func(r *http.Request) (int, string) {
		if r.URL.Query().Get("platform") == "twitch" {
			return down(r)
		}
		return yes(r)
	})
	both := all(Rule{Type: "twitch_follower"}, Rule{Type: "youtube_subscriber"})
	if got := app.evaluate(context.Background(), &Realm{Rules: []Rule{both}}, notchSession); got != unavailable {
		t.Errorf("one failed: got %v", got)
	}

	// one check fails but another denies: denied, because the all can't match either way
	app, _ = newRecordingApp(t, func(r *http.Request) (int, string) {
		if r.URL.Query().Get("platform") == "twitch" {
			return down(r)
		}
		return no(r)
	})
	if got := app.evaluate(context.Background(), &Realm{Rules: []Rule{both}}, notchSession); got != denied {
		t.Errorf("failed + denied: got %v", got)
	}

	// in a realm, a failed rule doesn't stop another rule letting the player in
	app, _ = newRecordingApp(t, func(r *http.Request) (int, string) {
		if r.URL.Query().Get("platform") == "twitch" {
			return down(r)
		}
		return yes(r)
	})
	if got := app.evaluate(context.Background(), &Realm{Rules: []Rule{{Type: "twitch_follower"}, {Type: "youtube_subscriber"}}}, notchSession); got != allowed {
		t.Errorf("any with one failed: got %v", got)
	}
}

func TestSubscriberButNotShunned(t *testing.T) {
	for name, shunned := range map[string]bool{"shunned": true, "not shunned": false} {
		app, _ := newRecordingApp(t, func(r *http.Request) (int, string) {
			if strings.HasSuffix(r.URL.Path, "/permission-check") {
				body, _ := io.ReadAll(r.Body)
				if shunned && strings.Contains(string(body), `"group.shunned"`) {
					return 200, `{"result":"true"}`
				}
				return 200, `{"result":"undefined"}`
			}
			return yes(r)
		})
		realm := &Realm{Rules: []Rule{all(Rule{Type: "twitch_subscriber"}, not(Rule{Type: "group", Values: []string{"shunned"}}))}}
		want := allowed
		if shunned {
			want = denied
		}
		if got := app.evaluate(context.Background(), realm, notchSession); got != want {
			t.Errorf("%s: got %v, want %v", name, got, want)
		}
	}
}

func TestNeedsAPINested(t *testing.T) {
	if all(player("x"), not(Rule{Type: "linked"})).needsAPI() {
		t.Error("local-only all needs no API")
	}
	if !all(player("x"), all(not(Rule{Type: "twitch_follower"}))).needsAPI() {
		t.Error("all with a nested API rule needs the API")
	}
}

func TestValidateNotAndAll(t *testing.T) {
	valid := func(rules ...Rule) *App {
		return &App{AppID: "a", RedirectURL: "https://x/cb", OnAPIError: "deny", Realms: map[string]*Realm{"r": {Rules: rules}}}
	}
	if err := valid(not(player("Notch")), all(Rule{Type: "linked"}, not(all(player("jeb_"))))).Validate(); err != nil {
		t.Fatal(err)
	}
	for name, tc := range map[string]struct {
		app  *App
		want string
	}{
		"empty all":              {valid(all()), "all has no rules"},
		"all with values":        {valid(Rule{Type: "all", Values: []string{"x"}, All: []Rule{player("x")}}), "number of values"},
		"bad rule inside all":    {valid(all(Rule{Type: "steam_friend"})), "unknown rule"},
		"bad rule inside not":    {valid(not(Rule{Type: "linked", Values: []string{"x"}})), "number of values"},
		"rules on a non-all":     {valid(Rule{Type: "linked", All: []Rule{player("x")}}), "only all can contain rules"},
		"nested needs token":     {valid(all(Rule{Type: "linked"}, not(Rule{Type: "twitch_follower"}))), "needs server_token"},
		"nested needs luckperms": {valid(all(not(Rule{Type: "group", Values: []string{"shunned"}}))), "needs the luckperms option"},
	} {
		if err := tc.app.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
}

func TestCaddyfileNotAndAll(t *testing.T) {
	app, _, err := adapt(t, global(`
		app_id a
		redirect https://x/cb
		realm r {
			not player Notch
			all {
				twitch_subscriber 2
				not group shunned server=survival
				not all {
					linked
					player jeb_
				}
			}
			linked
		}`))
	if err != nil {
		t.Fatal(err)
	}
	got := app.Realms["r"].Rules
	want := []Rule{
		not(player("Notch")),
		all(
			Rule{Type: "twitch_subscriber", Values: []string{"2"}},
			not(Rule{Type: "group", Values: []string{"shunned", "server=survival"}}),
			not(all(Rule{Type: "linked"}, player("jeb_"))),
		),
		{Type: "linked"},
	}
	if !sameRules(got, want) {
		t.Errorf("rules\n got %+v\nwant %+v", got, want)
	}

	for name, tc := range map[string]struct{ body, want string }{
		"bare not":          {"realm r {\n not\n }", "not needs a rule"},
		"not unknown":       {"realm r {\n not bogus\n }", "unknown rule"},
		"all with argument": {"realm r {\n all x {\n linked\n }\n }", "not arguments"},
		"all without block": {"realm r {\n all\n }", "all needs a block"},
		"empty all":         {"realm r {\n all {\n }\n }", "all needs a block"},
		"block on a rule":   {"realm r {\n linked {\n player x\n }\n }", "only all takes a block"},
		"bad rule in all":   {"realm r {\n all {\n bogus\n }\n }", "unknown rule"},
	} {
		if _, _, err := adapt(t, global(tc.body)); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
}

func sameRules(a, b []Rule) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].Type != b[i].Type || a[i].Not != b[i].Not || strings.Join(a[i].Values, " ") != strings.Join(b[i].Values, " ") || !sameRules(a[i].All, b[i].All) {
			return false
		}
	}
	return true
}
