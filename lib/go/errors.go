package mcauth

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
)

// APIError is returned for every failed request. Status is 0 when no response was received.
type APIError struct {
	Status  int
	Message string
	Err     error
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

// IsTemporary reports a failure worth retrying later: 409 (bot not in the Discord server), 429, 5xx,
// or no response at all.
func IsTemporary(err error) bool {
	e, ok := asAPIError(err)
	if !ok {
		return false
	}
	return e.Status == 0 || e.Status == http.StatusConflict || e.Status == http.StatusTooManyRequests || e.Status >= 500
}
