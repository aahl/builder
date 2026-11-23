// Modified or added for XiaoSeek LX04 Web Setup. See NOTICE.
// SPDX-License-Identifier: Apache-2.0
package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"xiaobot"
)

func TestValidDNS(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"", true},
		{"192.168.1.1:53", true},
		{"192.168.1.1", false},
		{"example.com:53", false},
		{"192.168.1.1:0", false},
		{"192.168.1.1:70000", false},
	}
	for _, c := range cases {
		if got := validDNS(c.in); got != c.want {
			t.Errorf("validDNS(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestSubtleEqual(t *testing.T) {
	if !subtleEqual("abc123", "abc123") {
		t.Fatal("equal tokens must match")
	}
	if subtleEqual("abc123", "abc124") || subtleEqual("abc", "abcd") {
		t.Fatal("different tokens must not match")
	}
}

func TestBuildConfigDefaults(t *testing.T) {
	cfg := buildConfig(setupInput{Account: " u ", Password: "p", OpenAIKey: "k"}, nil)
	if cfg.Hardware != "LX04" {
		t.Fatalf("hardware = %q", cfg.Hardware)
	}
	if cfg.Account != "u" || cfg.Password != "p" {
		t.Fatalf("credentials not preserved: %+v", cfg)
	}
	if cfg.Bot != "deepseek-flash" || cfg.OpenAIBackend != "https://api.deepseek.com" {
		t.Fatalf("bad defaults: %+v", cfg)
	}
	if cfg.ContextMode != "session" || cfg.ContextTurns != 20 || cfg.ContextTTLSec != 0 || cfg.ThinkingMode != "off" {
		t.Fatalf("bad defaults: %+v", cfg)
	}
}

func TestBuildConfigPreservesAdvancedFields(t *testing.T) {
	existing := &xiaobot.Config{
		GPTOptions: map[string]interface{}{"top_p": 0.9},
		MusicPath:  "/music",
	}
	cfg := buildConfig(setupInput{Account: "a", Password: "p", ContextMode: "off", ThinkingMode: "max"}, existing)
	if cfg.GPTOptions["top_p"] != 0.9 || cfg.MusicPath != "/music" {
		t.Fatalf("advanced fields lost: %+v", cfg)
	}
	if cfg.ContextMode != "off" || cfg.ThinkingMode != "max" {
		t.Fatalf("form fields not applied: %+v", cfg)
	}
}

func TestReadStatusFromBootstrapFile(t *testing.T) {
	dir := t.TempDir()
	oldDir := appDir
	appDir = dir
	defer func() { appDir = oldDir }()

	data, _ := json.Marshal(map[string]interface{}{
		"status":              "verification_required",
		"verification_method": "Phone",
		"exact_matches":       2,
		"did_configured":      false,
	})
	if err := os.WriteFile(filepath.Join(dir, statusName), data, 0600); err != nil {
		t.Fatal(err)
	}
	got := readStatus()
	if got.Status != "verification_required" || got.VerificationMsg != "Phone" {
		t.Fatalf("bad status: %+v", got)
	}
	if got.ExactMatches == nil || *got.ExactMatches != 2 {
		t.Fatalf("bad exact_matches: %+v", got)
	}
}

func TestReadStatusNeverReadsSecrets(t *testing.T) {
	dir := t.TempDir()
	oldDir := appDir
	appDir = dir
	defer func() { appDir = oldDir }()

	// Only a status file exists; config.json and .mi.token are absent.
	data, _ := json.Marshal(map[string]interface{}{"status": "ready", "did_configured": true})
	_ = os.WriteFile(filepath.Join(dir, statusName), data, 0600)
	got := readStatus()
	if !got.DidConfigured || got.Status != "ready" {
		t.Fatalf("bad status: %+v", got)
	}
	if strings.Contains(got.Detail, "sk-") {
		t.Fatal("status leaked a secret")
	}
}

func TestPageInjectsToken(t *testing.T) {
	content, err := fsReadPage()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(content, "__WEBSETUP_TOKEN__") {
		t.Fatal("page must contain the token placeholder")
	}
}

// fsReadPage is a tiny wrapper so the test reads the same embedded source as
// handlePage without depending on an http.Request.
func fsReadPage() (string, error) {
	b, err := page.ReadFile("setup.html")
	return string(b), err
}
