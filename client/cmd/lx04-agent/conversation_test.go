// SPDX-License-Identifier: Apache-2.0
package main

import (
	"testing"
	"time"
)

func TestConvStoreOffAndSession(t *testing.T) {
	off := newConvStore("off", 20, 0)
	off.append(off.key(""), "q", "a", time.Now())
	if off.history(off.key(""), time.Now()) != nil {
		t.Fatal("off mode kept history")
	}

	session := newConvStore("session", 2, 0)
	key := session.key("")
	now := time.Now()
	session.append(key, "q1", "a1", now)
	session.append(key, "q2", "a2", now)
	session.append(key, "q3", "a3", now)
	got := session.history(key, now)
	if len(got) != 2 || got[0].User != "q2" || got[1].User != "q3" {
		t.Fatalf("bad cap: %+v", got)
	}
}

func TestConvStoreTTL(t *testing.T) {
	store := newConvStore("session", 20, time.Minute)
	key := store.key("")
	start := time.Now()
	store.append(key, "q", "a", start)
	if len(store.history(key, start.Add(30*time.Second))) != 1 {
		t.Fatal("history expired too early")
	}
	if len(store.history(key, start.Add(2*time.Minute))) != 0 {
		t.Fatal("history did not expire")
	}
}

func TestConvStoreSpeakerBuckets(t *testing.T) {
	store := newConvStore("speaker", 20, 0)
	alice := store.key("alice")
	unknown := store.key("")
	if alice == unknown {
		t.Fatal("speaker buckets collided")
	}
	store.append(alice, "q", "a", time.Now())
	if len(store.history(alice, time.Now())) != 1 {
		t.Fatal("alice history missing")
	}
	if len(store.history(unknown, time.Now())) != 0 {
		t.Fatal("unknown bucket polluted")
	}
}

func TestConvStoreClipsLongText(t *testing.T) {
	store := newConvStore("session", 20, 0)
	key := store.key("")
	store.append(key, string(make([]rune, 500)), string(make([]rune, 5000)), time.Now())
	got := store.history(key, time.Now())
	if len([]rune(got[0].User)) != 200 || len([]rune(got[0].Assistant)) != 1000 {
		t.Fatalf("bad clip: user=%d assistant=%d", len([]rune(got[0].User)), len([]rune(got[0].Assistant)))
	}
}
