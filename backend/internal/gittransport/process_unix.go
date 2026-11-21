//go:build linux || darwin

package gittransport

import (
	"errors"
	"os/exec"
	"sync"
	"syscall"
	"time"
)

func supportedPlatform() error { return nil }

func configureProcess(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
}

type processGroup struct {
	pid  int
	once sync.Once
}

func newProcessGroup(pid int) *processGroup { return &processGroup{pid: pid} }

func (group *processGroup) wait(command *exec.Cmd) error {
	// Observe exit without reaping the group leader. Keeping its PID reserved
	// prevents process-group signals from racing with PID reuse under load.
	observeErr := waitForExit(group.pid)
	group.stop()
	return errors.Join(observeErr, command.Wait())
}

func (group *processGroup) stop() {
	group.once.Do(func() {
		if err := syscall.Kill(-group.pid, syscall.SIGTERM); err != nil {
			return
		}
		deadline := time.NewTimer(200 * time.Millisecond)
		defer deadline.Stop()
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if syscall.Kill(-group.pid, 0) == syscall.ESRCH {
					return
				}
			case <-deadline.C:
				_ = syscall.Kill(-group.pid, syscall.SIGKILL)
				return
			}
		}
	})
}
