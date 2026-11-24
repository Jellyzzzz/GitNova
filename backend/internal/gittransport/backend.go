package gittransport

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"mime"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Service string

const (
	UploadPack  Service = "upload-pack"
	ReceivePack Service = "receive-pack"
)

// Invocation is a server-side decision made after authentication and repository
// authorization. Never bind these fields directly from a client request.
type Invocation struct {
	RepositoryID string
	Service      Service
	Discovery    bool
	UserID       int64
}

type Config struct {
	RepositoryRoot  string
	Executable      string
	MaxConcurrent   int
	MaxRequestBytes int64
	MaxDuration     time.Duration
	IdleTimeout     time.Duration
	MaxHeaderBytes  int
	MaxStderrBytes  int
	Logger          *slog.Logger
}

type Backend struct {
	config Config
	slots  chan struct{}
	ctx    context.Context
	cancel context.CancelCauseFunc
	mu     sync.Mutex
	closed bool
	runs   sync.WaitGroup
	done   chan struct{}
	once   sync.Once
}

var (
	errShutdown    = errors.New("Git backend shutting down")
	errMaxDuration = errors.New("Git request duration exceeded")
	errIdle        = errors.New("Git request made no progress")
	errBodyLimit   = errors.New("Git request body limit exceeded")
	errBodyRead    = errors.New("Git request body read failed")
	errBackend     = errors.New("Git backend transport failed")
	errClientWrite = errors.New("Git response write failed")
)

func New(config Config) (*Backend, error) {
	if config.RepositoryRoot == "" || config.Executable == "" {
		return nil, errors.New("Git repository root and executable are required")
	}
	if config.MaxConcurrent == 0 {
		config.MaxConcurrent = 8
	}
	if config.MaxRequestBytes == 0 {
		config.MaxRequestBytes = 1 << 30
	}
	if config.MaxDuration == 0 {
		config.MaxDuration = 30 * time.Minute
	}
	if config.IdleTimeout == 0 {
		config.IdleTimeout = 5 * time.Minute
	}
	if config.MaxHeaderBytes == 0 {
		config.MaxHeaderBytes = 32 << 10
	}
	if config.MaxStderrBytes == 0 {
		config.MaxStderrBytes = 16 << 10
	}
	if config.Logger == nil {
		config.Logger = slog.Default()
	}
	if config.MaxConcurrent < 1 || config.MaxRequestBytes < 1 ||
		config.MaxRequestBytes == int64(^uint64(0)>>1) || config.MaxDuration <= 0 ||
		config.IdleTimeout <= 0 || config.MaxHeaderBytes < 128 || config.MaxStderrBytes < 1 {
		return nil, errors.New("invalid Git backend limits")
	}
	root, err := filepath.Abs(config.RepositoryRoot)
	if err != nil {
		return nil, fmt.Errorf("repository root: %w", err)
	}
	root, err = filepath.EvalSymlinks(root)
	if err != nil {
		return nil, fmt.Errorf("repository root: %w", err)
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return nil, errors.New("repository root must be a directory")
	}
	executable, err := filepath.Abs(config.Executable)
	if err != nil {
		return nil, fmt.Errorf("Git backend executable: %w", err)
	}
	info, err = os.Stat(executable)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0111 == 0 {
		return nil, errors.New("Git backend must be an executable file")
	}
	if err := supportedPlatform(); err != nil {
		return nil, err
	}
	config.RepositoryRoot, config.Executable = root, executable
	ctx, cancel := context.WithCancelCause(context.Background())
	return &Backend{config: config, slots: make(chan struct{}, config.MaxConcurrent),
		ctx: ctx, cancel: cancel, done: make(chan struct{})}, nil
}

// Decimal IDs are accepted only to preserve the existing 100.git test fixture.
// Real repositories are generated as lowercase UUIDv7 by repository.Service.
func validTransportRepositoryID(id string) bool {
	if id == "" || len(id) > 64 {
		return false
	}
	for _, ch := range id {
		if !(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch == '-') {
			return false
		}
	}
	return true
}

// Shutdown stops admission, cancels active RPCs, and waits for their I/O and
// process cleanup. Its context limits the caller's wait, not the cleanup itself.
func (b *Backend) Shutdown(ctx context.Context) error {
	b.once.Do(func() {
		b.mu.Lock()
		b.closed = true
		b.cancel(errShutdown)
		b.mu.Unlock()
		go func() { b.runs.Wait(); close(b.done) }()
	})
	select {
	case <-b.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (b *Backend) admit() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return false
	}
	select {
	case b.slots <- struct{}{}:
		b.runs.Add(1)
		return true
	default:
		return false
	}
}

func (b *Backend) Serve(w http.ResponseWriter, r *http.Request, invocation Invocation) {
	if !b.admit() {
		w.Header().Set("Retry-After", "1")
		reject(w, r, http.StatusServiceUnavailable, "Git server busy or shutting down")
		return
	}
	defer func() { <-b.slots; b.runs.Done() }()
	path, status, err := b.validate(r, invocation)
	if err != nil {
		reject(w, r, status, err.Error())
		return
	}
	controller := http.NewResponseController(w)
	if err := controller.EnableFullDuplex(); err != nil && r.ProtoMajor < 2 {
		reject(w, r, http.StatusInternalServerError, "HTTP full duplex unavailable")
		return
	}
	if err := controller.SetReadDeadline(time.Time{}); err != nil {
		reject(w, r, http.StatusInternalServerError, "HTTP read deadlines unavailable")
		return
	}
	if err := controller.SetWriteDeadline(time.Time{}); err != nil {
		_ = controller.SetReadDeadline(time.Time{})
		reject(w, r, http.StatusInternalServerError, "HTTP write deadlines unavailable")
		return
	}
	defer func() {
		_ = controller.SetReadDeadline(time.Time{})
		_ = controller.SetWriteDeadline(time.Time{})
	}()
	ctx, cancel := context.WithCancelCause(r.Context())
	stopShutdown := context.AfterFunc(b.ctx, func() { cancel(errShutdown) })
	timer := time.AfterFunc(b.config.MaxDuration, func() { cancel(errMaxDuration) })
	defer func() { stopShutdown(); timer.Stop(); cancel(nil) }()

	started := time.Now()
	result := b.stream(ctx, cancel, controller, w, r, invocation, path)
	if result.err != nil && !result.committed && !errors.Is(result.err, context.Canceled) {
		result.status = errorStatus(result.err)
	}
	attributes := []any{"repository_id", invocation.RepositoryID, "service", invocation.Service,
		"discovery", invocation.Discovery, "user_id", invocation.UserID,
		"duration", time.Since(started), "request_bytes", result.inputBytes,
		"response_bytes", result.outputBytes, "status", result.status,
		"response_started", result.committed, "exit_code", result.exitCode, "error", result.err}
	if result.err != nil {
		b.config.Logger.Warn("Git RPC ended", attributes...)
	} else {
		b.config.Logger.Info("Git RPC ended", attributes...)
	}
	if result.stderr != "" {
		b.config.Logger.Debug("Git RPC stderr", "repository_id", invocation.RepositoryID,
			"stderr", result.stderr, "truncated", result.stderrTruncated)
	}
	if result.err == nil {
		return
	}
	if result.committed {
		panic(http.ErrAbortHandler)
	}
	if errors.Is(result.err, context.Canceled) {
		panic(http.ErrAbortHandler)
	}
	_ = controller.SetReadDeadline(time.Time{})
	_ = controller.SetWriteDeadline(time.Now().Add(min(b.config.IdleTimeout, 5*time.Second)))
	reject(w, r, errorStatus(result.err), errorMessage(result.err))
}

func (b *Backend) validate(r *http.Request, invocation Invocation) (string, int, error) {
	if !validTransportRepositoryID(invocation.RepositoryID) || invocation.UserID < 0 ||
		(invocation.Service != UploadPack && invocation.Service != ReceivePack) {
		return "", http.StatusBadRequest, errors.New("invalid Git invocation")
	}
	if invocation.Service == ReceivePack && invocation.UserID == 0 {
		return "", http.StatusForbidden, errors.New("authenticated Git user required")
	}
	method := http.MethodPost
	if invocation.Discovery {
		method = http.MethodGet
	}
	if r.Method != method {
		return "", http.StatusMethodNotAllowed, errors.New("invalid Git method")
	}
	if invocation.Discovery && r.ContentLength != 0 {
		return "", http.StatusBadRequest, errors.New("Git discovery must not have a request body")
	}
	if r.ContentLength > b.config.MaxRequestBytes {
		return "", http.StatusRequestEntityTooLarge, errBodyLimit
	}
	if !invocation.Discovery {
		mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || mediaType != "application/x-git-"+string(invocation.Service)+"-request" {
			return "", http.StatusUnsupportedMediaType, errors.New("invalid Git content type")
		}
	}
	encoding := strings.ToLower(r.Header.Get("Content-Encoding"))
	if encoding != "" && encoding != "gzip" && encoding != "x-gzip" {
		return "", http.StatusUnsupportedMediaType, errors.New("unsupported Git content encoding")
	}
	protocol := r.Header.Get("Git-Protocol")
	if len(protocol) > 1024 || strings.ContainsAny(protocol, "\r\n\x00") {
		return "", http.StatusBadRequest, errors.New("invalid Git protocol header")
	}
	path := filepath.Join(b.config.RepositoryRoot, invocation.RepositoryID+".git")
	resolved, err := filepath.EvalSymlinks(path)
	if err != nil || resolved != path {
		return "", http.StatusNotFound, errors.New("repository unavailable")
	}
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return "", http.StatusNotFound, errors.New("repository unavailable")
	}
	return path, 0, nil
}

func (b *Backend) environment(r *http.Request, invocation Invocation) []string {
	name := invocation.RepositoryID + ".git"
	path, query := "/"+name+"/git-"+string(invocation.Service), ""
	if invocation.Discovery {
		path = "/" + name + "/info/refs"
		query = "service=git-" + string(invocation.Service)
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	env := []string{"PATH=" + os.Getenv("PATH"), "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL=" + os.DevNull,
		"GIT_TERMINAL_PROMPT=0", "GIT_PROJECT_ROOT=" + b.config.RepositoryRoot, "GIT_HTTP_EXPORT_ALL=1",
		"REQUEST_METHOD=" + r.Method, "PATH_INFO=" + path, "QUERY_STRING=" + query,
		"GATEWAY_INTERFACE=CGI/1.1", "SERVER_PROTOCOL=" + r.Proto, "SERVER_NAME=gitnova",
		"SERVER_PORT=80", "REMOTE_ADDR=" + host, "GIT_PROTOCOL=" + r.Header.Get("Git-Protocol")}
	if !invocation.Discovery {
		env = append(env, "CONTENT_TYPE=application/x-git-"+string(invocation.Service)+"-request")
	}
	if invocation.UserID > 0 {
		env = append(env, "REMOTE_USER="+strconv.FormatInt(invocation.UserID, 10))
	}
	if r.Header.Get("Content-Encoding") == "" && r.ContentLength >= 0 {
		env = append(env, "CONTENT_LENGTH="+strconv.FormatInt(r.ContentLength, 10))
	}
	return env
}

func reject(w http.ResponseWriter, r *http.Request, status int, message string) {
	controller := http.NewResponseController(w)
	_ = controller.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if r.ProtoMajor == 1 && r.ContentLength != 0 {
		w.Header().Set("Connection", "close")
		// net/http may otherwise drain an unfinished body after the handler
		// returns, leaving rejected uploads blocked outside the admission cap.
		if err := controller.SetReadDeadline(time.Now()); err == nil && r.Body != nil {
			_ = r.Body.Close()
		}
	}
	http.Error(w, message, status)
}

func errorStatus(err error) int {
	switch {
	case errors.Is(err, errBodyLimit):
		return http.StatusRequestEntityTooLarge
	case errors.Is(err, errBodyRead):
		return http.StatusBadRequest
	case errors.Is(err, errIdle), errors.Is(err, errMaxDuration), errors.Is(err, context.DeadlineExceeded):
		return http.StatusGatewayTimeout
	case errors.Is(err, errShutdown):
		return http.StatusServiceUnavailable
	default:
		return http.StatusBadGateway
	}
}

func errorMessage(err error) string {
	switch errorStatus(err) {
	case http.StatusRequestEntityTooLarge:
		return "Git request too large"
	case http.StatusBadRequest:
		return "Git request body could not be read"
	case http.StatusGatewayTimeout:
		return "Git request timed out"
	case http.StatusServiceUnavailable:
		return "Git server shutting down"
	default:
		return "Git backend failed"
	}
}
