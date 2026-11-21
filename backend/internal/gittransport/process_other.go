//go:build !linux && !darwin

package gittransport

import (
	"errors"
	"os/exec"
)

func supportedPlatform() error           { return errors.New("Git bridge process groups require Linux or macOS") }
func configureProcess(command *exec.Cmd) {}

type processGroup struct{}

func newProcessGroup(pid int) *processGroup        { return &processGroup{} }
func (*processGroup) stop()                        {}
func (*processGroup) wait(command *exec.Cmd) error { return command.Wait() }
