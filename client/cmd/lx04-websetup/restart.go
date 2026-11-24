// Modified or added for XiaoSeek LX04 Web Setup. See NOTICE.
// SPDX-License-Identifier: Apache-2.0
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	agentRestartTimeout    = 12 * time.Second
	supervisorStartTimeout = 8 * time.Second
	agentPollInterval      = 200 * time.Millisecond
)

// restartAgent reloads the agent after the config changed. run.sh owns the
// process, so terminating the agent is enough: the supervisor notices the exit
// and starts a fresh one with the new config. When nothing is running yet the
// supervisor is launched directly. It returns the new agent PID when the
// restart could be observed.
func restartAgent(dir string) (int, bool) {
	old := readPID(filepath.Join(dir, "agent.pid"))
	if old > 0 && isAgentProcess(old, dir) {
		_ = syscall.Kill(old, syscall.SIGTERM)
	} else {
		old = 0
		launchSupervisor(dir)
	}
	if pid, ok := waitForAgent(dir, old, agentRestartTimeout); ok {
		return pid, true
	}
	if old > 0 {
		// The agent was running without a supervisor (started by hand over adb).
		launchSupervisor(dir)
		if pid, ok := waitForAgent(dir, old, supervisorStartTimeout); ok {
			return pid, true
		}
	}
	return 0, false
}

func waitForAgent(dir string, old int, timeout time.Duration) (int, bool) {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		time.Sleep(agentPollInterval)
		pid := readPID(filepath.Join(dir, "agent.pid"))
		if pid > 0 && pid != old && isAgentProcess(pid, dir) {
			return pid, true
		}
	}
	return 0, false
}

func isAgentProcess(pid int, dir string) bool {
	target, err := os.Readlink(filepath.Join("/proc", strconv.Itoa(pid), "exe"))
	return err == nil && target == filepath.Join(dir, "lx04-agent")
}

func readPID(path string) int {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0
	}
	return pid
}

// launchSupervisor starts run.sh detached. The script takes its own lock, so a
// duplicate launch exits immediately when a supervisor is already alive.
func launchSupervisor(dir string) {
	script := filepath.Join(dir, "run.sh")
	if _, err := os.Stat(script); err != nil {
		return
	}
	devnull, err := os.OpenFile(os.DevNull, os.O_RDWR, 0)
	if err != nil {
		return
	}
	defer devnull.Close()
	cmd := exec.Command("/system/bin/sh", script)
	cmd.Dir = dir
	cmd.Stdin, cmd.Stdout, cmd.Stderr = devnull, devnull, devnull
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return
	}
	go func() { _ = cmd.Wait() }()
}
