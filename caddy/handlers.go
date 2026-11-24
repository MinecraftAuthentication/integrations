package caddymcauth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
	"go.uber.org/zap"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go/v2"
)

func init() {
	caddy.RegisterModule(&Protect{})
	caddy.RegisterModule(&Callback{})
}

// maxTokenAge bounds how old a grant token may be when it reaches the callback, on top of its exp claim.
const maxTokenAge = 10 * time.Minute

// Protect only lets players through who are logged in with MinecraftAuth.me and match the realm's rules.
// Players without a session are redirected to MinecraftAuth.me to log in.
//
// For allowed requests it sets the placeholders {http.auth.user.id} (Minecraft UUID), {mcauth.uuid},
// {mcauth.name} and {mcauth.identity} (MinecraftAuth.me identity ID).
type Protect struct {
	// Realm is the name of a realm defined in the mcauth app.
	Realm string `json:"realm"`

	app   *App
	realm *Realm
}

// CaddyModule returns the Caddy module information.
func (*Protect) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "http.handlers.mcauth",
		New: func() caddy.Module { return new(Protect) },
	}
}

// Provision looks the realm up in the mcauth app.
func (p *Protect) Provision(ctx caddy.Context) error {
	app, err := loadApp(ctx)
	if err != nil {
		return err
	}
	p.app = app
	realm, ok := app.Realms[p.Realm]
	if !ok {
		return fmt.Errorf("mcauth: unknown realm %q", p.Realm)
	}
	p.realm = realm
	return nil
}

func loadApp(ctx caddy.Context) (*App, error) {
	appIface, err := ctx.App("mcauth")
	if err != nil {
		return nil, fmt.Errorf("mcauth: the mcauth global option is missing: %v", err)
	}
	return appIface.(*App), nil
}

func (p *Protect) ServeHTTP(w http.ResponseWriter, r *http.Request, next caddyhttp.Handler) error {
	a := p.app
	s, ok := a.readSession(r)
	if !ok {
		return p.startLogin(w, r)
	}

	switch a.evaluate(r.Context(), p.realm, s) {
	case allowed:
	case unavailable:
		if a.OnAPIError != "allow" {
			return caddyhttp.Error(http.StatusServiceUnavailable, fmt.Errorf("mcauth: couldn't check %s's linked accounts", s.Name))
		}
	default:
		a.logger.Debug("mcauth: denied", zap.String("realm", p.Realm), zap.String("player", s.Name), zap.String("uuid", s.UUID))
		return caddyhttp.Error(http.StatusForbidden, fmt.Errorf("mcauth: %s is not allowed into realm %s", s.Name, p.Realm))
	}

	repl := r.Context().Value(caddy.ReplacerCtxKey).(*caddy.Replacer)
	repl.Set("http.auth.user.id", s.UUID)
	repl.Set("mcauth.uuid", s.UUID)
	repl.Set("mcauth.name", s.Name)
	repl.Set("mcauth.identity", s.Subject)
	return next.ServeHTTP(w, r)
}

// startLogin redirects a browser to MinecraftAuth.me; other requests just get 401.
func (p *Protect) startLogin(w http.ResponseWriter, r *http.Request) error {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return caddyhttp.Error(http.StatusUnauthorized, fmt.Errorf("mcauth: login required"))
	}
	state, err := randomString(24)
	if err != nil {
		return err
	}
	st := loginState{State: state, Return: r.URL.RequestURI(), Host: r.Host, Expires: time.Now().Add(maxTokenAge).Unix()}
	if err := p.app.setCookie(w, r, p.app.stateCookie(), st, maxTokenAge); err != nil {
		return err
	}
	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, p.app.grantURL(state), http.StatusFound)
	return nil
}

// Callback receives players back from MinecraftAuth.me, verifies the signed grant token and starts their session.
// Serve it at the path of the app's redirect URL.
type Callback struct {
	app *App
}

// CaddyModule returns the Caddy module information.
func (*Callback) CaddyModule() caddy.ModuleInfo {
	return caddy.ModuleInfo{
		ID:  "http.handlers.mcauth_callback",
		New: func() caddy.Module { return new(Callback) },
	}
}

// Provision implements caddy.Provisioner.
func (c *Callback) Provision(ctx caddy.Context) error {
	app, err := loadApp(ctx)
	c.app = app
	return err
}

func (c *Callback) ServeHTTP(w http.ResponseWriter, r *http.Request, _ caddyhttp.Handler) error {
	a := c.app
	q := r.URL.Query()

	cookie, err := r.Cookie(a.stateCookie())
	if err != nil {
		return caddyhttp.Error(http.StatusBadRequest, fmt.Errorf("mcauth: login expired or started elsewhere, please try again"))
	}
	a.clearCookie(w, r, a.stateCookie())
	var st loginState
	if a.cookies.open(a.stateCookie(), cookie.Value, &st) != nil || time.Now().Unix() > st.Expires {
		return caddyhttp.Error(http.StatusBadRequest, fmt.Errorf("mcauth: login expired, please try again"))
	}
	if subtle.ConstantTimeCompare([]byte(q.Get("state")), []byte(st.State)) != 1 {
		return caddyhttp.Error(http.StatusBadRequest, fmt.Errorf("mcauth: state mismatch"))
	}
	if q.Get("status") != "accepted" {
		return caddyhttp.Error(http.StatusForbidden, fmt.Errorf("mcauth: login was canceled"))
	}

	claims, err := mcauth.VerifyGrantToken(r.Context(), a.keys, q.Get("token"), mcauth.VerifyOptions{
		MaxAge:   maxTokenAge,
		Audience: a.AppID,
	})
	if err != nil {
		a.logger.Warn("mcauth: rejected grant token", zap.Error(err))
		return caddyhttp.Error(http.StatusForbidden, err)
	}
	mc, ok := claims.Minecraft()
	if !ok {
		return caddyhttp.Error(http.StatusForbidden, fmt.Errorf("mcauth: grant token has no Minecraft account"))
	}

	ttl := time.Duration(a.SessionTTL)
	s := session{Subject: claims.Subject, UUID: mc.Identifier, Name: mc.Name, Expires: time.Now().Add(ttl).Unix()}
	if err := a.setCookie(w, r, a.sessionCookie(), s, ttl); err != nil {
		return err
	}
	a.logger.Info("mcauth: player logged in", zap.String("player", mc.Name), zap.String("uuid", mc.Identifier))

	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, a.returnURL(r, st), http.StatusFound)
	return nil
}

// returnURL is where the callback sends the player after logging in: back to the page they asked for, on the
// host they asked for it on when that is another host under the cookie domain, else on this host.
func (a *App) returnURL(r *http.Request, st loginState) string {
	path := safeReturn(st.Return)
	if st.Host == "" || strings.EqualFold(st.Host, r.Host) {
		return path
	}
	// the state cookie is sealed, but check the host anyway so the callback can never redirect off-site
	u, err := url.Parse("//" + st.Host)
	if err != nil || u.Host != st.Host || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || !a.inCookieDomain(u.Hostname()) {
		return path
	}
	scheme := "http"
	if isSecure(r) {
		scheme = "https"
	}
	return scheme + "://" + u.Host + path
}

// safeReturn only allows same-origin paths, so the callback can't be used as an open redirect.
// Control characters are refused outright: browsers strip tabs and newlines, so "/\t/evil.com" would become
// "//evil.com". (A RequestURI never contains them, they're escaped.)
func safeReturn(ret string) string {
	if !strings.HasPrefix(ret, "/") || strings.HasPrefix(ret, "//") || strings.HasPrefix(ret, "/\\") {
		return "/"
	}
	if strings.IndexFunc(ret, func(r rune) bool { return r < 0x20 || r == 0x7f }) >= 0 {
		return "/"
	}
	return ret
}

func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// Interface guards
var (
	_ caddyhttp.MiddlewareHandler = (*Protect)(nil)
	_ caddy.Provisioner           = (*Protect)(nil)
	_ caddyhttp.MiddlewareHandler = (*Callback)(nil)
	_ caddy.Provisioner           = (*Callback)(nil)
)
