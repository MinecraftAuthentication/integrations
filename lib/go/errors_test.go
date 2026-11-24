package mcauth

import (
	"errors"
	"fmt"
	"testing"
)

func TestNewAPIErrorMessage(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		want   string
	}{
		{503, `{"error":"Discord is down","status":503}`, "mcauth: HTTP 503: Discord is down"},
		{503, ``, "mcauth: HTTP 503: Service Unavailable"},
		{429, `not json`, "mcauth: HTTP 429: Too Many Requests"},
		{400, `{"error":""}`, "mcauth: HTTP 400: Bad Request"},
	} {
		if got := newAPIError(tc.status, []byte(tc.body)).Error(); got != tc.want {
			t.Errorf("%d %q: got %q, want %q", tc.status, tc.body, got, tc.want)
		}
	}
}

func TestAPIErrorWithoutStatus(t *testing.T) {
	cause := errors.New("connection refused")
	err := &APIError{Message: "GET /x: connection refused", Err: cause}
	if err.Error() != "mcauth: GET /x: connection refused" {
		t.Errorf("Error() = %q", err.Error())
	}
	if !errors.Is(err, cause) {
		t.Error("Unwrap should expose the cause")
	}
}

func TestErrorClassification(t *testing.T) {
	for _, tc := range []struct {
		status                    int
		notFound, config, retries bool
	}{
		{0, false, false, true},
		{400, false, true, false},
		{401, false, true, false},
		{403, false, true, false},
		{404, true, false, false},
		{409, false, false, true},
		{422, false, false, false},
		{429, false, false, true},
		{500, false, false, true},
		{503, false, false, true},
	} {
		// classification must see through wrapping
		err := fmt.Errorf("checking player: %w", &APIError{Status: tc.status})
		if IsNotFound(err) != tc.notFound || IsConfig(err) != tc.config || IsTemporary(err) != tc.retries {
			t.Errorf("status %d: notFound=%v config=%v temporary=%v", tc.status, IsNotFound(err), IsConfig(err), IsTemporary(err))
		}
	}
	for _, err := range []error{nil, errors.New("plain")} {
		if IsNotFound(err) || IsConfig(err) || IsTemporary(err) {
			t.Errorf("%v should not be classified", err)
		}
	}
}
