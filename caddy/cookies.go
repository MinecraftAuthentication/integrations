package caddymcauth

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"time"
)

// session is what the session cookie holds once a player has logged in.
type session struct {
	Subject string `json:"sub"`
	UUID    string `json:"uuid"`
	Name    string `json:"name"`
	Expires int64  `json:"exp"`
}

// loginState is what the short-lived state cookie holds between the redirect to MinecraftAuth.me and the callback.
type loginState struct {
	State   string `json:"state"`
	Return  string `json:"ret"`
	Expires int64  `json:"exp"`
}

// cookieCodec seals cookie values with AES-256-GCM; the cookie name is bound as additional data so a value
// can't be moved between cookies.
type cookieCodec struct {
	aead cipher.AEAD
}

func newCookieCodec(secret []byte) (*cookieCodec, error) {
	key := sha256.Sum256(secret)
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &cookieCodec{aead: aead}, nil
}

func (c *cookieCodec) seal(name string, v any) (string, error) {
	plain, err := json.Marshal(v)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(c.aead.Seal(nonce, nonce, plain, []byte(name))), nil
}

func (c *cookieCodec) open(name, value string, v any) error {
	raw, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return err
	}
	n := c.aead.NonceSize()
	if len(raw) < n {
		return errors.New("cookie too short")
	}
	plain, err := c.aead.Open(nil, raw[:n], raw[n:], []byte(name))
	if err != nil {
		return err
	}
	return json.Unmarshal(plain, v)
}

func isSecure(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https"
}

func (a *App) setCookie(w http.ResponseWriter, r *http.Request, name string, v any, ttl time.Duration) error {
	value, err := a.cookies.seal(name, v)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     name,
		Value:    value,
		Path:     "/",
		MaxAge:   int(ttl.Seconds()),
		HttpOnly: true,
		Secure:   isSecure(r),
		// Lax: the cookie must come back on the top-level redirect from minecraftauth.me
		SameSite: http.SameSiteLaxMode,
	})
	return nil
}

func clearCookie(w http.ResponseWriter, r *http.Request, name string) {
	http.SetCookie(w, &http.Cookie{Name: name, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: isSecure(r), SameSite: http.SameSiteLaxMode})
}

func (a *App) sessionCookie() string { return a.CookiePrefix + "_session" }
func (a *App) stateCookie() string   { return a.CookiePrefix + "_state" }

// readSession returns the player's unexpired session, if any.
func (a *App) readSession(r *http.Request) (*session, bool) {
	cookie, err := r.Cookie(a.sessionCookie())
	if err != nil {
		return nil, false
	}
	var s session
	if a.cookies.open(a.sessionCookie(), cookie.Value, &s) != nil || time.Now().Unix() > s.Expires || s.UUID == "" {
		return nil, false
	}
	return &s, true
}
