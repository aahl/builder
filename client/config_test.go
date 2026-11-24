// SPDX-License-Identifier: Apache-2.0
package xiaobot

import "testing"

func TestNormalizeDefaults(t *testing.T) {
	var c Config
	c.Normalize()
	if c.ContextMode != "session" || c.ContextTurns != 20 || c.ContextTTLSec != 0 || c.ThinkingMode != "off" {
		t.Fatalf("bad defaults: %+v", c)
	}
}

func TestNormalizeBounds(t *testing.T) {
	c := Config{ContextMode: "bogus", ContextTurns: 9999, ContextTTLSec: -3, ThinkingMode: "turbo"}
	c.Normalize()
	if c.ContextMode != "session" || c.ContextTurns != 200 || c.ContextTTLSec != 0 || c.ThinkingMode != "off" {
		t.Fatalf("bad clamp: %+v", c)
	}
	c = Config{ContextMode: "off", ContextTurns: 0, ContextTTLSec: 86400, ThinkingMode: "max"}
	c.Normalize()
	if c.ContextTurns != 20 || c.ContextTTLSec != 86400 || c.ThinkingMode != "max" {
		t.Fatalf("bad clamp: %+v", c)
	}
}

func TestRequestOptionsThinking(t *testing.T) {
	c := Config{
		ThinkingMode: "max",
		GPTOptions:   map[string]interface{}{"top_p": 0.9, "reasoning_effort": "low"},
	}
	opts := c.RequestOptions()
	if opts["top_p"] != 0.9 {
		t.Fatal("advanced option lost")
	}
	if opts["reasoning_effort"] != "max" {
		t.Fatalf("effort not overridden: %+v", opts)
	}
	thinking, ok := opts["thinking"].(map[string]string)
	if !ok || thinking["type"] != "enabled" {
		t.Fatalf("thinking not enabled: %+v", opts)
	}

	c.ThinkingMode = "off"
	opts = c.RequestOptions()
	thinking, ok = opts["thinking"].(map[string]string)
	if !ok || thinking["type"] != "disabled" {
		t.Fatalf("thinking not disabled: %+v", opts)
	}
	if _, exists := opts["reasoning_effort"]; exists {
		t.Fatal("stale reasoning_effort retained")
	}
}

func TestRequestOptionsNilMap(t *testing.T) {
	c := Config{ThinkingMode: "off"}
	opts := c.RequestOptions()
	if opts == nil || opts["thinking"] == nil {
		t.Fatal("nil options not initialized")
	}
}
