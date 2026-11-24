package caddymcauth

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	mcauth "github.com/MinecraftAuthentication/integrations/lib/go/v2"
)

// LuckPerms points the `luckperms` rule at a LuckPerms REST API
// (https://luckperms.net/wiki/Standalone-and-REST-API).
type LuckPerms struct {
	// URL is the REST API's base URL, e.g. http://localhost:8080.
	URL string `json:"url"`
	// APIKey is one of the keys in LUCKPERMS_REST_AUTH_KEYS, sent as a bearer token. Leave it empty when
	// the REST API runs without auth.
	APIKey string `json:"api_key,omitempty"`
}

// luckPermsContext matches the REST API's context keys and values (^[a-z0-9]+$).
var luckPermsContext = regexp.MustCompile(`^[a-z0-9]+$`)

// parseLuckPermsRule turns a permission or group rule into the permission to check and its contexts
// (`key=value` pairs, e.g. server=survival). `group <name>` checks group.<name>, the permission LuckPerms
// gives members of that group (including through inherited groups).
func parseLuckPermsRule(rule Rule) (string, []luckPermsCtx, error) {
	values := rule.Values
	if len(values) == 0 || strings.TrimSpace(values[0]) == "" {
		return "", nil, fmt.Errorf("%s needs a value", rule.Type)
	}
	permission := values[0]
	if rule.Type == "group" {
		permission = "group." + strings.ToLower(permission)
	}
	var contexts []luckPermsCtx
	for _, v := range values[1:] {
		key, value, ok := strings.Cut(v, "=")
		if !ok || !luckPermsContext.MatchString(key) || !luckPermsContext.MatchString(value) {
			return "", nil, fmt.Errorf("luckperms context %q must be key=value, lowercase letters and digits only", v)
		}
		contexts = append(contexts, luckPermsCtx{Key: key, Value: value})
	}
	return permission, contexts, nil
}

type luckPermsCtx struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

type luckPermsClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func newLuckPermsClient(cfg *LuckPerms) *luckPermsClient {
	return &luckPermsClient{
		baseURL: strings.TrimRight(cfg.URL, "/"),
		apiKey:  cfg.APIKey,
		http:    &http.Client{Timeout: 10 * time.Second},
	}
}

// hasPermission asks LuckPerms whether the player has permission in the given contexts. Only an explicit
// "true" grants it; "false", "undefined" and players LuckPerms has never seen (404) don't.
func (c *luckPermsClient) hasPermission(ctx context.Context, uuid, permission string, contexts []luckPermsCtx) (bool, error) {
	id, ok := dashedUUID(uuid)
	if !ok {
		return false, fmt.Errorf("luckperms: %q is not a UUID", uuid)
	}
	body := map[string]any{"permission": permission}
	if len(contexts) > 0 {
		body["queryOptions"] = map[string]any{"contexts": contexts}
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return false, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.baseURL+"/user/"+url.PathEscape(id)+"/permission-check", bytes.NewReader(payload))
	if err != nil {
		return false, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", "MinecraftAuth-Caddy/"+Version)
	if c.apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+c.apiKey)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return false, fmt.Errorf("luckperms: %w", &mcauth.APIError{Message: err.Error(), Err: err})
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	switch {
	case resp.StatusCode == http.StatusNotFound:
		return false, nil // the player never joined a LuckPerms server
	case resp.StatusCode != http.StatusOK:
		return false, fmt.Errorf("luckperms: %w", &mcauth.APIError{Status: resp.StatusCode, Message: http.StatusText(resp.StatusCode)})
	}
	var result struct {
		Result json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(data, &result); err != nil {
		return false, fmt.Errorf("luckperms: unreadable permission check: %w", err)
	}
	// the REST API answers the strings "true", "false" or "undefined"; accept a JSON boolean too
	switch strings.Trim(string(result.Result), `"`) {
	case "true":
		return true, nil
	case "false", "undefined":
		return false, nil
	}
	return false, fmt.Errorf("luckperms: unexpected permission check result %s", result.Result)
}

// dashedUUID returns uuid in the 8-4-4-4-12 lowercase form LuckPerms requires.
func dashedUUID(uuid string) (string, bool) {
	s := strings.ToLower(strings.ReplaceAll(uuid, "-", ""))
	if len(s) != 32 {
		return "", false
	}
	for _, r := range s {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return "", false
		}
	}
	return s[:8] + "-" + s[8:12] + "-" + s[12:16] + "-" + s[16:20] + "-" + s[20:], true
}
