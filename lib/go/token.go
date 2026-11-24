package mcauth

import (
	"context"
	"crypto/rsa"
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Issuer is the iss claim of grant tokens.
const Issuer = "MinecraftAuth.me"

// GrantAccount is an account included in a grant token. Name and Avatar may be empty.
type GrantAccount struct {
	Identifier string `json:"identifier"`
	Name       string `json:"name,omitempty"`
	Avatar     string `json:"avatar,omitempty"`
}

// GrantClaims are the verified contents of a grant token.
type GrantClaims struct {
	// Subject is the player's MinecraftAuth.me identity ID: stable, use it as your user key.
	Subject  string
	IssuedAt time.Time
	// ExpiresAt is when the token expires; a verified token always has one.
	ExpiresAt time.Time
	Audience  []string
	Accounts  map[Platform]GrantAccount
}

// Minecraft returns the player's Minecraft account from the token, if present.
func (c *GrantClaims) Minecraft() (GrantAccount, bool) {
	a, ok := c.Accounts[Minecraft]
	return a, ok
}

// ErrInvalidToken wraps every grant token verification failure.
var ErrInvalidToken = errors.New("mcauth: invalid grant token")

// KeySource provides the RSA key that signs grant tokens, fetched from {BaseURL}/rsa and cached.
// A verification failure triggers a refetch (at most once per RefreshInterval) in case the key rotated.
type KeySource struct {
	Client *Client
	// RefreshInterval defaults to one minute.
	RefreshInterval time.Duration

	mu        sync.Mutex
	key       *rsa.PublicKey
	fetchedAt time.Time
}

// Key returns the cached key, fetching it the first time.
func (s *KeySource) Key(ctx context.Context) (*rsa.PublicKey, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.key != nil {
		return s.key, nil
	}
	return s.fetchLocked(ctx)
}

// refresh refetches the key unless it was fetched within RefreshInterval. It reports whether the key changed.
func (s *KeySource) refresh(ctx context.Context) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	interval := s.RefreshInterval
	if interval == 0 {
		interval = time.Minute
	}
	if time.Since(s.fetchedAt) < interval {
		return false
	}
	old := s.key
	key, err := s.fetchLocked(ctx)
	return err == nil && (old == nil || !old.Equal(key))
}

func (s *KeySource) fetchLocked(ctx context.Context) (*rsa.PublicKey, error) {
	s.fetchedAt = time.Now()
	resp, err := s.Client.raw(ctx, http.MethodGet, "/rsa", nil, nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	pem, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return nil, responseError(resp, pem)
	}
	key, err := jwt.ParseRSAPublicKeyFromPEM(pem)
	if err != nil {
		return nil, fmt.Errorf("mcauth: parsing grant token key: %w", err)
	}
	s.key = key
	return key, nil
}

// VerifyOptions tune VerifyGrantToken.
type VerifyOptions struct {
	// MaxAge also rejects tokens whose iat is older than this (0 = no limit). Every token must carry an
	// exp claim and not have expired, whatever MaxAge is.
	MaxAge time.Duration
	// Audience is your application ID. When set, it must be among the token's aud values, so a token issued to
	// another application is rejected. Set it whenever tokens reach you through a browser.
	Audience string
	// Leeway allows for clock skew (default 1 minute).
	Leeway time.Duration
	// Now overrides the clock (tests).
	Now func() time.Time
}

// VerifyGrantToken checks a grant token's RS512 signature, issuer and age, and returns its claims.
func VerifyGrantToken(ctx context.Context, keys *KeySource, token string, opts VerifyOptions) (*GrantClaims, error) {
	claims, err := verifyWithKey(ctx, keys, token, opts)
	if err != nil && errors.Is(err, jwt.ErrTokenSignatureInvalid) && keys.refresh(ctx) {
		claims, err = verifyWithKey(ctx, keys, token, opts)
	}
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidToken, err)
	}
	return claims, nil
}

func verifyWithKey(ctx context.Context, keys *KeySource, token string, opts VerifyOptions) (*GrantClaims, error) {
	key, err := keys.Key(ctx)
	if err != nil {
		return nil, err
	}
	leeway := opts.Leeway
	if leeway == 0 {
		leeway = time.Minute
	}
	now := time.Now
	if opts.Now != nil {
		now = opts.Now
	}
	parserOpts := []jwt.ParserOption{
		jwt.WithValidMethods([]string{jwt.SigningMethodRS512.Alg()}),
		jwt.WithIssuer(Issuer),
		jwt.WithIssuedAt(),
		jwt.WithExpirationRequired(),
		jwt.WithLeeway(leeway),
		jwt.WithTimeFunc(now),
	}
	mapClaims := jwt.MapClaims{}
	if _, err := jwt.ParseWithClaims(token, mapClaims, func(*jwt.Token) (any, error) { return key, nil }, parserOpts...); err != nil {
		return nil, err
	}

	claims := &GrantClaims{Accounts: map[Platform]GrantAccount{}}
	switch sub := mapClaims["sub"].(type) {
	case string:
		claims.Subject = sub
	case float64:
		claims.Subject = fmt.Sprintf("%.0f", sub)
	default:
		return nil, errors.New("token has no subject")
	}
	iat, err := mapClaims.GetIssuedAt()
	if err != nil || iat == nil {
		return nil, errors.New("token has no iat")
	}
	claims.IssuedAt = iat.Time
	if exp, _ := mapClaims.GetExpirationTime(); exp != nil {
		claims.ExpiresAt = exp.Time
	}
	if aud, _ := mapClaims.GetAudience(); aud != nil {
		claims.Audience = aud
	}
	if opts.MaxAge > 0 && now().Sub(claims.IssuedAt) > opts.MaxAge+leeway {
		return nil, errors.New("token is too old")
	}
	if opts.Audience != "" {
		found := false
		for _, a := range claims.Audience {
			found = found || a == opts.Audience
		}
		if !found {
			return nil, errors.New("token was issued for a different application")
		}
	}
	for _, p := range Platforms {
		obj, ok := mapClaims[string(p)].(map[string]any)
		if !ok {
			continue
		}
		id, _ := obj["identifier"].(string)
		if id == "" {
			continue
		}
		name, _ := obj["name"].(string)
		avatar, _ := obj["avatar"].(string)
		claims.Accounts[p] = GrantAccount{Identifier: id, Name: name, Avatar: avatar}
	}
	return claims, nil
}
