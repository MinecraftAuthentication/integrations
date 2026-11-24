package caddymcauth

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
	"github.com/golang-jwt/jwt/v5"
)

func serveRequest(t *testing.T, h caddyhttp.MiddlewareHandler, req *http.Request) (result, *caddy.Replacer) {
	t.Helper()
	repl := caddy.NewReplacer()
	req = req.WithContext(context.WithValue(req.Context(), caddy.ReplacerCtxKey, repl))
	rec := httptest.NewRecorder()
	res := result{rec: rec}
	res.err = h.ServeHTTP(rec, req, caddyhttp.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		res.nextOK = true
		return nil
	}))
	return res, repl
}

func cookieNamed(rec *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, c := range rec.Result().Cookies() {
		if c.Name == name {
			return c
		}
	}
	return nil
}

func sealedCookie(t *testing.T, app *App, name string, v any) *http.Cookie {
	t.Helper()
	value, err := app.cookies.seal(name, v)
	if err != nil {
		t.Fatal(err)
	}
	return &http.Cookie{Name: name, Value: value}
}

func staffProtect(app *App) *Protect {
	return &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}
}

func TestStartLogin(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		res, _ := serveRequest(t, staffProtect(app), httptest.NewRequest(method, "https://example.com/a/b?c=d", nil))
		if res.err != nil || res.rec.Code != http.StatusFound || res.nextOK {
			t.Fatalf("%s: want redirect, got %d %v", method, res.rec.Code, res.err)
		}
		if res.rec.Header().Get("Cache-Control") != "no-store" {
			t.Errorf("%s: login redirect must not be cached", method)
		}
		st := cookieNamed(res.rec, "mcauth_state")
		if st == nil || st.MaxAge != int(maxTokenAge.Seconds()) || !st.HttpOnly || !st.Secure || st.SameSite != http.SameSiteLaxMode || st.Path != "/" {
			t.Fatalf("%s: bad state cookie %+v", method, st)
		}
		var ls loginState
		if err := app.cookies.open("mcauth_state", st.Value, &ls); err != nil || ls.Return != "/a/b?c=d" {
			t.Fatalf("%s: state cookie %+v %v", method, ls, err)
		}
		loc, _ := url.Parse(res.rec.Header().Get("Location"))
		if loc.Query().Get("state") != ls.State || len(ls.State) < 32 {
			t.Errorf("%s: state %q vs cookie %q", method, loc.Query().Get("state"), ls.State)
		}
	}
}

func TestStartLoginStateIsRandom(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	seen := map[string]bool{}
	for range 20 {
		res, _ := serveRequest(t, staffProtect(app), httptest.NewRequest(http.MethodGet, "https://example.com/", nil))
		loc, _ := url.Parse(res.rec.Header().Get("Location"))
		state := loc.Query().Get("state")
		if seen[state] {
			t.Fatalf("state %q repeated", state)
		}
		seen[state] = true
	}
}

func TestNonBrowserRequestGets401(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodOptions} {
		res, _ := serveRequest(t, staffProtect(app), httptest.NewRequest(method, "https://example.com/api", nil))
		if status(res.err) != http.StatusUnauthorized || res.rec.Header().Get("Location") != "" || cookieNamed(res.rec, "mcauth_state") != nil {
			t.Errorf("%s: want plain 401, got %d %v", method, res.rec.Code, res.err)
		}
	}
}

func TestCookieSecureFlag(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	for name, tc := range map[string]struct {
		target, proto string
		secure        bool
	}{
		"https":          {"https://example.com/", "", true},
		"http":           {"http://example.com/", "", false},
		"behind a proxy": {"http://example.com/", "https", true},
		"forwarded http": {"http://example.com/", "http", false},
	} {
		req := httptest.NewRequest(http.MethodGet, tc.target, nil)
		if tc.proto != "" {
			req.Header.Set("X-Forwarded-Proto", tc.proto)
		}
		res, _ := serveRequest(t, staffProtect(app), req)
		if c := cookieNamed(res.rec, "mcauth_state"); c == nil || c.Secure != tc.secure {
			t.Errorf("%s: want Secure=%v, got %+v", name, tc.secure, c)
		}
	}
}

func TestPlaceholders(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	cookies := login(t, app, f, "staff", notch, "Notch")
	req := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	req.AddCookie(cookies[0])
	res, repl := serveRequest(t, staffProtect(app), req)
	if res.err != nil || !res.nextOK {
		t.Fatal(res.err)
	}
	for key, want := range map[string]string{
		"http.auth.user.id": notch, "mcauth.uuid": notch, "mcauth.name": "Notch", "mcauth.identity": "42",
	} {
		if got, _ := repl.GetString(key); got != want {
			t.Errorf("{%s} = %q, want %q", key, got, want)
		}
	}
}

func TestSessionCookieAttributes(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	app.SessionTTL = caddy.Duration(2 * time.Hour)
	cookies := login(t, app, f, "staff", notch, "Notch")
	if cookies[0].MaxAge != 7200 || cookies[0].SameSite != http.SameSiteLaxMode || cookies[0].Path != "/" {
		t.Errorf("session cookie %+v", cookies[0])
	}
	var s session
	if err := app.cookies.open(app.sessionCookie(), cookies[0].Value, &s); err != nil {
		t.Fatal(err)
	}
	if s.Subject != "42" || s.UUID != notch || s.Name != "Notch" || s.Expires-time.Now().Unix() < 7100 {
		t.Errorf("session %+v", s)
	}
}

func TestInvalidSessionsRestartLogin(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	other := newApp(t, f, "deny")
	other.cookies, _ = newCookieCodec([]byte(strings.Repeat("z", 32)))
	future := time.Now().Add(time.Hour).Unix()

	for name, cookie := range map[string]*http.Cookie{
		"expired":      sealedCookie(t, app, "mcauth_session", session{UUID: notch, Name: "Notch", Expires: time.Now().Add(-time.Second).Unix()}),
		"no uuid":      sealedCookie(t, app, "mcauth_session", session{Name: "Notch", Expires: future}),
		"other key":    sealedCookie(t, other, "mcauth_session", session{UUID: notch, Name: "Notch", Expires: future}),
		"moved cookie": {Name: "mcauth_session", Value: sealedCookie(t, app, "mcauth_state", session{UUID: notch, Name: "Notch", Expires: future}).Value},
		"not base64":   {Name: "mcauth_session", Value: "!!!"},
		"too short":    {Name: "mcauth_session", Value: "YWJj"},
		"not json":     sealedCookie(t, app, "mcauth_session", "a string"),
	} {
		req := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
		req.AddCookie(cookie)
		res, _ := serveRequest(t, staffProtect(app), req)
		if res.rec.Code != http.StatusFound || res.nextOK {
			t.Errorf("%s: want login redirect, got %d %v", name, res.rec.Code, res.err)
		}
	}

	// sanity check: the same construction with valid values is accepted
	req := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	req.AddCookie(sealedCookie(t, app, "mcauth_session", session{UUID: notch, Name: "Notch", Expires: future}))
	if res, _ := serveRequest(t, staffProtect(app), req); !res.nextOK {
		t.Fatalf("valid session rejected: %v", res.err)
	}
}

func TestCookiePrefix(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	app.CookiePrefix = "site2"
	cookies := login(t, app, f, "staff", notch, "Notch")
	if cookies[0].Name != "site2_session" {
		t.Fatalf("session cookie named %q", cookies[0].Name)
	}
	// a session for one prefix is useless under another
	req := httptest.NewRequest(http.MethodGet, "https://example.com/", nil)
	req.AddCookie(&http.Cookie{Name: "mcauth_session", Value: cookies[0].Value})
	app.CookiePrefix = "mcauth"
	if res, _ := serveRequest(t, staffProtect(app), req); res.nextOK {
		t.Fatal("cookie accepted under another name")
	}
}

func callbackRequest(t *testing.T, app *App, st loginState, q url.Values) result {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "https://example.com/mcauth/callback?"+q.Encode(), nil)
	req.AddCookie(sealedCookie(t, app, app.stateCookie(), st))
	res, _ := serveRequest(t, &Callback{app: app}, req)
	return res
}

func signClaims(t *testing.T, f *fakeAPI, claims jwt.MapClaims) string {
	t.Helper()
	s, err := jwt.NewWithClaims(jwt.SigningMethodRS512, claims).SignedString(f.key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestCallbackRejectsMore(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	valid := loginState{State: "S", Return: "/", Expires: time.Now().Add(time.Minute).Unix()}
	claims := func(extra jwt.MapClaims) jwt.MapClaims {
		c := jwt.MapClaims{"sub": "42", "iss": "MinecraftAuth.me", "aud": app.AppID, "iat": time.Now().Unix(),
			"exp": time.Now().Add(10 * time.Minute).Unix(), "minecraft": map[string]any{"identifier": notch, "name": "Notch"}}
		for k, v := range extra {
			if v == nil {
				delete(c, k)
				continue
			}
			c[k] = v
		}
		return c
	}

	for name, tc := range map[string]struct {
		st    loginState
		token string
		want  int
	}{
		"expired state":      {loginState{State: "S", Return: "/", Expires: time.Now().Add(-time.Second).Unix()}, signClaims(t, f, claims(nil)), 400},
		"no minecraft":       {valid, signClaims(t, f, claims(jwt.MapClaims{"minecraft": nil})), 403},
		"other app":          {valid, signClaims(t, f, claims(jwt.MapClaims{"aud": "someone-else"})), 403},
		"wrong issuer":       {valid, signClaims(t, f, claims(jwt.MapClaims{"iss": "evil"})), 403},
		"missing token":      {valid, "", 403},
		"no aud":             {valid, signClaims(t, f, claims(jwt.MapClaims{"aud": nil})), 403},
		"no exp":             {valid, signClaims(t, f, claims(jwt.MapClaims{"exp": nil})), 403},
		"expired":            {valid, signClaims(t, f, claims(jwt.MapClaims{"exp": time.Now().Add(-5 * time.Minute).Unix()})), 403},
		"accepted, this app": {valid, signClaims(t, f, claims(nil)), 302},
	} {
		res := callbackRequest(t, app, tc.st, url.Values{"status": {"accepted"}, "state": {"S"}, "token": {tc.token}})
		got := res.rec.Code
		if res.err != nil {
			got = status(res.err)
		}
		if got != tc.want {
			t.Errorf("%s: want %d, got %d %v", name, tc.want, got, res.err)
		}
		if cleared := cookieNamed(res.rec, app.stateCookie()); cleared == nil || cleared.MaxAge >= 0 {
			t.Errorf("%s: state cookie should always be cleared, got %+v", name, cleared)
		}
	}
}

func TestCallbackStatusesOtherThanAccepted(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	st := loginState{State: "S", Return: "/", Expires: time.Now().Add(time.Minute).Unix()}
	token := f.token(t, time.Now(), notch, "Notch")
	for _, s := range []string{"denied", "expired", "", "ACCEPTED"} {
		res := callbackRequest(t, app, st, url.Values{"status": {s}, "state": {"S"}, "token": {token}})
		if status(res.err) != http.StatusForbidden || cookieNamed(res.rec, app.sessionCookie()) != nil {
			t.Errorf("status %q: want 403 without session, got %v", s, res.err)
		}
	}
}

func TestCallbackReturnsOnlyToSameOrigin(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	for ret, want := range map[string]string{
		"/vip/page?x=1": "/vip/page?x=1",
		"//evil.com/x":  "/",
		"/\\evil.com":   "/",
		"https://evil":  "/",
	} {
		st := loginState{State: "S", Return: ret, Expires: time.Now().Add(time.Minute).Unix()}
		res := callbackRequest(t, app, st, url.Values{"status": {"accepted"}, "state": {"S"}, "token": {f.token(t, time.Now(), notch, "Notch")}})
		if res.err != nil || res.rec.Header().Get("Location") != want {
			t.Errorf("return %q: got %q %v", ret, res.rec.Header().Get("Location"), res.err)
		}
		if res.rec.Header().Get("Cache-Control") != "no-store" {
			t.Errorf("callback response must not be cached")
		}
	}
}

// Provision is exercised through caddy.Validate, which provisions every module in a config.
func validateCaddyfile(t *testing.T, cf string) error {
	t.Helper()
	out, _, err := caddyconfig.GetAdapter("caddyfile").Adapt([]byte(cf), nil)
	if err != nil {
		t.Fatal(err)
	}
	var cfg caddy.Config
	if err := json.Unmarshal(out, &cfg); err != nil {
		t.Fatal(err)
	}
	return caddy.Validate(&cfg)
}

func TestHandlerProvision(t *testing.T) {
	head := "{\n admin off\n persist_config off\n auto_https off\n storage file_system " + t.TempDir() + "\n"
	app := " mcauth {\n app_id a\n redirect https://x/cb\n session_key " + strings.Repeat("k", 32) + "\n realm staff {\n player Notch\n }\n }\n"

	if err := validateCaddyfile(t, head+app+"}\n:0 {\n mcauth_callback /cb\n mcauth staff\n respond ok\n}\n"); err != nil {
		t.Fatalf("valid config rejected: %v", err)
	}
	if err := validateCaddyfile(t, head+app+"}\n:0 {\n mcauth admins\n}\n"); err == nil || !strings.Contains(err.Error(), `unknown realm "admins"`) {
		t.Errorf("unknown realm: %v", err)
	}
	for _, site := range []string{"mcauth staff", "mcauth_callback /cb"} {
		if err := validateCaddyfile(t, head+"}\n:0 {\n "+site+"\n}\n"); err == nil || !strings.Contains(err.Error(), "mcauth global option is missing") {
			t.Errorf("%s without the mcauth app: %v", site, err)
		}
	}
	bad := " mcauth {\n app_id a\n redirect /relative\n }\n"
	if err := validateCaddyfile(t, head+bad+"}\n:0 {\n mcauth_callback /cb\n}\n"); err == nil || !strings.Contains(err.Error(), "absolute URL") {
		t.Errorf("app Validate not run: %v", err)
	}
}

func TestRegistrableDomain(t *testing.T) {
	for host, want := range map[string]string{
		"panel.minecraftauth.me": "minecraftauth.me",
		"A.B.Example.COM.":       "example.com",
		"auth.example.co.uk":     "example.co.uk",
		"user.github.io":         "user.github.io",
		"github.io":              "",
		"localhost":              "",
		"127.0.0.1":              "",
		"::1":                    "",
		"":                       "",
	} {
		if got := registrableDomain(host); got != want {
			t.Errorf("registrableDomain(%q) = %q, want %q", host, got, want)
		}
	}
}

// A login started on a sibling subdomain of the callback's host shares the state and session cookies through
// the registrable domain, and comes back to the host it started on.
func TestLoginAcrossSubdomains(t *testing.T) {
	for _, tc := range []struct{ redirect, start, domain string }{
		{"https://minecraftauth.me/callback", "https://panel.minecraftauth.me/tables?x=1", "minecraftauth.me"},
		{"https://gamecommunity.com/mcauth/callback", "https://panel.gamecommunity.com/home", "gamecommunity.com"},
		{"https://auth.gamecommunity.com/mcauth/callback", "https://panel.gamecommunity.com/home", "gamecommunity.com"},
	} {
		t.Run(tc.start+" via "+tc.redirect, func(t *testing.T) {
			f := newFakeAPI(t)
			app := newApp(t, f, "deny")
			app.RedirectURL = tc.redirect
			app.cookieDomain = registrableDomain(mustURL(t, tc.redirect).Hostname())
			protect := &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}

			res, _ := serveRequest(t, protect, httptest.NewRequest(http.MethodGet, tc.start, nil))
			stateCookie := cookieNamed(res.rec, app.stateCookie())
			if res.rec.Code != http.StatusFound || stateCookie == nil || stateCookie.Domain != tc.domain {
				t.Fatalf("want a redirect with a state cookie for %s, got %d %+v", tc.domain, res.rec.Code, stateCookie)
			}
			loc := mustURL(t, res.rec.Header().Get("Location"))
			if loc.Query().Get("redirectUrl") != tc.redirect {
				t.Fatalf("grant URL should send the player to %s, got %s", tc.redirect, loc)
			}

			q := url.Values{"status": {"accepted"}, "state": {loc.Query().Get("state")}, "token": {f.token(t, time.Now(), notch, "Notch")}}
			req := httptest.NewRequest(http.MethodGet, tc.redirect+"?"+q.Encode(), nil)
			req.AddCookie(stateCookie)
			res, _ = serveRequest(t, &Callback{app: app}, req)
			if res.err != nil || res.rec.Header().Get("Location") != tc.start {
				t.Fatalf("want redirect back to %s, got %q %v", tc.start, res.rec.Header().Get("Location"), res.err)
			}
			session := cookieNamed(res.rec, app.sessionCookie())
			if session == nil || session.Domain != tc.domain {
				t.Fatalf("session cookie should cover %s, got %+v", tc.domain, session)
			}
			if c := cookieNamed(res.rec, app.stateCookie()); c == nil || c.Domain != tc.domain || c.MaxAge >= 0 {
				t.Fatalf("state cookie should be cleared on %s, got %+v", tc.domain, c)
			}

			// back on the protected host, the session lets the player through
			req = httptest.NewRequest(http.MethodGet, tc.start, nil)
			req.AddCookie(session)
			if res, _ = serveRequest(t, protect, req); res.err != nil || !res.nextOK {
				t.Fatalf("session should let the player into %s, got %d %v", tc.start, res.rec.Code, res.err)
			}
		})
	}
}

func mustURL(t *testing.T, s string) *url.URL {
	t.Helper()
	u, err := url.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

// Cookies set for a host outside the cookie domain stay host-only, since a browser would drop them otherwise.
func TestCookieOutsideDomainIsHostOnly(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny")
	protect := &Protect{Realm: "staff", app: app, realm: app.Realms["staff"]}
	res, _ := serveRequest(t, protect, httptest.NewRequest(http.MethodGet, "https://other.net/", nil))
	if c := cookieNamed(res.rec, app.stateCookie()); c == nil || c.Domain != "" {
		t.Fatalf("want a host-only state cookie, got %+v", c)
	}
}

// The callback only returns to hosts under the cookie domain.
func TestCallbackReturnHosts(t *testing.T) {
	f := newFakeAPI(t)
	app := newApp(t, f, "deny") // callback on example.com
	for host, want := range map[string]string{
		"":                      "/p",
		"example.com":           "/p",
		"db.example.com":        "https://db.example.com/p",
		"db.example.com:8443":   "https://db.example.com:8443/p",
		"evil.com":              "/p",
		"example.com.evil.com":  "/p",
		"evilexample.com":       "/p",
		"evil.com/.example.com": "/p",
		"x@db.example.com":      "/p",
		"db.example.com#":       "/p",
	} {
		st := loginState{State: "S", Return: "/p", Host: host, Expires: time.Now().Add(time.Minute).Unix()}
		res := callbackRequest(t, app, st, url.Values{"status": {"accepted"}, "state": {"S"}, "token": {f.token(t, time.Now(), notch, "Notch")}})
		if res.err != nil || res.rec.Header().Get("Location") != want {
			t.Errorf("host %q: got %q %v, want %q", host, res.rec.Header().Get("Location"), res.err, want)
		}
	}
}
