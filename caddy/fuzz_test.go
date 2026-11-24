package caddymcauth

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/url"
	"strings"
	"testing"
	"unicode/utf8"
)

// Run longer with e.g. `go test -run '^$' -fuzz FuzzSafeReturn -fuzztime 1m` in caddy/.
// Plain `go test` runs only the seed corpus.

// FuzzSafeReturn checks that whatever lands in the state cookie, the post-login redirect stays on this site.
func FuzzSafeReturn(f *testing.F) {
	for _, seed := range []string{
		"/", "/a?b=c", "//evil.com", "/\\evil.com", "https://evil.com", "", "evil.com", "/%2F/evil.com",
		"/\t/evil.com", "/\n/evil.com", "/\r\n/evil.com", "\t//evil.com", " /x", "/.//evil.com", "/\\/evil.com",
		"javascript:alert(1)", "/@evil.com", "/?next=//evil.com",
	} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, in string) {
		out := safeReturn(in)
		if out != "/" && out != in {
			t.Fatalf("safeReturn(%q) = %q: must be the input or /", in, out)
		}
		// browsers drop ASCII tab and newlines anywhere in a URL and trim leading/trailing C0 controls and spaces
		// (WHATWG URL parsing), so judge the redirect the way the browser will read it
		seen := strings.Map(func(r rune) rune {
			if r == '\t' || r == '\n' || r == '\r' {
				return -1
			}
			return r
		}, out)
		seen = strings.TrimLeft(seen, "\x00\x01\x02\x03\x04\x05\x06\x07\x08\x0b\x0c\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f ")
		if !strings.HasPrefix(seen, "/") {
			t.Fatalf("safeReturn(%q) = %q: a browser reads %q, which isn't a path", in, out, seen)
		}
		// browsers treat \ like / in special-scheme URLs, so /\ and \/ start a protocol-relative URL as well
		if len(seen) > 1 && (seen[1] == '/' || seen[1] == '\\') {
			t.Fatalf("safeReturn(%q) = %q: a browser reads %q, which leaves the site", in, out, seen)
		}
		if u, err := url.Parse(seen); err == nil && (u.Scheme != "" || u.Host != "") {
			t.Fatalf("safeReturn(%q) = %q: parses with scheme %q host %q", in, out, u.Scheme, u.Host)
		}
	})
}

// FuzzCookieOpen feeds arbitrary cookie values to the decoder: it must never panic, and nothing but a value
// this codec sealed for this cookie name may decode.
func FuzzCookieOpen(f *testing.F) {
	codec, err := newCookieCodec([]byte("0123456789abcdef0123456789abcdef"))
	if err != nil {
		f.Fatal(err)
	}
	other, err := newCookieCodec([]byte("another secret, another key....."))
	if err != nil {
		f.Fatal(err)
	}
	// sealed with a fixed nonce: the fuzz workers are separate processes, and each must agree on this value
	plain, _ := json.Marshal(session{Subject: "1", UUID: notch, Name: "Notch", Expires: 1})
	nonce := make([]byte, codec.aead.NonceSize())
	validRaw := codec.aead.Seal(nonce, nonce, plain, []byte("mcauth_session"))
	valid := base64.RawURLEncoding.EncodeToString(validRaw)
	foreign, err := other.seal("mcauth_session", session{Subject: "1", UUID: notch, Name: "Notch", Expires: 1})
	if err != nil {
		f.Fatal(err)
	}
	for _, seed := range []string{"", "a", "====", "!!!", valid, foreign, valid[:len(valid)-1], valid + "A",
		base64.RawURLEncoding.EncodeToString(make([]byte, 11)), base64.RawURLEncoding.EncodeToString(make([]byte, 12)),
		base64.RawURLEncoding.EncodeToString(make([]byte, 64))} {
		f.Add(seed)
	}
	f.Fuzz(func(t *testing.T, value string) {
		var s session
		err := codec.open("mcauth_session", value, &s)
		if err == nil {
			// the decoder ignores unused trailing bits, so compare the bytes rather than the text
			raw, _ := base64.RawURLEncoding.DecodeString(value)
			if !bytes.Equal(raw, validRaw) {
				t.Fatalf("open accepted a value this codec never sealed: %q -> %+v", value, s)
			}
		}
		// a value sealed for one cookie never opens as another
		var st loginState
		if codec.open("mcauth_state", value, &st) == nil {
			t.Fatalf("session cookie value opened as the state cookie: %q", value)
		}
	})
}

// A sealed value round-trips, and any single changed byte breaks it.
func FuzzCookieRoundTrip(f *testing.F) {
	codec, err := newCookieCodec([]byte("0123456789abcdef0123456789abcdef"))
	if err != nil {
		f.Fatal(err)
	}
	f.Add("Notch", "/a?b=c", int64(1), 0)
	f.Add("", "", int64(-1), 5)
	f.Add("ü\x00\"\\", "//evil.com", int64(1<<62), 1000)
	f.Fuzz(func(t *testing.T, name, ret string, exp int64, flip int) {
		in := loginState{State: name, Return: ret, Expires: exp}
		value, err := codec.seal("mcauth_state", in)
		if err != nil {
			t.Fatal(err)
		}
		var out loginState
		if err := codec.open("mcauth_state", value, &out); err != nil {
			t.Fatalf("round trip failed: %v", err)
		}
		// encoding/json replaces invalid UTF-8 with U+FFFD, so only valid strings come back unchanged
		same := out.Expires == in.Expires
		if utf8.ValidString(name) && utf8.ValidString(ret) {
			same = same && out == in
		}
		if !same {
			t.Fatalf("round trip changed the value: %+v -> %+v", in, out)
		}
		raw, _ := base64.RawURLEncoding.DecodeString(value)
		i := int(uint(flip) % uint(len(raw)))
		raw[i] ^= 0x01
		if codec.open("mcauth_state", base64.RawURLEncoding.EncodeToString(raw), &out) == nil {
			t.Fatalf("a value with byte %d flipped still opened", i)
		}
	})
}
