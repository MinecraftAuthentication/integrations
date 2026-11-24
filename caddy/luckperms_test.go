package caddymcauth

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"go.uber.org/zap"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go"
)

type lpRequest struct {
	path, auth string
	body       map[string]any
}

// newLuckPermsApp returns an app whose LuckPerms REST API answers with answer and records each request.
func newLuckPermsApp(t *testing.T, apiKey string, answer func(lpRequest) (int, string)) (*App, func() []lpRequest) {
	t.Helper()
	var (
		mu   sync.Mutex
		seen []lpRequest
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		req := lpRequest{path: r.Method + " " + r.URL.Path, auth: r.Header.Get("Authorization")}
		data, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(data, &req.body)
		mu.Lock()
		seen = append(seen, req)
		mu.Unlock()
		status, body := answer(req)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	app := &App{
		LuckPerms: &LuckPerms{URL: srv.URL + "/", APIKey: apiKey},
		cache:     newMembershipCache(time.Minute),
		logger:    zap.NewNop(),
	}
	app.lp = newLuckPermsClient(app.LuckPerms)
	return app, func() []lpRequest {
		mu.Lock()
		defer mu.Unlock()
		return append([]lpRequest(nil), seen...)
	}
}

func lpResult(result string) func(lpRequest) (int, string) {
	return func(lpRequest) (int, string) { return 200, `{"result":` + result + `}` }
}

func TestLuckPermsRequest(t *testing.T) {
	app, seen := newLuckPermsApp(t, "secret", lpResult(`"true"`))
	undashed := strings.ReplaceAll(strings.ToUpper(notch), "-", "")
	rule := Rule{Type: "permission", Values: []string{"site.admin", "server=survival", "world=nether"}}
	if ok, err := app.checkMembership(context.Background(), rule, undashed); !ok || err != nil {
		t.Fatalf("got %v, %v", ok, err)
	}
	got := seen()
	if len(got) != 1 {
		t.Fatalf("want 1 request, got %v", got)
	}
	req := got[0]
	if req.path != "POST /user/"+notch+"/permission-check" {
		t.Errorf("path %q", req.path)
	}
	if req.auth != "Bearer secret" {
		t.Errorf("auth %q", req.auth)
	}
	body, _ := json.Marshal(req.body)
	want := `{"permission":"site.admin","queryOptions":{"contexts":[{"key":"server","value":"survival"},{"key":"world","value":"nether"}]}}`
	if string(body) != want {
		t.Errorf("body\n got %s\nwant %s", body, want)
	}
}

func TestLuckPermsRequestWithoutContextsOrKey(t *testing.T) {
	app, seen := newLuckPermsApp(t, "", lpResult(`"true"`))
	if ok, err := app.checkMembership(context.Background(), Rule{Type: "permission", Values: []string{"site.view"}}, notch); !ok || err != nil {
		t.Fatalf("got %v, %v", ok, err)
	}
	req := seen()[0]
	if req.auth != "" {
		t.Errorf("no api_key should send no Authorization, got %q", req.auth)
	}
	if _, ok := req.body["queryOptions"]; ok || req.body["permission"] != "site.view" {
		t.Errorf("body %v", req.body)
	}
}

func TestLuckPermsResults(t *testing.T) {
	for name, tc := range map[string]struct {
		status       int
		body         string
		want, failed bool
		config       bool
	}{
		"true":           {200, `{"result":"true","node":{"key":"site.admin","type":"permission","value":true,"context":[]}}`, true, false, false},
		"boolean true":   {200, `{"result":true}`, true, false, false},
		"false":          {200, `{"result":"false"}`, false, false, false},
		"undefined":      {200, `{"result":"undefined"}`, false, false, false},
		"unknown player": {404, ``, false, false, false},
		"bad key":        {401, ``, false, true, true},
		"down":           {503, ``, false, true, false},
		"garbage":        {200, `<html>`, false, true, false},
		"unknown result": {200, `{"result":"maybe"}`, false, true, false},
		"missing result": {200, `{}`, false, true, false},
	} {
		app, _ := newLuckPermsApp(t, "k", func(lpRequest) (int, string) { return tc.status, tc.body })
		ok, err := app.checkMembership(context.Background(), Rule{Type: "permission", Values: []string{"p"}}, notch)
		if ok != tc.want || (err != nil) != tc.failed || mcauth.IsConfig(err) != tc.config {
			t.Errorf("%s: got %v, %v (config=%v)", name, ok, err, mcauth.IsConfig(err))
		}
	}
}

func TestLuckPermsUnreachable(t *testing.T) {
	app, _ := newLuckPermsApp(t, "", lpResult(`"true"`))
	app.lp.baseURL = "http://127.0.0.1:1"
	if ok, err := app.checkMembership(context.Background(), Rule{Type: "permission", Values: []string{"p"}}, notch); ok || err == nil {
		t.Fatalf("got %v, %v", ok, err)
	}
}

func TestLuckPermsInRealm(t *testing.T) {
	app, seen := newLuckPermsApp(t, "", func(r lpRequest) (int, string) {
		if r.body["permission"] == "site.admin" {
			return 200, `{"result":"true"}`
		}
		return 200, `{"result":"undefined"}`
	})
	s := &session{UUID: notch, Name: "Notch"}
	admins := &Realm{Rules: []Rule{{Type: "permission", Values: []string{"site.admin"}}}}
	mods := &Realm{Rules: []Rule{{Type: "permission", Values: []string{"site.mod"}}}}
	if v := app.evaluate(context.Background(), admins, s); v != allowed {
		t.Errorf("admins: %v", v)
	}
	if v := app.evaluate(context.Background(), mods, s); v != denied {
		t.Errorf("mods: %v", v)
	}
	// answers are cached per permission and context
	app.evaluate(context.Background(), admins, s)
	app.evaluate(context.Background(), &Realm{Rules: []Rule{{Type: "permission", Values: []string{"site.admin", "server=lobby"}}}}, s)
	if n := len(seen()); n != 3 {
		t.Errorf("want 3 requests (one cached), got %d", n)
	}
}

func TestLuckPermsGroupRule(t *testing.T) {
	app, seen := newLuckPermsApp(t, "", lpResult(`"true"`))
	realm := &Realm{Rules: []Rule{{Type: "group", Values: []string{"Moderator", "server=survival"}}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != allowed {
		t.Fatalf("got %v", v)
	}
	got := seen()
	body, _ := json.Marshal(got[0].body)
	want := `{"permission":"group.moderator","queryOptions":{"contexts":[{"key":"server","value":"survival"}]}}`
	if len(got) != 1 || string(body) != want {
		t.Errorf("body\n got %s\nwant %s", body, want)
	}
}

func TestLuckPermsUnavailableVerdict(t *testing.T) {
	app, _ := newLuckPermsApp(t, "", func(lpRequest) (int, string) { return 500, "" })
	realm := &Realm{Rules: []Rule{{Type: "permission", Values: []string{"p"}}}}
	if v := app.evaluate(context.Background(), realm, &session{UUID: notch}); v != unavailable {
		t.Fatalf("got %v", v)
	}
}

func TestDashedUUID(t *testing.T) {
	for in, want := range map[string]string{
		notch:                                 notch,
		strings.ReplaceAll(notch, "-", ""):    notch,
		strings.ToUpper(notch):                notch,
		"069a79f4-44e9-4726-a5be-fca90e38aaf": "",
		"zz9a79f444e94726a5befca90e38aaf5":    "",
		"":                                    "",
	} {
		got, ok := dashedUUID(in)
		if got != want || ok != (want != "") {
			t.Errorf("%q: got %q, %v", in, got, ok)
		}
	}
	app, seen := newLuckPermsApp(t, "", lpResult(`"true"`))
	if ok, err := app.checkMembership(context.Background(), Rule{Type: "permission", Values: []string{"p"}}, "not-a-uuid"); ok || err == nil || len(seen()) != 0 {
		t.Errorf("non-UUID player: %v, %v, %v", ok, err, seen())
	}
}

func TestParseLuckPermsRule(t *testing.T) {
	for _, bad := range [][]string{
		nil,
		{""},
		{"p", "server"},
		{"p", "Server=survival"},
		{"p", "server=Survival"},
		{"p", "server="},
		{"p", "=survival"},
		{"p", "server=a b"},
	} {
		if _, _, err := parseLuckPermsRule(Rule{Type: "permission", Values: bad}); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	perm, ctx, err := parseLuckPermsRule(Rule{Type: "permission", Values: []string{"a.b", "server=s1", "world=w"}})
	if err != nil || perm != "a.b" || len(ctx) != 2 || ctx[1] != (luckPermsCtx{"world", "w"}) {
		t.Errorf("got %q %v %v", perm, ctx, err)
	}
	// group <name> is shorthand for permission group.<name>; LuckPerms group names are lowercase
	perm, ctx, err = parseLuckPermsRule(Rule{Type: "group", Values: []string{"Admin", "server=s1"}})
	if err != nil || perm != "group.admin" || len(ctx) != 1 {
		t.Errorf("group: got %q %v %v", perm, ctx, err)
	}
	for _, bad := range [][]string{nil, {" "}, {"admin", "server"}} {
		if _, _, err := parseLuckPermsRule(Rule{Type: "group", Values: bad}); err == nil {
			t.Errorf("group %q accepted", bad)
		}
	}
}

func TestValidateLuckPerms(t *testing.T) {
	valid := func() *App {
		return &App{
			AppID: "a", RedirectURL: "https://x/cb", OnAPIError: "deny",
			LuckPerms: &LuckPerms{URL: "http://localhost:8080"},
			Realms:    map[string]*Realm{"r": {Rules: []Rule{{Type: "permission", Values: []string{"site.admin", "server=survival"}}}}},
		}
	}
	// luckperms rules don't need a server token
	if err := valid().Validate(); err != nil {
		t.Fatal(err)
	}
	for name, tc := range map[string]struct {
		mutate func(*App)
		want   string
	}{
		"no luckperms option": {func(a *App) { a.LuckPerms = nil }, "uses permission, which needs the luckperms option"},
		"group without luckperms option": {func(a *App) {
			a.LuckPerms = nil
			a.Realms["r"].Rules = []Rule{{Type: "group", Values: []string{"admin"}}}
		}, "uses group, which needs the luckperms option"},
		"group without name": {func(a *App) { a.Realms["r"].Rules = []Rule{{Type: "group"}} }, "number of values"},
		"relative url":       {func(a *App) { a.LuckPerms.URL = "localhost:8080" }, "luckperms url"},
		"empty url":          {func(a *App) { a.LuckPerms.URL = "" }, "luckperms url"},
		"no permission":      {func(a *App) { a.Realms["r"].Rules[0].Values = nil }, "number of values"},
		"bad context": {func(a *App) {
			a.Realms["r"].Rules[0].Values = []string{"p", "Server=x"}
		}, "key=value"},
	} {
		app := valid()
		tc.mutate(app)
		if err := app.Validate(); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
}

func TestCaddyfileLuckPerms(t *testing.T) {
	app, _, err := adapt(t, global(`
		app_id a
		redirect https://x/cb
		luckperms http://localhost:8080 {
			api_key {env.LP_KEY}
		}
		realm staff {
			permission site.admin server=survival
			group Admin server=survival
		}`))
	if err != nil {
		t.Fatal(err)
	}
	if app.LuckPerms == nil || app.LuckPerms.URL != "http://localhost:8080" || app.LuckPerms.APIKey != "{env.LP_KEY}" {
		t.Errorf("luckperms: %+v", app.LuckPerms)
	}
	if r := app.Realms["staff"].Rules; len(r) != 2 || r[0].Type != "permission" || strings.Join(r[0].Values, " ") != "site.admin server=survival" ||
		r[1].Type != "group" || strings.Join(r[1].Values, " ") != "Admin server=survival" {
		t.Errorf("rules: %+v", r)
	}

	app, out, err := adapt(t, global("app_id a\nredirect https://x/cb\nluckperms http://lp:8080"))
	if err != nil || app.LuckPerms == nil || app.LuckPerms.APIKey != "" || strings.Contains(out, "api_key") {
		t.Errorf("without block: %+v %v\n%s", app.LuckPerms, err, out)
	}

	for name, tc := range map[string]struct{ body, want string }{
		"no url":         {"luckperms", "argument"},
		"two urls":       {"luckperms http://a http://b", "argument"},
		"twice":          {"luckperms http://a\nluckperms http://b", "defined twice"},
		"key missing":    {"luckperms http://a {\n api_key\n }", "argument"},
		"two keys":       {"luckperms http://a {\n api_key a b\n }", "argument"},
		"unknown option": {"luckperms http://a {\n port 1\n }", "unrecognized luckperms option"},
	} {
		if _, _, err := adapt(t, global(tc.body)); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: want error containing %q, got %v", name, tc.want, err)
		}
	}
}

func TestProvisionLuckPermsPlaceholders(t *testing.T) {
	t.Setenv("MCAUTH_TEST_LP_URL", "http://lp.internal:8080/")
	t.Setenv("MCAUTH_TEST_LP_KEY", "k3y")
	app := provision(t, &App{AppID: "a", RedirectURL: "https://x/cb", LuckPerms: &LuckPerms{URL: "{env.MCAUTH_TEST_LP_URL}", APIKey: "{env.MCAUTH_TEST_LP_KEY}"}})
	if app.lp == nil || app.lp.baseURL != "http://lp.internal:8080" || app.lp.apiKey != "k3y" {
		t.Errorf("client: %+v", app.lp)
	}
}
