package caddymcauth

import (
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"strconv"
	"strings"
	"testing"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/caddyconfig"

	// the real server, file storage, respond, ... as in cmd/caddy
	_ "github.com/caddyserver/caddy/v2/modules/standard"
)

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

// TestEndToEnd runs a real Caddy from a Caddyfile and logs in through it with a browser-like client, against
// a fake MinecraftAuth.me whose consent page accepts immediately.
func TestEndToEnd(t *testing.T) {
	f := newFakeAPI(t)
	storage := t.TempDir() // created before Caddy so it's removed after Caddy stops
	site := "http://127.0.0.1:" + strconv.Itoa(freePort(t))

	cf := `{
	admin off
	persist_config off
	auto_https off
	storage file_system ` + storage + `
	log default {
		output discard
	}
	mcauth {
		app_id       abcdefghijklmnop
		server_token tok
		redirect     ` + site + `/mcauth/callback
		session_key  0123456789abcdef0123456789abcdef
		api_base     ` + f.srv.URL + `
		realm staff {
			player Notch
		}
		realm supporters {
			twitch_follower
		}
	}
}

` + site + ` {
	mcauth_callback /mcauth/callback
	route /vip/* {
		mcauth supporters
		respond "vip {mcauth.name}"
	}
	mcauth staff
	respond "hello {mcauth.name} {mcauth.uuid} {http.auth.user.id} {mcauth.identity}"
}
`
	cfg, _, err := caddyconfig.GetAdapter("caddyfile").Adapt([]byte(cf), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := caddy.Load(cfg, true); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = caddy.Stop() })

	browser := func() *http.Client {
		jar, _ := cookiejar.New(nil)
		return &http.Client{Jar: jar}
	}
	get := func(c *http.Client, method, path string) (int, string) {
		t.Helper()
		req, _ := http.NewRequest(method, site+path, nil)
		resp, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		body, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(body)
	}

	// Notch: protect -> fake consent page -> callback -> back to the original URL, now logged in
	notchBrowser := browser()
	code, body := get(notchBrowser, http.MethodGet, "/page?x=1")
	if want := "hello Notch " + notch + " " + notch + " 42"; code != 200 || body != want {
		t.Fatalf("got %d %q, want %q", code, body, want)
	}
	// the session cookie now carries Notch through without another login
	f.player.Store(&[2]string{"unused", "unused"})
	if code, body := get(notchBrowser, http.MethodGet, "/other"); code != 200 || !strings.HasPrefix(body, "hello Notch") {
		t.Fatalf("second request: %d %q", code, body)
	}
	// and the same session is checked against another realm's rules
	f.member.Store(true)
	if code, body := get(notchBrowser, http.MethodGet, "/vip/"); code != 200 || body != "vip Notch" {
		t.Fatalf("follower in vip: %d %q", code, body)
	}

	// jeb_ logs in fine but isn't on the staff list, and doesn't follow
	f.member.Store(false)
	f.player.Store(&[2]string{"853c80ef-3c37-49fd-aa49-938b674adae6", "jeb_"})
	jeb := browser()
	if code, _ := get(jeb, http.MethodGet, "/vip/"); code != http.StatusForbidden {
		t.Fatalf("jeb_ in vip: want 403, got %d", code)
	}
	if code, _ := get(jeb, http.MethodGet, "/page"); code != http.StatusForbidden {
		t.Fatalf("jeb_ on staff page: want 403, got %d", code)
	}
	// Notch's cached membership is unaffected by jeb_'s answer
	if code, _ := get(notchBrowser, http.MethodGet, "/vip/"); code != 200 {
		t.Fatalf("notch lost vip: %d", code)
	}

	// API clients without a session get 401 instead of a redirect
	if code, _ := get(browser(), http.MethodPost, "/page"); code != http.StatusUnauthorized {
		t.Fatalf("POST without session: want 401, got %d", code)
	}

	// visiting the callback without starting a login is rejected
	if code, _ := get(browser(), http.MethodGet, "/mcauth/callback?status=accepted&state=x&token=y"); code != http.StatusBadRequest {
		t.Fatalf("stray callback: want 400, got %d", code)
	}
}
