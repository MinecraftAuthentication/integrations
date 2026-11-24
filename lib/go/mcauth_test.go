package mcauth

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
	"sync/atomic"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

const notch = "069a79f4-44e9-4726-a5be-fca90e38aaf5"

func newClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return &Client{BaseURL: srv.URL, UserAgent: "Test/1.0", Credentials: ServerToken("tok")}
}

func reply(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}

func TestLookup(t *testing.T) {
	var got *http.Request
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		got = r
		reply(w, 200, `{"accounts":{"minecraft":{"identifier":"`+notch+`"},"twitch":{"hint":true}}}`)
	})
	res, err := c.Lookup(context.Background(), Minecraft, notch, Twitch, Discord)
	if err != nil {
		t.Fatal(err)
	}
	if got.URL.Path != "/api/v2/lookup/minecraft/"+notch || got.URL.Query().Get("include") != "twitch,discord" {
		t.Fatalf("unexpected request %s", got.URL)
	}
	if got.Header.Get("User-Agent") != "Test/1.0" || got.Header.Get("Authorization") != "Bearer tok" {
		t.Fatalf("unexpected headers %v", got.Header)
	}
	if *res.Accounts[Minecraft].Identifier != notch || !res.Accounts[Twitch].Hint {
		t.Fatalf("unexpected result %+v", res)
	}
	if _, ok := res.Accounts[Discord]; ok {
		t.Fatal("discord should be absent")
	}
}

func TestLookupNotFound(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 404, `{"error":"nope","status":404}`) })
	res, err := c.Lookup(context.Background(), Discord, "1")
	if err != nil || res != nil {
		t.Fatalf("want nil, nil; got %v, %v", res, err)
	}
}

func TestMembership(t *testing.T) {
	var queries []string
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		queries = append(queries, r.URL.Path+"?"+r.URL.RawQuery)
		reply(w, 200, `{"result":true}`)
	})
	ctx := context.Background()
	for _, check := range []func() (bool, error){
		func() (bool, error) { return c.Following(ctx, notch, Twitch) },
		func() (bool, error) { return c.Subscribed(ctx, notch, Twitch, "2") },
		func() (bool, error) { return c.DiscordPresent(ctx, notch, "123") },
		func() (bool, error) { return c.DiscordRole(ctx, notch, "Mod Team", "987") },
		func() (bool, error) { return c.DiscordRole(ctx, notch, "456", "") },
	} {
		if ok, err := check(); !ok || err != nil {
			t.Fatalf("want true, nil; got %v, %v", ok, err)
		}
	}
	want := []string{
		"/api/v2/following?minecraft=" + notch + "&platform=twitch",
		"/api/v2/subscribed?data=2&minecraft=" + notch + "&platform=twitch",
		"/api/v2/discord/present?minecraft=" + notch + "&server=123",
		"/api/v2/subscribed?data=Mod+Team&minecraft=" + notch + "&platform=discord&server=987",
		"/api/v2/subscribed?data=456&minecraft=" + notch + "&platform=discord",
	}
	for i := range want {
		if queries[i] != want[i] {
			t.Errorf("request %d: got %s want %s", i, queries[i], want[i])
		}
	}
}

func TestMembershipErrors(t *testing.T) {
	for _, tc := range []struct {
		status       int
		member       bool
		config, temp bool
		expectErr    bool
	}{
		{404, false, false, false, false},
		{400, false, true, false, true},
		{403, false, true, false, true},
		{409, false, false, true, true},
		{502, false, false, true, true},
	} {
		c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, tc.status, `{"error":"x","status":0}`) })
		ok, err := c.Subscribed(context.Background(), notch, Patreon, "Gold")
		if ok != tc.member || (err != nil) != tc.expectErr || IsConfig(err) != tc.config || IsTemporary(err) != tc.temp {
			t.Errorf("status %d: ok=%v err=%v", tc.status, ok, err)
		}
	}
}

func TestNetworkErrorIsTemporary(t *testing.T) {
	c := &Client{BaseURL: "http://127.0.0.1:1", UserAgent: "Test/1.0"}
	_, err := c.Following(context.Background(), notch, Twitch)
	if !IsTemporary(err) {
		t.Fatalf("want temporary, got %v", err)
	}
}

func TestGrantRequests(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Basic YXBwOnNoaA==" {
			reply(w, 403, `{"error":"bad creds"}`)
			return
		}
		if r.Method == http.MethodPost {
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			if body["persist"] != false {
				t.Errorf("persist not sent: %v", body)
			}
			reply(w, 201, `{"id":"r1","code":"ABCD-EFGH","url":"u","verificationUrl":"v","scopes":["minecraft"],"persist":false,"expiresAt":1,"interval":5}`)
			return
		}
		reply(w, 200, `{"status":"accepted","expiresAt":1,"token":"a.b.c"}`)
	})
	c.Credentials = ApplicationCredentials{ID: "app", Secret: "shh"}
	req, err := c.CreateGrantRequest(context.Background(), nil, false)
	if err != nil || req.Code != "ABCD-EFGH" {
		t.Fatalf("create: %+v %v", req, err)
	}
	st, err := c.GetGrantRequest(context.Background(), req.ID)
	if err != nil || st.Status != GrantAccepted || st.Token != "a.b.c" {
		t.Fatalf("get: %+v %v", st, err)
	}
}

func TestParsePlatform(t *testing.T) {
	if p, ok := ParsePlatform("YouTube"); !ok || p != Google {
		t.Fatal("youtube alias")
	}
	if _, ok := ParsePlatform("glimpse"); ok {
		t.Fatal("unknown platform accepted")
	}
}

// --- grant tokens ---

func keyServer(t *testing.T, keys ...*rsa.PrivateKey) (*KeySource, *atomic.Int32) {
	t.Helper()
	var fetches atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		i := int(fetches.Add(1)) - 1
		if i >= len(keys) {
			i = len(keys) - 1
		}
		der, _ := x509.MarshalPKIXPublicKey(&keys[i].PublicKey)
		_ = pem.Encode(w, &pem.Block{Type: "PUBLIC KEY", Bytes: der})
	})
	return &KeySource{Client: c, RefreshInterval: time.Nanosecond}, &fetches
}

func sign(t *testing.T, key *rsa.PrivateKey, method jwt.SigningMethod, claims jwt.MapClaims) string {
	t.Helper()
	s, err := jwt.NewWithClaims(method, claims).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func genKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func validClaims(iat time.Time) jwt.MapClaims {
	return jwt.MapClaims{
		"sub": "1", "iss": Issuer, "aud": "my-app", "iat": iat.Unix(), "exp": iat.Add(10 * time.Minute).Unix(),
		"minecraft": map[string]any{"identifier": notch, "name": "Notch", "avatar": nil},
		"discord":   map[string]any{"identifier": "1234", "name": "notch"},
	}
}

func TestVerifyGrantToken(t *testing.T) {
	key := genKey(t)
	keys, _ := keyServer(t, key)
	now := time.Now()
	claims, err := VerifyGrantToken(context.Background(), keys, sign(t, key, jwt.SigningMethodRS512, validClaims(now)), VerifyOptions{MaxAge: 10 * time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	mc, ok := claims.Minecraft()
	if !ok || mc.Identifier != notch || mc.Name != "Notch" || claims.Subject != "1" || claims.Accounts[Discord].Identifier != "1234" {
		t.Fatalf("unexpected claims %+v", claims)
	}
}

func TestVerifyGrantTokenRejects(t *testing.T) {
	key, other := genKey(t), genKey(t)
	now := time.Now()
	stale := validClaims(now.Add(-time.Hour))
	wrongIss := validClaims(now)
	wrongIss["iss"] = "evil"
	wrongAud := validClaims(now)
	wrongAud["aud"] = "other-app"

	for name, token := range map[string]string{
		"wrong key":    sign(t, other, jwt.SigningMethodRS512, validClaims(now)),
		"wrong alg":    sign(t, key, jwt.SigningMethodRS256, validClaims(now)),
		"stale":        sign(t, key, jwt.SigningMethodRS512, stale),
		"wrong issuer": sign(t, key, jwt.SigningMethodRS512, wrongIss),
		"wrong aud":    sign(t, key, jwt.SigningMethodRS512, wrongAud),
		"garbage":      "a.b.c",
	} {
		keys, _ := keyServer(t, key)
		_, err := VerifyGrantToken(context.Background(), keys, token, VerifyOptions{MaxAge: 10 * time.Minute, Audience: "my-app"})
		if !errors.Is(err, ErrInvalidToken) {
			t.Errorf("%s: want ErrInvalidToken, got %v", name, err)
		}
	}
}

func TestKeyRotationRefetches(t *testing.T) {
	oldKey, newKey := genKey(t), genKey(t)
	keys, fetches := keyServer(t, oldKey, newKey)
	if _, err := keys.Key(context.Background()); err != nil {
		t.Fatal(err)
	}
	token := sign(t, newKey, jwt.SigningMethodRS512, validClaims(time.Now()))
	if _, err := VerifyGrantToken(context.Background(), keys, token, VerifyOptions{}); err != nil {
		t.Fatalf("rotated key not picked up: %v", err)
	}
	if fetches.Load() != 2 {
		t.Fatalf("want 2 key fetches, got %d", fetches.Load())
	}
}
