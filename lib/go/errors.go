package mcauth

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// APIError is returned for every failed request. Status is 0 when no response was received.
type APIError struct {
	Status  int
	Message string
	// RetryAfter is set when the API rate limits the client (429 with Retry-After): how long to wait before trying
	// again. See Client.MaxRateLimitWait.
	RetryAfter time.Duration
	Err        error
}

func (e *APIError) Error() string {
	if e.Status == 0 {
		return "mcauth: " + e.Message
	}
	return fmt.Sprintf("mcauth: HTTP %d: %s", e.Status, e.Message)
}

func (e *APIError) Unwrap() error { return e.Err }

func newAPIError(status int, body []byte) *APIError {
	var parsed struct {
		Error string `json:"error"`
	}
	message := http.StatusText(status)
	if json.Unmarshal(body, &parsed) == nil && parsed.Error != "" {
		message = parsed.Error
	}
	return &APIError{Status: status, Message: message}
}

func asAPIError(err error) (*APIError, bool) {
	var apiErr *APIError
	ok := errors.As(err, &apiErr)
	return apiErr, ok
}

// IsNotFound reports a 404: the resource doesn't exist, or the player's privacy settings hide it.
func IsNotFound(err error) bool {
	e, ok := asAPIError(err)
	return ok && e.Status == http.StatusNotFound
}

// IsConfig reports a 400/401/403: a problem with how the integration is set up. Retrying won't help.
func IsConfig(err error) bool {
	e, ok := asAPIError(err)
	return ok && (e.Status == http.StatusBadRequest || e.Status == http.StatusUnauthorized || e.Status == http.StatusForbidden)
}

// IsRateLimited reports a 429 with a Retry-After: the API is rate limiting the client, for longer than
// Client.MaxRateLimitWait (shorter limits are waited out). RetryAfter says how long to wait.
func IsRateLimited(err error) bool {
	e, ok := asAPIError(err)
	return ok && e.Status == http.StatusTooManyRequests && e.RetryAfter > 0
}

// RetryAfter returns how long a rate limited call asks to wait, or 0 if err isn't a rate limit.
func RetryAfter(err error) time.Duration {
	if !IsRateLimited(err) {
		return 0
	}
	e, _ := asAPIError(err)
	return e.RetryAfter
}

// parseRetryAfter reads a Retry-After header: delay-seconds or an HTTP date. ok is false if it's missing or malformed.
func parseRetryAfter(value string, now time.Time) (d time.Duration, ok bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	if seconds, err := strconv.ParseInt(value, 10, 64); err == nil {
		if seconds < 0 {
			return 0, false
		}
		return time.Duration(seconds) * time.Second, true
	}
	at, err := http.ParseTime(value)
	if err != nil {
		return 0, false
	}
	return max(at.Sub(now), 0), true
}

// IsTemporary reports a failure worth retrying later: 409 (bot not in the Discord server), 429, 5xx,
// or no response at all.
func IsTemporary(err error) bool {
	e, ok := asAPIError(err)
	if !ok {
		return false
	}
	return e.Status == 0 || e.Status == http.StatusConflict || e.Status == http.StatusTooManyRequests || e.Status >= 500
}
