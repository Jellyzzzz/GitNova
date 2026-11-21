//go:build darwin

package gittransport

import "syscall"

func waitForExit(pid int) error {
	queue, err := syscall.Kqueue()
	if err != nil {
		return err
	}
	defer syscall.Close(queue)
	syscall.CloseOnExec(queue)
	changes := []syscall.Kevent_t{{Ident: uint64(pid), Filter: syscall.EVFILT_PROC,
		Flags: syscall.EV_ADD | syscall.EV_ONESHOT, Fflags: syscall.NOTE_EXIT}}
	events := make([]syscall.Kevent_t, 1)
	for {
		count, err := syscall.Kevent(queue, changes, events, nil)
		if err == syscall.EINTR {
			continue
		}
		// The child may have exited before registration. It is still ours and
		// has not been reaped, so its PID remains reserved until Cmd.Wait.
		if err == syscall.ESRCH {
			return nil
		}
		if err != nil {
			return err
		}
		changes = nil
		if count == 0 {
			continue
		}
		if events[0].Flags&syscall.EV_ERROR != 0 {
			err := syscall.Errno(events[0].Data)
			if err == syscall.ESRCH || err == 0 {
				return nil
			}
			return err
		}
		if events[0].Fflags&syscall.NOTE_EXIT != 0 {
			return nil
		}
	}
}
