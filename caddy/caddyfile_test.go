package caddymcauth

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"
)

func adapt(t *testing.T, cf string) (App, string, error) {
	t.Helper()
	out, _, err := caddyconfig.GetAdapter("caddyfile").Adapt([]byte(cf), nil)
	if err != nil {
		return App{}, "", err
	}
	var cfg struct {
		Apps struct {
			MCAuth App `json:"mcauth"`
		} `json:"apps"`
	}
	if err := json.Unmarshal(out, &cfg); err != nil {
		t.Fatal(err)
	}
	return cfg.Apps.MCAuth, string(out), nil
}

func global(body string) string {
	return "{\n\tmcauth {\n" + body + "\n\t}\n}\n:80 {\n\trespond ok\n}\n"
}

func TestCaddyfileOptions(t *testing.T) {
	app, _, err := adapt(t, global(`
		app_id        a
		redirect      https://x/cb
		cookie_prefix mysite
		api_base      http://localhost:9999
		scopes        discord
		scopes        twitch google
		persist       true
		realm everyone {
			linked
		}
		realm empty {
		}`))
	if err != nil {
		t.Fatal(err)
	}
	if app.CookiePrefix != "mysite" || app.APIBase != "http://localhost:9999" {
		t.Errorf("options: %+v", app)
	}
	if strings.Join(app.Scopes, ",") != "discord,twitch,google" {
		t.Errorf("scopes should accumulate: %v", app.Scopes)
	}
	if app.Persist == nil || !*app.Persist {
		t.Error("persist true lost")
	}
	if r := app.Realms["everyone"].Rules; len(r) != 1 || r[0].Type != "linked" || len(r[0].Values) != 0 {
		t.Errorf("everyone realm: %+v", r)
	}
	// an empty realm adapts; Validate rejects it when Caddy loads the config
	if r, ok := app.Realms["empty"]; !ok || len(r.Rules) != 0 {
		t.Errorf("empty realm: %+v", r)
	}
}

func TestCaddyfileOmitsUnsetOptions(t *testing.T) {
	_, out, err := adapt(t, global("app_id a\nredirect https://x/cb"))
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"app_secret", "server_token", "session_key", "session_ttl", "persist", "scopes", "api_base"} {
		if strings.Contains(out, `"`+key+`"`) {
			t.Errorf("unset %s should be omitted:\n%s", key, out)
		}
	}
}

func TestCaddyfileGlobalErrors(t *testing.T) {
	for name, tc := range map[string]struct{ body, want string }{
		"value missing":      {"app_id", "argument"},
		"two values":         {"app_id a b", "argument"},
		"ttl missing":        {"session_ttl", "argument"},
		"bad ttl":            {"session_ttl forever", "invalid session_ttl"},
		"bad membership_ttl": {"membership_ttl 5 minutes", "invalid membership_ttl"},
		"two ttls":           {"session_ttl 5m 10m", "argument"},
		"two persists":       {"persist true false", "argument"},
		"persist missing":    {"persist", "argument"},
		"bad persist":        {"persist maybe", "true or false"},
		"realm without name": {"realm {\n linked\n }", "argument"},
		"duplicate realm":    {"realm a {\n linked\n }\n realm a {\n linked\n }", "defined twice"},
		"unknown rule":       {"realm a {\n steam_friend\n }", "unknown rule"},
		"unknown option":     {"nope 1", "unrecognized"},
	} {
		_, _, err := adapt(t, global(tc.body))
		if err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
	if _, _, err := adapt(t, "{\n mcauth extra {\n app_id a\n }\n}\n:80 {\n}\n"); err == nil {
		t.Error("argument after mcauth accepted")
	}
}

func TestCaddyfileDirectiveErrors(t *testing.T) {
	for name, site := range map[string]string{
		"protect two realms":    "mcauth a b",
		"protect matcher, none": "mcauth /x",
		"callback extra arg":    "mcauth_callback /cb extra",
	} {
		cf := "{\n mcauth {\n app_id a\n }\n}\n:80 {\n " + site + "\n}\n"
		if _, _, err := adapt(t, cf); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestCaddyfileDirectiveOrder(t *testing.T) {
	// the directives need no `order` option, and run callback -> basic_auth -> protect -> respond
	_, out, err := adapt(t, `{
	mcauth {
		app_id a
	}
}
:80 {
	respond ok
	mcauth r
	basic_auth /basic/* {
		u $2a$14$Zkx19XLiW6VYouLHR5NmfOFU0z2GTNmpkT/5qqR7hx4IjWJPDhjvG
	}
	mcauth_callback /cb
}
`)
	if err != nil {
		t.Fatal(err)
	}
	order := []string{`"mcauth_callback"`, `"authentication"`, `"mcauth"`, `"static_response"`}
	last := -1
	for _, h := range order {
		i := strings.Index(out, `"handler":`+h)
		if i < 0 || i < last {
			t.Fatalf("handlers out of order, want %v:\n%s", order, out)
		}
		last = i
	}
}

func TestExampleCaddyfileAdapts(t *testing.T) {
	cf, err := os.ReadFile("examples/Caddyfile")
	if err != nil {
		t.Fatal(err)
	}
	app, out, err := adapt(t, string(cf))
	if err != nil {
		t.Fatal(err)
	}
	if len(app.Realms) != 3 || app.Realms["community"] == nil || app.AppID != "{env.MCAUTH_APP_ID}" {
		t.Errorf("unexpected app %+v", app)
	}
	if !strings.Contains(out, `"realm":"supporters"`) {
		t.Errorf("protect handler missing:\n%s", out)
	}
}

// TestEveryExampleLoads adapts each example and has Caddy provision and validate it (like `caddy adapt
// --validate`), so the examples can't drift from the module.
func TestEveryExampleLoads(t *testing.T) {
	t.Setenv("MCAUTH_APP_ID", "app")
	t.Setenv("MCAUTH_SESSION_KEY", strings.Repeat("k", 32))
	t.Setenv("MCAUTH_APP_SECRET", "sec")
	t.Setenv("LUCKPERMS_REST_KEY", "key")
	files, err := filepath.Glob("examples/*Caddyfile")
	if err != nil || len(files) < 2 {
		t.Fatalf("examples: %v %v", files, err)
	}
	for _, f := range files {
		cf, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		out, _, err := caddyconfig.GetAdapter("caddyfile").Adapt(cf, map[string]any{"filename": f})
		if err != nil {
			t.Errorf("%s: adapt: %v", f, err)
			continue
		}
		var cfg caddy.Config
		if err := json.Unmarshal(out, &cfg); err != nil {
			t.Fatal(err)
		}
		if err := caddy.Validate(&cfg); err != nil {
			t.Errorf("%s: %v", f, err)
		}
	}
}
