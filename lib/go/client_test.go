package mcauth

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAuthorizationHeader(t *testing.T) {
	for name, tc := range map[string]struct {
		creds Credentials
		want  string
	}{
		"anonymous":          {nil, ""},
		"empty server token": {ServerToken(""), ""},
		"server token":       {ServerToken("abc"), "Bearer abc"},
		// base64("app:s3cr:et") - the secret may itself contain a colon
		"application": {ApplicationCredentials{ID: "app", Secret: "s3cr:et"}, "Basic YXBwOnMzY3I6ZXQ="},
	} {
		t.Run(name, func(t *testing.T) {
			var got []string
			c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
				got = r.Header.Values("Authorization")
				reply(w, 200, `{"accounts":{}}`)
			})
			c.Credentials = tc.creds
			if _, err := c.Lookup(context.Background(), Minecraft, notch); err != nil {
				t.Fatal(err)
			}
			if tc.want == "" && len(got) != 0 {
				t.Fatalf("want no Authorization header, got %q", got)
			}
			if tc.want != "" && (len(got) != 1 || got[0] != tc.want) {
				t.Fatalf("want %q, got %q", tc.want, got)
			}
		})
	}
}

func TestUserAgentRequired(t *testing.T) {
	var hits atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { hits.Add(1) })
	c.UserAgent = ""
	if _, err := c.Following(context.Background(), notch, Twitch); err == nil || !strings.Contains(err.Error(), "UserAgent") {
		t.Fatalf("want UserAgent error, got %v", err)
	}
	if hits.Load() != 0 {
		t.Fatal("request sent without a User-Agent")
	}
}

func TestRequestHeaders(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Accept") != "application/json" {
			t.Errorf("Accept = %q", r.Header.Get("Accept"))
		}
		wantType := ""
		if r.Method == http.MethodPost {
			wantType = "application/json"
		}
		if got := r.Header.Get("Content-Type"); got != wantType {
			t.Errorf("%s Content-Type = %q, want %q", r.Method, got, wantType)
		}
		reply(w, 200, `{"result":false,"id":"r"}`)
	})
	ctx := context.Background()
	if _, err := c.Following(ctx, notch, Twitch); err != nil {
		t.Fatal(err)
	}
	if _, err := c.CreateGrantRequest(ctx, nil, true); err != nil {
		t.Fatal(err)
	}
}

func TestDefaults(t *testing.T) {
	c := &Client{}
	if c.baseURL() != DefaultBaseURL {
		t.Errorf("baseURL = %q", c.baseURL())
	}
	if c.httpClient() != defaultHTTPClient || defaultHTTPClient.Timeout == 0 {
		t.Error("default HTTP client should be used and have a timeout")
	}
}

type countingTransport struct{ n atomic.Int32 }

func (c *countingTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	c.n.Add(1)
	return http.DefaultTransport.RoundTrip(r)
}

func TestBaseURLTrailingSlashAndCustomHTTPClient(t *testing.T) {
	var path string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path = r.URL.Path
		reply(w, 200, `{"result":true}`)
	}))
	t.Cleanup(srv.Close)
	transport := &countingTransport{}
	c := &Client{BaseURL: srv.URL + "//", UserAgent: "Test/1.0", HTTPClient: &http.Client{Transport: transport}}
	if ok, err := c.Following(context.Background(), notch, Google); !ok || err != nil {
		t.Fatalf("want true, nil; got %v, %v", ok, err)
	}
	if path != "/api/v2/following" {
		t.Errorf("path = %q", path)
	}
	if transport.n.Load() != 1 {
		t.Error("custom HTTP client not used")
	}
}

func TestLookupEscapesIdentifier(t *testing.T) {
	var escaped string
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		escaped = r.URL.EscapedPath()
		reply(w, 200, `{}`)
	})
	res, err := c.Lookup(context.Background(), Patreon, "a b/c?d")
	if err != nil {
		t.Fatal(err)
	}
	if escaped != "/api/v2/lookup/patreon/a%20b%2Fc%3Fd" {
		t.Errorf("path = %q", escaped)
	}
	if res.Accounts == nil || len(res.Accounts) != 0 {
		t.Errorf("missing accounts should become an empty map, got %#v", res.Accounts)
	}
}

func TestLookupWithoutInclude(t *testing.T) {
	var raw string
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		raw = r.URL.RawQuery
		reply(w, 200, `{"accounts":{}}`)
	})
	if _, err := c.Lookup(context.Background(), Twitch, "notch"); err != nil {
		t.Fatal(err)
	}
	if raw != "" {
		t.Errorf("want no query, got %q", raw)
	}
}

func TestLookupServerError(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 500, `{"error":"boom"}`) })
	res, err := c.Lookup(context.Background(), Minecraft, notch)
	if res != nil || !IsTemporary(err) || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("want temporary boom error, got %v, %v", res, err)
	}
}

func TestSubscribedOmitsEmptyData(t *testing.T) {
	var query map[string][]string
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.Query()
		reply(w, 200, `{"result":false}`)
	})
	ok, err := c.Subscribed(context.Background(), notch, Google, "")
	if ok || err != nil {
		t.Fatalf("want false, nil; got %v, %v", ok, err)
	}
	if _, present := query["data"]; present {
		t.Errorf("empty data should be omitted: %v", query)
	}
}

func TestInvalidJSONResponse(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 200, `<html>`) })
	_, err := c.Following(context.Background(), notch, Twitch)
	var apiErr *APIError
	if !errors.As(err, &apiErr) || apiErr.Status != 200 || !strings.Contains(apiErr.Message, "invalid JSON") {
		t.Fatalf("want invalid JSON APIError, got %v", err)
	}
	var syntax *json.SyntaxError
	if !errors.As(err, &syntax) {
		t.Errorf("the JSON error should be unwrappable, got %v", err)
	}
}

func TestMembershipWithoutResult(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 200, `{}`) })
	ok, err := c.DiscordPresent(context.Background(), notch, "1")
	if ok || !IsTemporary(err) {
		t.Fatalf("want false + temporary error, got %v, %v", ok, err)
	}
}

func TestMembershipFalse(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 200, `{"result":false}`) })
	if ok, err := c.Following(context.Background(), notch, Twitch); ok || err != nil {
		t.Fatalf("want false, nil; got %v, %v", ok, err)
	}
}

func TestCreateGrantRequestBody(t *testing.T) {
	var body struct {
		Scopes  []string `json:"scopes"`
		Persist bool     `json:"persist"`
	}
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v2/grant-requests" {
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		reply(w, 201, `{"id":"r1","scopes":["minecraft","discord","twitch"],"persist":true,"interval":5}`)
	})
	req, err := c.CreateGrantRequest(context.Background(), []Platform{Discord, Twitch}, true)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(body.Scopes, ",") != "discord,twitch" || !body.Persist {
		t.Errorf("unexpected body %+v", body)
	}
	if !req.Persist || len(req.Scopes) != 3 || req.Interval != 5 {
		t.Errorf("unexpected response %+v", req)
	}
}

func TestCreateGrantRequestNilScopesSendsEmptyArray(t *testing.T) {
	var raw map[string]json.RawMessage
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&raw)
		reply(w, 201, `{"id":"r1"}`)
	})
	if _, err := c.CreateGrantRequest(context.Background(), nil, false); err != nil {
		t.Fatal(err)
	}
	if string(raw["scopes"]) != "[]" {
		t.Errorf("scopes = %s, want []", raw["scopes"])
	}
}

func TestGrantRequestErrors(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			reply(w, 401, `{"error":"bad credentials"}`)
			return
		}
		if r.URL.EscapedPath() != "/api/v2/grant-requests/a%2Fb" {
			t.Errorf("path = %q", r.URL.EscapedPath())
		}
		reply(w, 404, `{"error":"unknown request"}`)
	})
	if req, err := c.CreateGrantRequest(context.Background(), nil, false); req != nil || !IsConfig(err) {
		t.Errorf("create: want config error, got %+v, %v", req, err)
	}
	if st, err := c.GetGrantRequest(context.Background(), "a/b"); st != nil || !IsNotFound(err) {
		t.Errorf("get: want not found, got %+v, %v", st, err)
	}
}

func TestGrantRequestStatuses(t *testing.T) {
	for _, status := range []GrantStatus{GrantPending, GrantDenied, GrantExpired} {
		c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
			reply(w, 200, `{"status":"`+string(status)+`","expiresAt":99}`)
		})
		st, err := c.GetGrantRequest(context.Background(), "r1")
		if err != nil || st.Status != status || st.Token != "" || st.ExpiresAt != 99 {
			t.Errorf("%s: got %+v, %v", status, st, err)
		}
	}
}

func TestContextCanceled(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { reply(w, 200, `{"result":true}`) })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	ok, err := c.Following(ctx, notch, Twitch)
	if ok || !IsTemporary(err) || !errors.Is(err, context.Canceled) {
		t.Fatalf("want temporary context.Canceled, got %v, %v", ok, err)
	}
}

func TestParsePlatformAll(t *testing.T) {
	for in, want := range map[string]Platform{
		"minecraft": Minecraft, "Discord": Discord, " TWITCH ": Twitch, "patreon": Patreon, "google": Google, "youtube": Google,
	} {
		if got, ok := ParsePlatform(in); !ok || got != want {
			t.Errorf("ParsePlatform(%q) = %q, %v", in, got, ok)
		}
	}
	if _, ok := ParsePlatform(""); ok {
		t.Error("empty platform accepted")
	}
}
