// SPDX-License-Identifier: Apache-2.0
package main

import (
	"sync"
	"time"

	"xiaobot/voiceanswer"
)

type turnRecord struct {
	User      string
	Assistant string
	At        time.Time
}

// convStore keeps bounded, memory-only conversation history. It is cleared by
// agent restart; ttl=0 keeps turns until the count cap is reached.
type convStore struct {
	mu    sync.Mutex
	mode  string
	turns int
	ttl   time.Duration
	data  map[string][]turnRecord
}

func newConvStore(mode string, turns int, ttl time.Duration) *convStore {
	if turns < 1 {
		turns = 1
	}
	if turns > 200 {
		turns = 200
	}
	if ttl < 0 {
		ttl = 0
	}
	return &convStore{mode: mode, turns: turns, ttl: ttl, data: make(map[string][]turnRecord)}
}

func (s *convStore) key(speaker string) string {
	switch s.mode {
	case "off":
		return ""
	case "speaker":
		if speaker == "" {
			return "unknown"
		}
		return speaker
	default:
		return "session"
	}
}

func (s *convStore) history(key string, now time.Time) []voiceanswer.Turn {
	if key == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	records := s.data[key]
	if len(records) == 0 {
		return nil
	}
	if s.ttl > 0 && now.Sub(records[len(records)-1].At) > s.ttl {
		delete(s.data, key)
		return nil
	}
	out := make([]voiceanswer.Turn, 0, len(records))
	for _, record := range records {
		out = append(out, voiceanswer.Turn{User: record.User, Assistant: record.Assistant})
	}
	return out
}

func (s *convStore) append(key, user, assistant string, now time.Time) {
	if key == "" || assistant == "" {
		return
	}
	record := turnRecord{
		User:      clipRunes(user, 200),
		Assistant: clipRunes(assistant, 1000),
		At:        now,
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	records := append(s.data[key], record)
	if len(records) > s.turns {
		records = records[len(records)-s.turns:]
	}
	s.data[key] = records
}

func clipRunes(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}
