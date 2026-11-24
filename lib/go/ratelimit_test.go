package mcauth

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

func limited(w http.ResponseWriter, seconds int) {
	w.Header().Set("Retry-After", fmt.Sprint(seconds))
	reply(w, http.StatusTooManyRequests, `{"error":"Rate limit exceeded","status":429}`)
}

func TestRateLimitWaitedOut(t *testing.T) {
	var calls atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			limited(w, 1)
			return
		}
		reply(w, 200, `{"result":true}`)
	})
	started := time.Now()
	ok, err := c.Following(context.Background(), notch, Twitch)
	if err != nil || !ok {
		t.Fatalf("got %v, %v", ok, err)
	}
	if calls.Load() != 2 {
		t.Fatalf("want 2 calls, got %d", calls.Load())
	}
	if time.Since(started) < 900*time.Millisecond {
		t.Fatal("retried before Retry-After")
	}
}

func TestRateLimitTooLongHoldsBackCalls(t *testing.T) {
	var calls atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		limited(w, 60)
	})
	ctx := context.Background()

	_, err := c.Following(ctx, notch, Twitch)
	if !IsRateLimited(err) || RetryAfter(err) != time.Minute || !IsTemporary(err) {
		t.Fatalf("want a 60s rate limit, got %v (retry after %s)", err, RetryAfter(err))
	}
	// not sent at all while the limit lasts
	_, err = c.Lookup(ctx, Minecraft, notch)
	if !IsRateLimited(err) || RetryAfter(err) <= 0 || RetryAfter(err) > time.Minute {
		t.Fatalf("want a held back call, got %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("want 1 call, got %d", calls.Load())
	}
}

func TestRateLimitNegativeMaxWaitNeverWaits(t *testing.T) {
	var calls atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		limited(w, 1)
	})
	c.MaxRateLimitWait = -1
	if _, err := c.Following(context.Background(), notch, Twitch); !IsRateLimited(err) {
		t.Fatalf("want rate limited, got %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("want 1 call, got %d", calls.Load())
	}
}

func TestRateLimitWaitHonorsContext(t *testing.T) {
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) { limited(w, 5) })
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err := c.Following(ctx, notch, Twitch)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("want deadline exceeded, got %v", err)
	}
}

func TestTooManyWithoutRetryAfterIsNotRateLimit(t *testing.T) {
	var calls atomic.Int32
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		reply(w, http.StatusTooManyRequests, `{"error":"Too many unanswered access requests","status":429}`)
	})
	_, err := c.CreateGrantRequest(context.Background(), nil, true)
	if IsRateLimited(err) || !IsTemporary(err) {
		t.Fatalf("want a plain temporary 429, got %v", err)
	}
	if calls.Load() != 1 {
		t.Fatalf("want 1 call, got %d", calls.Load())
	}
}

func TestGrantRequestLimitDoesNotHoldBackOtherCalls(t *testing.T) {
	var methods []string
	c := newClient(t, func(w http.ResponseWriter, r *http.Request) {
		methods = append(methods, r.Method)
		if r.Method == http.MethodPost {
			limited(w, 60)
			return
		}
		reply(w, 200, `{"result":true}`)
	})
	ctx := context.Background()
	for range 2 {
		if _, err := c.CreateGrantRequest(ctx, nil, true); !IsRateLimited(err) {
			t.Fatalf("want rate limited, got %v", err)
		}
	}
	if ok, err := c.Following(ctx, notch, Twitch); err != nil || !ok {
		t.Fatalf("got %v, %v", ok, err)
	}
	if fmt.Sprint(methods) != "[POST GET]" {
		t.Fatalf("want [POST GET], got %v", methods)
	}
}

func TestParseRetryAfter(t *testing.T) {
	now := time.Date(2015, 10, 21, 7, 28, 0, 0, time.UTC)
	for value, want := range map[string]time.Duration{
		" 3 ":                           3 * time.Second,
		"0":                             0,
		"Wed, 21 Oct 2015 07:28:30 GMT": 30 * time.Second,
		"Wed, 21 Oct 2015 07:27:00 GMT": 0,
	} {
		if got, ok := parseRetryAfter(value, now); !ok || got != want {
			t.Errorf("%q: got %s, %v; want %s", value, got, ok, want)
		}
	}
	for _, value := range []string{"", "-1", "soon"} {
		if _, ok := parseRetryAfter(value, now); ok {
			t.Errorf("%q should not parse", value)
		}
	}
}
