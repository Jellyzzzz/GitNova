package gittransport

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/textproto"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

const copyBufferSize = 32 << 10

type rpcResult struct {
	status          int
	committed       bool
	inputBytes      int64
	outputBytes     int64
	exitCode        int
	stderr          string
	stderrTruncated bool
	err             error
}

type inputResult struct {
	bytes    int64
	readErr  error
	writeErr error
}

func (b *Backend) stream(ctx context.Context, cancel context.CancelCauseFunc, controller *http.ResponseController,
	w http.ResponseWriter, r *http.Request, invocation Invocation, repositoryPath string) (result rpcResult) {
	result.exitCode = -1
	if ctx.Err() != nil {
		result.err = context.Cause(ctx)
		return
	}
	// These are owned pipes: Cmd.Wait must not close stdout before it is drained.
	stdinRead, stdinWrite, err := os.Pipe()
	if err != nil {
		result.err = fmt.Errorf("%w: stdin pipe: %v", errBackend, err)
		return
	}
	defer stdinRead.Close()
	defer stdinWrite.Close()
	stdoutRead, stdoutWrite, err := os.Pipe()
	if err != nil {
		result.err = fmt.Errorf("%w: stdout pipe: %v", errBackend, err)
		return
	}
	defer stdoutRead.Close()
	defer stdoutWrite.Close()
	stderrRead, stderrWrite, err := os.Pipe()
	if err != nil {
		result.err = fmt.Errorf("%w: stderr pipe: %v", errBackend, err)
		return
	}
	defer stderrRead.Close()
	defer stderrWrite.Close()

	command := exec.Command(b.config.Executable)
	command.Dir = repositoryPath
	command.Env = b.environment(r, invocation)
	command.Stdin, command.Stdout, command.Stderr = stdinRead, stdoutWrite, stderrWrite
	configureProcess(command)
	if err := command.Start(); err != nil {
		result.err = fmt.Errorf("%w: start process: %v", errBackend, err)
		return
	}
	_ = stdinRead.Close()
	_ = stdoutWrite.Close()
	_ = stderrWrite.Close()
	group := newProcessGroup(command.Process.Pid)

	body := r.Body
	if body == nil {
		body = http.NoBody
	}
	canceled := make(chan struct{})
	stopCancel := context.AfterFunc(ctx, func() {
		_ = controller.SetReadDeadline(time.Now())
		_ = controller.SetWriteDeadline(time.Now())
		_ = stdinWrite.Close()
		_ = stdoutRead.Close()
		_ = stderrRead.Close()
		group.stop()
		_ = body.Close()
		close(canceled)
	})

	progress := &activity{last: time.Now()}
	watchDone := make(chan struct{})
	watchStopped := make(chan struct{})
	go func() {
		progress.watch(ctx, cancel, b.config.IdleTimeout, watchDone)
		close(watchStopped)
	}()
	inputDone := make(chan inputResult, 1)
	go func() {
		inputDone <- b.copyInput(ctx, cancel, controller, body, stdinWrite, r, progress)
	}()
	stderr := &limitedCapture{limit: b.config.MaxStderrBytes}
	stderrDone := make(chan struct{})
	go func() {
		_, _ = io.CopyBuffer(stderr, stderrRead, make([]byte, copyBufferSize))
		close(stderrDone)
	}()
	processDone := make(chan error, 1)
	go func() {
		processDone <- group.wait(command)
	}()

	reader := bufio.NewReaderSize(&progressReader{reader: stdoutRead, progress: progress}, copyBufferSize)
	headers, status, length, headerErr := readCGIHeaders(reader, b.config.MaxHeaderBytes)
	if headerErr != nil {
		cancel(fmt.Errorf("%w: %v", errBackend, headerErr))
	} else if ctx.Err() == nil {
		for name, values := range headers {
			for _, value := range values {
				w.Header().Add(name, value)
			}
		}
		if status >= 400 && r.ProtoMajor == 1 && r.ContentLength != 0 {
			w.Header().Set("Connection", "close")
		}
		result.status, result.committed = status, true
		w.WriteHeader(status)
		writer := &clientWriter{ctx: ctx, writer: w, controller: controller, idle: b.config.IdleTimeout, progress: progress}
		count, copyErr := io.CopyBuffer(writer, reader, make([]byte, copyBufferSize))
		result.outputBytes = count
		if copyErr != nil {
			if writer.err != nil {
				cancel(fmt.Errorf("%w: %v", errClientWrite, writer.err))
			} else {
				cancel(fmt.Errorf("%w: response body read: %v", errBackend, copyErr))
			}
		} else if length >= 0 && count != length {
			cancel(fmt.Errorf("%w: response content length mismatch", errBackend))
		}
	}

	var input inputResult
	var waitErr error
	var inputJoined, processJoined bool
	var drainTimer *time.Timer
	for !inputJoined || !processJoined {
		select {
		case input = <-inputDone:
			inputJoined = true
			inputDone = nil
		case waitErr = <-processDone:
			processJoined = true
			processDone = nil
			if !inputJoined {
				// An exited backend cannot consume more input. Give an in-flight
				// HTTP terminator a bounded grace period before interrupting it.
				drainTimer = time.AfterFunc(time.Second, func() {
					cancel(fmt.Errorf("%w: backend exited before request completed", errBackend))
				})
			}
		}
	}
	if drainTimer != nil {
		drainTimer.Stop()
	}
	if input.readErr != nil && ctx.Err() == nil {
		cancel(input.readErr)
	}
	if input.writeErr != nil && ctx.Err() == nil && result.status < 400 {
		cancel(fmt.Errorf("%w: backend stopped reading request", errBackend))
	}
	if waitErr != nil && ctx.Err() == nil {
		cancel(fmt.Errorf("%w: wait process: %v", errBackend, waitErr))
	}
	close(watchDone)
	<-watchStopped
	if !stopCancel() {
		<-canceled
	}
	_ = body.Close()
	<-stderrDone

	result.inputBytes = input.bytes
	if command.ProcessState != nil {
		result.exitCode = command.ProcessState.ExitCode()
	}
	result.stderr, result.stderrTruncated = stderr.buffer.String(), stderr.truncated
	result.err = context.Cause(ctx)
	if input.readErr != nil && errors.Is(result.err, context.Canceled) && !errors.Is(input.readErr, context.Canceled) {
		result.err = input.readErr
	}
	return
}

func (b *Backend) copyInput(ctx context.Context, cancel context.CancelCauseFunc, controller *http.ResponseController,
	body io.ReadCloser, stdin *os.File, r *http.Request, progress *activity) (result inputResult) {
	defer stdin.Close()
	defer func() {
		_ = controller.SetReadDeadline(time.Time{})
		if ctx.Err() != nil {
			// Body.Close may drain unread HTTP/1 bytes. Do not undo the
			// cancellation deadline while cleanup still owns the body.
			_ = controller.SetReadDeadline(time.Now())
		}
	}()
	raw := &boundedReader{reader: &requestReader{ctx: ctx, reader: body, controller: controller,
		idle: b.config.IdleTimeout, progress: progress}, remaining: b.config.MaxRequestBytes}
	var reader io.Reader = raw
	if r.Header.Get("Content-Encoding") != "" {
		decompressed, err := gzip.NewReader(raw)
		if err != nil {
			result.readErr = inputError(err)
			cancel(result.readErr)
			return
		}
		defer decompressed.Close()
		reader = &boundedReader{reader: decompressed, remaining: b.config.MaxRequestBytes}
	}
	buffer := make([]byte, copyBufferSize)
	for {
		count, readErr := reader.Read(buffer)
		result.bytes += int64(count)
		if count > 0 && result.writeErr == nil {
			written := 0
			for written < count {
				size, writeErr := stdin.Write(buffer[written:count])
				written += size
				if size > 0 {
					progress.touch()
				}
				if writeErr != nil {
					result.writeErr = writeErr
					break
				}
				if size == 0 {
					result.writeErr = io.ErrShortWrite
					break
				}
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				result.readErr = inputError(readErr)
			} else if r.ContentLength >= 0 && raw.count != r.ContentLength {
				result.readErr = fmt.Errorf("%w: truncated body", errBodyRead)
			}
			if result.readErr != nil && ctx.Err() == nil {
				cancel(result.readErr)
			}
			return
		}
		if ctx.Err() != nil {
			return
		}
	}
}

func inputError(err error) error {
	if errors.Is(err, errBodyLimit) {
		return errBodyLimit
	}
	var networkError net.Error
	if errors.As(err, &networkError) && networkError.Timeout() {
		return fmt.Errorf("%w: request body read timeout", errIdle)
	}
	return fmt.Errorf("%w: %w", errBodyRead, err)
}

type activity struct {
	mu   sync.Mutex
	last time.Time
}

func (a *activity) touch() { a.mu.Lock(); a.last = time.Now(); a.mu.Unlock() }

func (a *activity) watch(ctx context.Context, cancel context.CancelCauseFunc, idle time.Duration, done <-chan struct{}) {
	interval := idle / 2
	if interval > time.Second {
		interval = time.Second
	}
	if interval <= 0 {
		interval = time.Nanosecond
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			a.mu.Lock()
			elapsed := time.Since(a.last)
			a.mu.Unlock()
			if elapsed >= idle {
				cancel(errIdle)
				return
			}
		case <-ctx.Done():
			return
		case <-done:
			return
		}
	}
}

type requestReader struct {
	ctx        context.Context
	reader     io.Reader
	controller *http.ResponseController
	idle       time.Duration
	progress   *activity
}

func (reader *requestReader) Read(data []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	if err := reader.controller.SetReadDeadline(time.Now().Add(reader.idle)); err != nil {
		return 0, err
	}
	if err := reader.ctx.Err(); err != nil {
		_ = reader.controller.SetReadDeadline(time.Now())
		return 0, err
	}
	count, err := reader.reader.Read(data)
	if count > 0 {
		reader.progress.touch()
	}
	return count, err
}

type progressReader struct {
	reader   io.Reader
	progress *activity
}

func (reader *progressReader) Read(data []byte) (int, error) {
	count, err := reader.reader.Read(data)
	if count > 0 {
		reader.progress.touch()
	}
	return count, err
}

type clientWriter struct {
	ctx        context.Context
	writer     http.ResponseWriter
	controller *http.ResponseController
	idle       time.Duration
	progress   *activity
	err        error
}

func (writer *clientWriter) Write(data []byte) (int, error) {
	if err := writer.ctx.Err(); err != nil {
		writer.err = err
		return 0, err
	}
	if err := writer.controller.SetWriteDeadline(time.Now().Add(writer.idle)); err != nil {
		writer.err = err
		return 0, err
	}
	if err := writer.ctx.Err(); err != nil {
		_ = writer.controller.SetWriteDeadline(time.Now())
		writer.err = err
		return 0, err
	}
	count, err := writer.writer.Write(data)
	if err == nil {
		err = writer.controller.Flush()
	}
	if count > 0 {
		writer.progress.touch()
	}
	writer.err = err
	return count, err
}

type boundedReader struct {
	reader    io.Reader
	remaining int64
	count     int64
}

func (reader *boundedReader) Read(data []byte) (int, error) {
	if int64(len(data)) > reader.remaining+1 {
		data = data[:reader.remaining+1]
	}
	count, err := reader.reader.Read(data)
	reader.count += int64(count)
	if int64(count) > reader.remaining {
		allowed := int(reader.remaining)
		reader.remaining = 0
		return allowed, errBodyLimit
	}
	reader.remaining -= int64(count)
	return count, err
}

type limitedCapture struct {
	limit     int
	buffer    bytes.Buffer
	truncated bool
}

func (capture *limitedCapture) Write(data []byte) (int, error) {
	count := len(data)
	remaining := capture.limit - capture.buffer.Len()
	if len(data) > remaining {
		data = data[:remaining]
		capture.truncated = true
	}
	_, _ = capture.buffer.Write(data)
	return count, nil
}

func readCGIHeaders(reader *bufio.Reader, limit int) (http.Header, int, int64, error) {
	var buffer bytes.Buffer
	lineBytes := 0
	for {
		line, err := reader.ReadSlice('\n')
		if buffer.Len()+len(line) > limit {
			return nil, 0, -1, errors.New("CGI headers too large")
		}
		buffer.Write(line)
		lineBytes += len(line)
		if err == bufio.ErrBufferFull {
			continue
		}
		if err != nil {
			return nil, 0, -1, errors.New("incomplete CGI headers")
		}
		if lineBytes == len(line) && (bytes.Equal(line, []byte("\n")) || bytes.Equal(line, []byte("\r\n"))) {
			break
		}
		lineBytes = 0
	}
	headers, err := textproto.NewReader(bufio.NewReader(bytes.NewReader(buffer.Bytes()))).ReadMIMEHeader()
	if err != nil {
		return nil, 0, -1, errors.New("malformed CGI headers")
	}
	status := http.StatusOK
	if values := headers.Values("Status"); len(values) > 0 {
		if len(values) != 1 || len(values[0]) < 3 {
			return nil, 0, -1, errors.New("invalid CGI status")
		}
		fields := strings.Fields(values[0])
		if len(fields) == 0 {
			return nil, 0, -1, errors.New("invalid CGI status")
		}
		status, err = strconv.Atoi(fields[0])
		if err != nil || len(fields[0]) != 3 || status < 200 || status > 599 {
			return nil, 0, -1, errors.New("invalid CGI status")
		}
	}
	if headers.Get("Content-Type") == "" {
		return nil, 0, -1, errors.New("CGI content type missing")
	}
	if headers.Get("Transfer-Encoding") != "" {
		return nil, 0, -1, errors.New("CGI transfer encoding is not supported")
	}
	length := int64(-1)
	if values := headers.Values("Content-Length"); len(values) > 0 {
		if len(values) != 1 {
			return nil, 0, -1, errors.New("ambiguous CGI content length")
		}
		length, err = strconv.ParseInt(values[0], 10, 64)
		if err != nil || length < 0 {
			return nil, 0, -1, errors.New("invalid CGI content length")
		}
	}
	for _, connection := range headers.Values("Connection") {
		for _, name := range strings.Split(connection, ",") {
			headers.Del(strings.TrimSpace(name))
		}
	}
	for _, name := range []string{"Status", "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
		"TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		headers.Del(name)
	}
	return http.Header(headers), status, length, nil
}
