package caddymcauth

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
	"github.com/golang-jwt/jwt/v5"
)

const notch = "069a79f4-44e9-4726-a5be-fca90e38aaf5"

func TestCaddyfileAdapt(t *testing.T) {
	cf := `{
	mcauth {
		app_id         abcdefghijklmnop
		server_token   tok
		redirect       https://example.com/mcauth/callback
		session_key    0123456789abcdef0123456789abcdef
		session_ttl    12h
		membership_ttl 1m
		on_api_error   allow
		persist        false
		scopes         discord
		realm staff {
			player 069a79f4-44e9-4726-a5be-fca90e38aaf5 Notch
		}
		realm supporters {
			twitch_subscriber 2
			discord_role 123456789
			patreon_member "Gold Tier"
		}
	}
}

example.com {
	mcauth_callback /mcauth/callback
	route /admin/* {
		mcauth staff
		respond "hi {mcauth.name}"
	}
	mcauth /vip/* supporters
	respond "ok"
}
`
	out, warnings, err := caddyconfig.GetAdapter("caddyfile").Adapt([]byte(cf), nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(warnings) > 0 {
		t.Logf("warnings: %v", warnings)
	}
	var cfg struct {
		Apps struct {
			MCAuth App `json:"mcauth"`
		} `json:"apps"`
	}
	if err := json.Unmarshal(out, &cfg); err != nil {
		t.Fatal(err)
	}
	app := cfg.Apps.MCAuth
	if app.AppID != "abcdefghijklmnop" || app.ServerToken != "tok" || app.OnAPIError != "allow" || app.Persist == nil || *app.Persist {
		t.Fatalf("unexpected app %+v", app)
	}
	if time.Duration(app.SessionTTL) != 12*time.Hour || time.Duration(app.MembershipTTL) != time.Minute {
		t.Fatalf("unexpected durations %+v", app)
	}
	if got := app.Realms["supporters"].Rules; len(got) != 3 || got[2].Type != "patreon_member" || got[2].Values[0] != "Gold Tier" {
		t.Fatalf("unexpected supporters realm %+v", got)
	}
	if got := app.Realms["staff"].Rules[0].Values; len(got) != 2 {
		t.Fatalf("unexpected staff realm %+v", got)
	}
	s := string(out)
	for _, want := range []string{`"handler":"mcauth_callback"`, `"handler":"mcauth","realm":"staff"`, `"realm":"supporters"`, `"/vip/*"`} {
		if !strings.Contains(s, want) {
			t.Errorf("adapted config is missing %s:\n%s", want, s)
		}
	}
}

func TestCaddyfileErrors(t *testing.T) {
	for name, cf := range map[string]string{
		"unknown rule":   "{\n mcauth {\n realm x {\n bogus\n }\n }\n}\n:80 {\n}\n",
		"unknown option": "{\n mcauth {\n nope 1\n }\n}\n:80 {\n}\n",
		"protect no arg": "{\n mcauth {\n app_id a\n }\n}\n:80 {\n mcauth\n}\n",
	} {
		if _, _, err := caddyconfig.GetAdapter("caddyfile").Adapt([]byte(cf), nil); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestValidate(t *testing.T) {
	base := func() *App {
		return &App{AppID: "a", RedirectURL: "https://x/cb", OnAPIError: "deny", Realms: map[string]*Realm{}}
	}
	a := base()
	a.Realms["r"] = &Realm{Rules: []Rule{{Type: "twitch_follower"}}}
	if err := a.Validate(); err == nil || !strings.Contains(err.Error(), "server_token") {
		t.Errorf("membership rule without server_token: %v", err)
	}
	a = base()
	a.Realms["r"] = &Realm{Rules: []Rule{{Type: "player"}}}
	if err := a.Validate(); err == nil {
		t.Error("player without values accepted")
	}
	a = base()
	a.RedirectURL = "/relative"
	if err := a.Validate(); err == nil {
		t.Error("relative redirect accepted")
	}
	a = base()
	a.ServerToken = "t"
	a.Realms["r"] = &Realm{Rules: []Rule{{Type: "twitch_subscriber", Values: []string{"4"}}}}
	if err := a.Validate(); err == nil {
		t.Error("tier 4 accepted")
	}
}

// --- login flow against a fake MinecraftAuth.me ---

type fakeAPI struct {
	key      *rsa.PrivateKey
	srv      *httptest.Server
	member   atomic.Bool
	fail     atomic.Int32 // HTTP status to fail membership checks with
	requests atomic.Int32
	player   atomic.Pointer[[2]string] // UUID and name the /grant consent page logs in as
}

func newFakeAPI(t *testing.T) *fakeAPI {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeAPI{key: key}
	f.player.Store(&[2]string{notch, "Notch"})
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/grant":
			// the consent page: the player accepts and is sent back with a signed token
			q := r.URL.Query()
			p := f.player.Load()
			back := url.Values{"status": {"accepted"}, "state": {q.Get("state")}, "token": {f.token(t, time.Now(), p[0], p[1])}}
			http.Redirect(w, r, q.Get("redirectUrl")+"?"+back.Encode(), http.StatusFound)
		case r.URL.Path == "/rsa":
			der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
			_ = pem.Encode(w, &pem.Block{Type: "PUBLIC KEY", Bytes: der})
		case strings.HasPrefix(r.URL.Path, "/api/v2/"):
			f.requests.Add(1)
			if r.Header.Get("Authorization") != "Bearer tok" {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			if status := f.fail.Load(); status != 0 {
				w.WriteHeader(int(status))
				_, _ = io.WriteString(w, `{"error":"upstream down","status":502}`)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]bool{"result": f.member.Load()})
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeAPI) token(t *testing.T, iat time.Time, uuid, name string) string {
	t.Helper()
	s, err := jwt.NewWithClaims(jwt.SigningMethodRS512, jwt.MapClaims{
		"sub": "42", "iss": "MinecraftAuth.me", "aud": "abcdefghijklmnop", "iat": iat.Unix(), "exp": iat.Add(10 * time.Minute).Unix(),
		"minecraft": map[string]any{"identifier": uuid, "name": name},
	}).SignedString(f.key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func newApp(t *testing.T, f *fakeAPI, onAPIError string) *App {
	t.Helper()
	app := &App{
		AppID:       "abcdefghijklmnop",
		ServerToken: "tok",
		RedirectURL: "https://example.com/mcauth/callback",
		SessionKey:  "0123456789abcdef0123456789abcdef",
		OnAPIError:  onAPIError,
		APIBase:     f.srv.URL,
		Realms: map[string]*Realm{
			"staff":      {Rules: []Rule{{Type: "player", Values: []string{"Notch"}}}},
			"supporters": {Rules: []Rule{{Type: "twitch_subscriber", Values: []string{"2"}}, {Type: "discord_server", Values: []string{"1"}}}},
		},
	}
	ctx, cancel := caddy.NewContext(caddy.Context{Context: context.Background()})
	t.Cleanup(cancel)
	if err := app.Provision(ctx); err != nil {
		t.Fatal(err)
	}
	if err := app.Validate(); err != nil {
		t.Fatal(err)
	}
	return app
}

type result struct {
	rec    *httptest.ResponseRecorder
	err    error
	nextOK bool
	name   string
}

func serve(t *testing.T, h caddyhttp.MiddlewareHandler, target string, cookies []*http.Cookie) result {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "https://example.com"+target, nil)
	for _, c := range cookies {
		req.AddCookie(c)
	}
	repl := caddy.NewReplacer()
	req = req.WithContext(context.WithValue(req.Context(), caddy.ReplacerCtxKey, repl))
	rec := httptest.NewRecorder()
	var res result
	res.err = h.ServeHTTP(rec, req, caddyhttp.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		res.nextOK = true
		res.name, _ = repl.GetString("mcauth.name")
		return nil
	}))
	res.rec = rec
	return res
}

func status(err error) int {
	var he caddyhttp.HandlerError
	if errors.As(err, &he) {
		return he.StatusCode
	}
	return 0
}

// login walks through protect -> MinecraftAuth.me -> callback and returns the session cookie.
func login(t *testing.T, app *App, f *fakeAPI, realm, uuid, name string) []*http.Cookie {
	t.Helper()
	protect := &Protect{Realm: realm, app: app, realm: app.Realms[realm]}
	res := serve(t, protect, "/secret?x=1", nil)
	if res.err != nil || res.rec.Code != http.StatusFound {
		t.Fatalf("expected redirect to login, got %d %v", res.rec.Code, res.err)
	}
	loc, _ := url.Parse(res.rec.Header().Get("Location"))
	if loc.Path != "/grant" || loc.Query().Get("app") != app.AppID || loc.Query().Get("redirectUrl") != app.RedirectURL || loc.Query().Get("scopes") != "minecraft" {
		t.Fatalf("unexpected grant URL %s", loc)
	}
	state := loc.Query().Get("state")

	callback := &Callback{app: app}
	q := url.Values{"status": {"accepted"}, "state": {state}, "token": {f.token(t, time.Now(), uuid, name)}}
	res = serve(t, callback, "/mcauth/callback?"+q.Encode(), res.rec.Result().Cookies())
	if res.err != nil || res.rec.Code != http.StatusFound || res.rec.Header().Get("Location") != "/secret?x=1" {
		t.Fatalf("callback: %d %v %s", res.rec.Code, res.err, res.rec.Header().Get("Location"))
	}
	var session []*http.Cookie
	for _, c := range res.rec.Result().Cookies() {
		if c.Name == app.sessionCookie() && c.Value != "" {
			if !c.HttpOnly || !c.Secure {
				t.Errorf("session cookie should be HttpOnly and Secure: %+v", c)
			}
			session = append(session, c)
		}
	}
	if len(session) != 1 {
		t.Fatal("no session cookie issued")
	}
	return session
}

func TestLoginFlowPlayerRule(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	cookies := login(t, app, f, "staff", notch, "Notch")

	protect := &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}
	res := serve(t, protect, "/secret", cookies)
	if res.err != nil || !res.nextOK || res.name != "Notch" {
		t.Fatalf("expected access, got %v next=%v name=%q", res.err, res.nextOK, res.name)
	}
	if f.requests.Load() != 0 {
		t.Errorf("player rule shouldn't call the API")
	}

	other := login(t, app, f, "staff", "853c80ef-3c37-49fd-aa49-938b674adae6", "jeb_")
	if res := serve(t, protect, "/secret", other); status(res.err) != http.StatusForbidden || res.nextOK {
		t.Fatalf("expected 403, got %v", res.err)
	}
}

func TestMembershipRulesAndCache(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	cookies := login(t, app, f, "supporters", notch, "Notch")
	protect := &Protect{Realm: "supporters", app: app, realm: app.Realms["supporters"]}

	if res := serve(t, protect, "/", cookies); status(res.err) != http.StatusForbidden {
		t.Fatalf("non-member should get 403, got %v", res.err)
	}
	if n := f.requests.Load(); n != 2 {
		t.Fatalf("expected both rules checked, got %d requests", n)
	}
	// cached: no new requests, still denied even though the fake now says yes
	f.member.Store(true)
	if res := serve(t, protect, "/", cookies); status(res.err) != http.StatusForbidden || f.requests.Load() != 2 {
		t.Fatalf("expected cached denial, got %v after %d requests", res.err, f.requests.Load())
	}
	app.cache = newMembershipCache(time.Minute)
	if res := serve(t, protect, "/", cookies); res.err != nil || !res.nextOK {
		t.Fatalf("member should pass, got %v", res.err)
	}
}

func TestAPIErrorPolicy(t *testing.T) {
	f := newFakeAPI(t)
	f.fail.Store(http.StatusBadGateway)

	deny := newApp(t, f, "deny")
	cookies := login(t, deny, f, "supporters", notch, "Notch")
	protect := &Protect{Realm: "supporters", app: deny, realm: deny.Realms["supporters"]}
	if res := serve(t, protect, "/", cookies); status(res.err) != http.StatusServiceUnavailable {
		t.Fatalf("deny policy: want 503, got %v", res.err)
	}

	allow := newApp(t, f, "allow")
	cookies = login(t, allow, f, "supporters", notch, "Notch")
	protect = &Protect{Realm: "supporters", app: allow, realm: allow.Realms["supporters"]}
	if res := serve(t, protect, "/", cookies); res.err != nil || !res.nextOK {
		t.Fatalf("allow policy: want pass, got %v", res.err)
	}
}

func TestCallbackRejects(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	protect := &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}
	callback := &Callback{app: app}

	start := func() (string, []*http.Cookie) {
		res := serve(t, protect, "/", nil)
		loc, _ := url.Parse(res.rec.Header().Get("Location"))
		return loc.Query().Get("state"), res.rec.Result().Cookies()
	}

	state, cookies := start()
	for name, tc := range map[string]struct {
		query   url.Values
		cookies []*http.Cookie
		want    int
	}{
		"no state cookie": {url.Values{"status": {"accepted"}, "state": {state}, "token": {f.token(t, time.Now(), notch, "Notch")}}, nil, 400},
		"wrong state":     {url.Values{"status": {"accepted"}, "state": {"nope"}, "token": {f.token(t, time.Now(), notch, "Notch")}}, cookies, 400},
		"canceled":        {url.Values{"status": {"canceled"}, "state": {state}}, cookies, 403},
		"stale token":     {url.Values{"status": {"accepted"}, "state": {state}, "token": {f.token(t, time.Now().Add(-time.Hour), notch, "Notch")}}, cookies, 403},
		"forged token":    {url.Values{"status": {"accepted"}, "state": {state}, "token": {"a.b.c"}}, cookies, 403},
	} {
		res := serve(t, callback, "/mcauth/callback?"+tc.query.Encode(), tc.cookies)
		if status(res.err) != tc.want {
			t.Errorf("%s: want %d, got %v", name, tc.want, res.err)
		}
	}
}

func TestSafeReturn(t *testing.T) {
	for in, want := range map[string]string{"/a?b=c": "/a?b=c", "//evil.com": "/", "https://evil.com": "/", "/\\evil.com": "/", "": "/"} {
		if got := safeReturn(in); got != want {
			t.Errorf("safeReturn(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestTamperedSessionCookie(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	cookies := login(t, app, f, "staff", notch, "Notch")
	cookies[0].Value = cookies[0].Value[:len(cookies[0].Value)-2] + "xx"
	protect := &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}
	if res := serve(t, protect, "/", cookies); res.rec.Code != http.StatusFound || res.nextOK {
		t.Fatalf("tampered cookie should restart login, got %d %v", res.rec.Code, res.err)
	}
}
