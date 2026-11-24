package mcauth

import (
	"context"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

func verify(t *testing.T, claims jwt.MapClaims, opts VerifyOptions) (*GrantClaims, error) {
	t.Helper()
	key := genKey(t)
	keys, _ := keyServer(t, key)
	return VerifyGrantToken(context.Background(), keys, sign(t, key, jwt.SigningMethodRS512, claims), opts)
}

func TestVerifyNumericSubject(t *testing.T) {
	c := validClaims(time.Now())
	c["sub"] = 1234567890123
	claims, err := verify(t, c, VerifyOptions{})
	if err != nil || claims.Subject != "1234567890123" {
		t.Fatalf("got %+v, %v", claims, err)
	}
}

func TestVerifyRequiresSubjectAndIssuedAt(t *testing.T) {
	noSub := validClaims(time.Now())
	delete(noSub, "sub")
	noIat := validClaims(time.Now())
	delete(noIat, "iat")
	boolSub := validClaims(time.Now())
	boolSub["sub"] = true
	for name, c := range map[string]jwt.MapClaims{"no sub": noSub, "no iat": noIat, "bool sub": boolSub} {
		if _, err := verify(t, c, VerifyOptions{}); !errors.Is(err, ErrInvalidToken) {
			t.Errorf("%s: want ErrInvalidToken, got %v", name, err)
		}
	}
}

func TestVerifyExpiration(t *testing.T) {
	now := time.Now()
	c := validClaims(now)
	c["exp"] = now.Add(time.Hour).Unix()
	claims, err := verify(t, c, VerifyOptions{})
	if err != nil || claims.ExpiresAt.Unix() != now.Add(time.Hour).Unix() {
		t.Fatalf("got %+v, %v", claims, err)
	}

	c = validClaims(now.Add(-time.Hour))
	c["exp"] = now.Add(-10 * time.Minute).Unix()
	if _, err := verify(t, c, VerifyOptions{}); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expired token: want ErrInvalidToken, got %v", err)
	}

	c = validClaims(now)
	delete(c, "exp")
	if _, err := verify(t, c, VerifyOptions{}); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("no exp claim: want ErrInvalidToken, got %v", err)
	}
}

func TestVerifyAudience(t *testing.T) {
	now := time.Now()
	single := validClaims(now)
	single["aud"] = "my-app"
	multi := validClaims(now)
	multi["aud"] = []string{"other", "my-app"}

	for name, tc := range map[string]struct {
		claims   jwt.MapClaims
		audience string
		want     []string
	}{
		"single match":            {single, "my-app", []string{"my-app"}},
		"one of several":          {multi, "my-app", []string{"other", "my-app"}},
		"aud claim, none checked": {multi, "", []string{"other", "my-app"}},
	} {
		claims, err := verify(t, tc.claims, VerifyOptions{Audience: tc.audience})
		if err != nil || strings.Join(claims.Audience, ",") != strings.Join(tc.want, ",") {
			t.Errorf("%s: got %+v, %v", name, claims, err)
		}
	}

	// a token without aud wasn't issued to this application
	noAud := validClaims(now)
	delete(noAud, "aud")
	if _, err := verify(t, noAud, VerifyOptions{Audience: "my-app"}); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("no aud claim: want ErrInvalidToken, got %v", err)
	}
}

func TestVerifyClock(t *testing.T) {
	iat := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	at := func(d time.Duration) func() time.Time { return func() time.Time { return iat.Add(d) } }
	for name, tc := range map[string]struct {
		opts VerifyOptions
		ok   bool
	}{
		"fresh":                  {VerifyOptions{MaxAge: 10 * time.Minute, Now: at(5 * time.Minute)}, true},
		"old, within leeway":     {VerifyOptions{MaxAge: 10 * time.Minute, Now: at(10*time.Minute + 30*time.Second)}, true},
		"too old":                {VerifyOptions{MaxAge: 10 * time.Minute, Now: at(12 * time.Minute)}, false},
		"too old, custom leeway": {VerifyOptions{MaxAge: 10 * time.Minute, Leeway: time.Second, Now: at(10*time.Minute + 30*time.Second)}, false},
		"no max age, before exp": {VerifyOptions{Now: at(9 * time.Minute)}, true},
		"no max age, after exp":  {VerifyOptions{Now: at(365 * 24 * time.Hour)}, false},
		"issued in the future":   {VerifyOptions{Now: at(-5 * time.Minute)}, false},
		"future, within leeway":  {VerifyOptions{Now: at(-30 * time.Second)}, true},
	} {
		_, err := verify(t, validClaims(iat), tc.opts)
		if (err == nil) != tc.ok {
			t.Errorf("%s: ok=%v, err=%v", name, tc.ok, err)
		}
	}
}

func TestVerifyAccounts(t *testing.T) {
	c := validClaims(time.Now())
	c["minecraft"] = map[string]any{"identifier": notch, "name": "Notch", "avatar": "https://crafatar.com/x"}
	c["twitch"] = map[string]any{"name": "no identifier"}
	c["patreon"] = "not an object"
	c["google"] = map[string]any{"identifier": "UC123"}
	c["unknown"] = map[string]any{"identifier": "ignored"}
	claims, err := verify(t, c, VerifyOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if mc, _ := claims.Minecraft(); mc.Avatar != "https://crafatar.com/x" {
		t.Errorf("avatar not read: %+v", mc)
	}
	if g := claims.Accounts[Google]; g.Identifier != "UC123" || g.Name != "" {
		t.Errorf("google = %+v", g)
	}
	for _, p := range []Platform{Twitch, Patreon} {
		if _, ok := claims.Accounts[p]; ok {
			t.Errorf("%s should be skipped", p)
		}
	}
	if len(claims.Accounts) != 3 {
		t.Errorf("accounts = %+v", claims.Accounts)
	}
}

func TestVerifyWithoutMinecraft(t *testing.T) {
	c := validClaims(time.Now())
	delete(c, "minecraft")
	claims, err := verify(t, c, VerifyOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := claims.Minecraft(); ok {
		t.Error("Minecraft() should report a missing account")
	}
}

func TestKeySourceErrors(t *testing.T) {
	for name, handler := range map[string]http.HandlerFunc{
		"server error": func(w http.ResponseWriter, r *http.Request) { reply(w, 503, `{"error":"maintenance"}`) },
		"not PEM":      func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("hello")) },
	} {
		t.Run(name, func(t *testing.T) {
			keys := &KeySource{Client: newClient(t, handler)}
			if _, err := keys.Key(context.Background()); err == nil {
				t.Fatal("want error")
			}
			key := genKey(t)
			_, err := VerifyGrantToken(context.Background(), keys, sign(t, key, jwt.SigningMethodRS512, validClaims(time.Now())), VerifyOptions{})
			if !errors.Is(err, ErrInvalidToken) {
				t.Fatalf("want ErrInvalidToken, got %v", err)
			}
		})
	}
}

func TestKeySourceRequestsRSAPath(t *testing.T) {
	key := genKey(t)
	var path, auth string
	keys := &KeySource{Client: newClient(t, func(w http.ResponseWriter, r *http.Request) {
		path, auth = r.URL.Path, r.Header.Get("Authorization")
		der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
		_ = pem.Encode(w, &pem.Block{Type: "PUBLIC KEY", Bytes: der})
	})}
	got, err := keys.Key(context.Background())
	if err != nil || !got.Equal(&key.PublicKey) {
		t.Fatalf("got %v, %v", got, err)
	}
	if path != "/rsa" || auth != "Bearer tok" {
		t.Errorf("path=%q auth=%q", path, auth)
	}
}

func TestKeyRefreshIsThrottled(t *testing.T) {
	oldKey, newKey := genKey(t), genKey(t)
	keys, fetches := keyServer(t, oldKey, newKey)
	keys.RefreshInterval = time.Hour
	token := sign(t, newKey, jwt.SigningMethodRS512, validClaims(time.Now()))
	for i := range 3 {
		if _, err := VerifyGrantToken(context.Background(), keys, token, VerifyOptions{}); !errors.Is(err, ErrInvalidToken) {
			t.Fatalf("attempt %d: want ErrInvalidToken, got %v", i, err)
		}
	}
	if fetches.Load() != 1 {
		t.Fatalf("want 1 key fetch within the refresh interval, got %d", fetches.Load())
	}
}

func TestKeyCachedAcrossVerifications(t *testing.T) {
	key := genKey(t)
	keys, fetches := keyServer(t, key)
	token := sign(t, key, jwt.SigningMethodRS512, validClaims(time.Now()))
	var wg sync.WaitGroup
	for range 16 {
		wg.Go(func() {
			if _, err := VerifyGrantToken(context.Background(), keys, token, VerifyOptions{}); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	if fetches.Load() != 1 {
		t.Fatalf("want 1 key fetch, got %d", fetches.Load())
	}
}

func TestRefreshKeepsKeyWhenUnchanged(t *testing.T) {
	key := genKey(t)
	keys, fetches := keyServer(t, key)
	if _, err := keys.Key(context.Background()); err != nil {
		t.Fatal(err)
	}
	keys.fetchedAt = keys.fetchedAt.Add(-time.Second) // past RefreshInterval even on coarse clocks
	// a bad signature triggers a refetch, which returns the same key: no retry
	_, err := VerifyGrantToken(context.Background(), keys, sign(t, genKey(t), jwt.SigningMethodRS512, validClaims(time.Now())), VerifyOptions{})
	if !errors.Is(err, ErrInvalidToken) || fetches.Load() != 2 {
		t.Fatalf("got %v after %d fetches", err, fetches.Load())
	}
	if k, _ := keys.Key(context.Background()); !k.Equal(&key.PublicKey) {
		t.Fatal("key changed")
	}
}
