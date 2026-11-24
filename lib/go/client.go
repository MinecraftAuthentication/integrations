// Package mcauth is a client for the MinecraftAuth.me v2 API (https://minecraftauth.me/developers).
//
// Players link their Minecraft account to Discord, Twitch, Patreon and YouTube on MinecraftAuth.me. This
// package looks those links up, asks whether a player follows/subscribes to/supports a creator, runs access
// (grant) requests and verifies the signed grant tokens MinecraftAuth.me issues.
package mcauth

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// DefaultBaseURL is the MinecraftAuth.me API host. Every path already includes /api/v2.
const DefaultBaseURL = "https://minecraftauth.me"

// Platform is a platform an identity can link an account on.
type Platform string

const (
	Minecraft Platform = "minecraft"
	Discord   Platform = "discord"
	Twitch    Platform = "twitch"
	Patreon   Platform = "patreon"
	// Google is YouTube; the v2 API calls it "google".
	Google Platform = "google"
)

// Platforms lists every known platform.
var Platforms = []Platform{Minecraft, Discord, Twitch, Patreon, Google}

// ParsePlatform parses a platform id, ignoring case. "youtube" is accepted as an alias of Google.
func ParsePlatform(s string) (Platform, bool) {
	s = strings.ToLower(strings.TrimSpace(s))
	if s == "youtube" {
		return Google, true
	}
	for _, p := range Platforms {
		if string(p) == s {
			return p, true
		}
	}
	return "", false
}

// Credentials authenticates API requests.
type Credentials interface {
	// Authorization returns the Authorization header value, or "" to send none.
	Authorization() string
}

// ServerToken is a creator's server token, sent as Bearer.
// Membership checks answer whether a player supports the token's owner.
//
// Deprecated: Server tokens were retired: no new ones are issued, and existing ones stop working at the end of the
// API's v1 grace period. Create an application at https://minecraftauth.me/developers and use
// ApplicationCredentials.
type ServerToken string

func (t ServerToken) Authorization() string {
	if t == "" {
		return ""
	}
	return "Bearer " + string(t)
}

// ApplicationCredentials are an application's ID and secret from https://minecraftauth.me/developers. Membership
// checks made with any of a user's applications answer whether a player supports that user, the application's
// owner. They're also required for access (grant) requests. Keep the secret on your server.
type ApplicationCredentials struct {
	ID     string
	Secret string
}

func (a ApplicationCredentials) Authorization() string {
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(a.ID+":"+a.Secret))
}

// Client talks to the MinecraftAuth.me v2 API. The zero value is not usable; set UserAgent at least.
type Client struct {
	// BaseURL defaults to DefaultBaseURL.
	BaseURL string
	// UserAgent is required by the API; name your integration and version, e.g. "MyBot/1.2".
	UserAgent string
	// Credentials may be nil for anonymous lookups.
	Credentials Credentials
	// HTTPClient defaults to a client with a 10 second timeout.
	HTTPClient *http.Client
}

var defaultHTTPClient = &http.Client{Timeout: 10 * time.Second}

// LinkedAccount is an account linked to an identity: either Identifier is set, or Hint is true
// (the player shows the platform is linked, but not which account).
type LinkedAccount struct {
	Identifier *string `json:"identifier,omitempty"`
	Hint       bool    `json:"hint,omitempty"`
}

// LookupResult maps platforms to the visible linked accounts. A missing platform means unlinked, hidden,
// or not requested; it never proves the player has no account there.
type LookupResult struct {
	Accounts map[Platform]LinkedAccount `json:"accounts"`
}

// Lookup returns the accounts linked to the identity owning identifier on platform, or (nil, nil) if
// there is no such identity (or it's hidden from you). include limits the result to those platforms.
func (c *Client) Lookup(ctx context.Context, platform Platform, identifier string, include ...Platform) (*LookupResult, error) {
	q := url.Values{}
	if len(include) > 0 {
		ids := make([]string, len(include))
		for i, p := range include {
			ids[i] = string(p)
		}
		q.Set("include", strings.Join(ids, ","))
	}
	var out LookupResult
	err := c.do(ctx, http.MethodGet, "/api/v2/lookup/"+string(platform)+"/"+url.PathEscape(identifier), q, nil, &out)
	if IsNotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if out.Accounts == nil {
		out.Accounts = map[Platform]LinkedAccount{}
	}
	return &out, nil
}

// Following reports whether the player follows you: Twitch = follows your channel, Google = subscribed to
// your YouTube channel. A player who isn't linked (HTTP 404) is reported as false.
func (c *Client) Following(ctx context.Context, minecraft string, platform Platform) (bool, error) {
	return c.membership(ctx, "/api/v2/following", url.Values{"minecraft": {minecraft}, "platform": {string(platform)}})
}

// Subscribed reports whether the player subscribes to or supports you on platform. data narrows the check:
// a Discord role ID (required for Discord; use DiscordRole for a role name), a minimum Twitch tier "1"-"3", or a
// Patreon tier title.
func (c *Client) Subscribed(ctx context.Context, minecraft string, platform Platform, data string) (bool, error) {
	q := url.Values{"minecraft": {minecraft}, "platform": {string(platform)}}
	if data != "" {
		q.Set("data", data)
	}
	return c.membership(ctx, "/api/v2/subscribed", q)
}

// DiscordRole reports whether the player's Discord account has role in a Discord server you moderate. role is a role
// ID, or a role name matched ignoring case. A name needs serverID: anyone can create a role with the same name in a
// server of their own, so the API only looks names up in the server you name. serverID may be empty for a role ID.
func (c *Client) DiscordRole(ctx context.Context, minecraft string, role string, serverID string) (bool, error) {
	q := url.Values{"minecraft": {minecraft}, "platform": {string(Discord)}, "data": {role}}
	if serverID != "" {
		q.Set("server", serverID)
	}
	return c.membership(ctx, "/api/v2/subscribed", q)
}

// DiscordPresent reports whether the player's Discord account is a member of the Discord server serverID.
func (c *Client) DiscordPresent(ctx context.Context, minecraft string, serverID string) (bool, error) {
	return c.membership(ctx, "/api/v2/discord/present", url.Values{"minecraft": {minecraft}, "server": {serverID}})
}

func (c *Client) membership(ctx context.Context, path string, q url.Values) (bool, error) {
	var out struct {
		Result *bool `json:"result"`
	}
	err := c.do(ctx, http.MethodGet, path, q, nil, &out)
	if IsNotFound(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if out.Result == nil {
		return false, &APIError{Status: http.StatusBadGateway, Message: "membership response without a result"}
	}
	return *out.Result, nil
}

// GrantRequest is a newly created access request. Show the player URL, or Code together with VerificationURL.
type GrantRequest struct {
	ID              string   `json:"id"`
	Code            string   `json:"code"`
	URL             string   `json:"url"`
	VerificationURL string   `json:"verificationUrl"`
	Scopes          []string `json:"scopes"`
	Persist         bool     `json:"persist"`
	// ExpiresAt is in epoch milliseconds.
	ExpiresAt int64 `json:"expiresAt"`
	// Interval is the number of seconds to wait between polls.
	Interval int `json:"interval"`
}

// GrantStatus is the state of an access request.
type GrantStatus string

const (
	GrantPending  GrantStatus = "pending"
	GrantAccepted GrantStatus = "accepted"
	GrantDenied   GrantStatus = "denied"
	GrantExpired  GrantStatus = "expired"
)

// GrantRequestStatus is the result of polling an access request. Token is set once accepted.
type GrantRequestStatus struct {
	Status    GrantStatus `json:"status"`
	ExpiresAt int64       `json:"expiresAt"`
	Token     string      `json:"token,omitempty"`
}

// CreateGrantRequest starts an access request (requires ApplicationCredentials). minecraft is always in scope.
func (c *Client) CreateGrantRequest(ctx context.Context, scopes []Platform, persist bool) (*GrantRequest, error) {
	body := struct {
		Scopes  []Platform `json:"scopes"`
		Persist bool       `json:"persist"`
	}{Scopes: scopes, Persist: persist}
	if body.Scopes == nil {
		body.Scopes = []Platform{}
	}
	var out GrantRequest
	if err := c.do(ctx, http.MethodPost, "/api/v2/grant-requests", nil, body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// GetGrantRequest polls an access request started by this application.
func (c *Client) GetGrantRequest(ctx context.Context, id string) (*GrantRequestStatus, error) {
	var out GrantRequestStatus
	if err := c.do(ctx, http.MethodGet, "/api/v2/grant-requests/"+url.PathEscape(id), nil, nil, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func (c *Client) baseURL() string {
	if c.BaseURL == "" {
		return DefaultBaseURL
	}
	return strings.TrimRight(c.BaseURL, "/")
}

func (c *Client) httpClient() *http.Client {
	if c.HTTPClient == nil {
		return defaultHTTPClient
	}
	return c.HTTPClient
}

func (c *Client) do(ctx context.Context, method, path string, query url.Values, body, out any) error {
	resp, err := c.raw(ctx, method, path, query, body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return &APIError{Message: "reading response: " + err.Error(), Err: err}
	}
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return newAPIError(resp.StatusCode, data)
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return &APIError{Status: resp.StatusCode, Message: "invalid JSON response: " + err.Error(), Err: err}
	}
	return nil
}

func (c *Client) raw(ctx context.Context, method, path string, query url.Values, body any) (*http.Response, error) {
	if c.UserAgent == "" {
		return nil, fmt.Errorf("mcauth: Client.UserAgent is required by the MinecraftAuth.me API")
	}
	u := c.baseURL() + path
	if len(query) > 0 {
		u += "?" + query.Encode()
	}
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, u, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", c.UserAgent)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.Credentials != nil {
		if auth := c.Credentials.Authorization(); auth != "" {
			req.Header.Set("Authorization", auth)
		}
	}
	resp, err := c.httpClient().Do(req)
	if err != nil {
		return nil, &APIError{Message: fmt.Sprintf("%s %s: %v", method, path, err), Err: err}
	}
	return resp, nil
}
