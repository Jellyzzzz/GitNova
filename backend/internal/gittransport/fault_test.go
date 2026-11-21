//go:build linux || darwin

package gittransport

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestInvalidCGIResponses(t *testing.T) {
	cases := map[string]string{
		"missing content type":          "printf 'Status: 200 OK\r\n\r\n'",
		"invalid status":                "printf 'Status: 999 nope\r\nContent-Type: text/plain\r\n\r\n'",
		"negative content length":       "printf 'Content-Type: text/plain\r\nContent-Length: -1\r\n\r\n'",
		"duplicate content length":      "printf 'Content-Type: text/plain\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n'",
		"HTTP transfer coding from CGI": "printf 'Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n'",
		"incomplete header":             "printf 'Content-Type: text/plain'",
		"oversized header":              "printf 'Content-Type: text/plain\r\nX-Large: " + strings.Repeat("x", 256) + "\r\n\r\n'",
		"early process failure":         "exit 7",
	}
	for name, script := range cases {
		t.Run(name, func(t *testing.T) {
			backend, _ := scriptBackend(t, script, func(config *Config) { config.MaxHeaderBytes = 128 })
			server := backendServer(t, backend)
			response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			_ = response.Body.Close()
			if err != nil || response.StatusCode != 502 {
				t.Fatalf("status=%d body=%q error=%v", response.StatusCode, body, err)
			}
			eventually(t, func() bool { return len(backend.slots) == 0 })
		})
	}
}

func TestTrustedInvocationAndEnvironment(t *testing.T) {
	backend, _ := scriptBackend(t, "printf 'Content-Type: text/plain\r\n\r\n'\nprintf '%s|%s|%s|%s' \"$REMOTE_USER\" \"$GIT_PROTOCOL\" \"$HTTP_AUTHORIZATION\" \"$HTTP_COOKIE\"", nil)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backend.Serve(w, r, Invocation{RepositoryID: 100, Service: UploadPack, Discovery: true, UserID: 42})
	}))
	defer server.Close()
	request, _ := http.NewRequest(http.MethodGet, server.URL+"/not-a-filesystem-path", nil)
	request.Header.Set("Authorization", "Basic attacker")
	request.Header.Set("Cookie", "secret")
	request.Header.Set("Git-Protocol", "version=2")
	response, err := server.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if err != nil || string(body) != "42|version=2||" {
		t.Fatalf("environment=%q error=%v", body, err)
	}
}

func TestEarlyExitWhileClientStillUploading(t *testing.T) {
	backend, _ := scriptBackend(t, "printf 'Content-Type: application/octet-stream\r\n\r\nearly'", nil)
	server := backendServer(t, backend)
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	request := rpcRequest(t, server.URL, reader)
	client := server.Client()
	client.Timeout = 4 * time.Second
	writerDone := make(chan struct{})
	go func() { _, _ = writer.Write([]byte("unfinished")); close(writerDone) }()
	response, err := client.Do(request)
	if err == nil {
		body, readErr := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if readErr == nil {
			t.Fatalf("incomplete upload incorrectly completed: %q", body)
		}
	}
	_ = writer.Close()
	<-writerDone
	eventually(t, func() bool { return len(backend.slots) == 0 })
}

func TestBurstAdmissionAndRecovery(t *testing.T) {
	script := "echo $$ > ready-$$\nwhile [ ! -f release ]; do sleep 0.01; done\nprintf 'Content-Type: text/plain\r\n\r\nok'"
	backend, repository := scriptBackend(t, script, func(config *Config) { config.MaxConcurrent = 3 })
	server := backendServer(t, backend)
	activeDone := make(chan error, 3)
	for range 3 {
		go func() {
			response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
			if err == nil {
				_, readErr := io.Copy(io.Discard, response.Body)
				_ = response.Body.Close()
				if readErr != nil {
					err = readErr
				} else if response.StatusCode != 200 {
					err = fmt.Errorf("status=%d", response.StatusCode)
				}
			}
			activeDone <- err
		}()
	}
	eventually(t, func() bool { files, _ := filepath.Glob(filepath.Join(repository, "ready-*")); return len(files) == 3 })
	var clients sync.WaitGroup
	failures := make(chan error, 30)
	for range 30 {
		clients.Add(1)
		go func() {
			defer clients.Done()
			response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
			if err == nil {
				_, _ = io.Copy(io.Discard, response.Body)
				_ = response.Body.Close()
				if response.StatusCode != 503 || response.Header.Get("Retry-After") == "" {
					err = fmt.Errorf("burst status=%d", response.StatusCode)
				}
			}
			failures <- err
		}()
	}
	clients.Wait()
	for range 30 {
		if err := <-failures; err != nil {
			t.Error(err)
		}
	}
	if len(backend.slots) != 3 {
		t.Fatalf("active RPC count=%d, want 3", len(backend.slots))
	}
	if err := os.WriteFile(filepath.Join(repository, "release"), nil, 0600); err != nil {
		t.Fatal(err)
	}
	for range 3 {
		if err := <-activeDone; err != nil {
			t.Error(err)
		}
	}
	eventually(t, func() bool { return len(backend.slots) == 0 })
	response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	_ = response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatalf("recovery status=%d", response.StatusCode)
	}
}

func TestRejectAnonymousWriteAndEscapingRepository(t *testing.T) {
	backend, repository := scriptBackend(t, "printf 'Content-Type: text/plain\r\n\r\nok'", nil)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backend.Serve(w, r, Invocation{RepositoryID: 100, Service: ReceivePack, Discovery: true})
	}))
	response, err := server.Client().Get(server.URL + "/git/100.git/info/refs?service=git-receive-pack")
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 403 {
		t.Fatalf("anonymous write discovery status=%d", response.StatusCode)
	}
	server.Close()
	external := t.TempDir()
	if err := os.Symlink(external, filepath.Join(filepath.Dir(repository), "101.git")); err != nil {
		t.Fatal(err)
	}
	request, _ := http.NewRequest(http.MethodGet, "http://local/git/101.git/info/refs", nil)
	_, status, err := backend.validate(request, Invocation{RepositoryID: 101, Service: UploadPack, Discovery: true})
	if err == nil || status != 404 {
		t.Fatal("repository symlink escape was accepted")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := backend.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestUploadReadIdleTimeout(t *testing.T) {
	backend, _ := scriptBackend(t, "cat >/dev/null", func(config *Config) {
		config.IdleTimeout = 200 * time.Millisecond
	})
	server := backendServer(t, backend)
	connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprintf(connection, "POST /git/100.git/git-upload-pack HTTP/1.1\r\nHost: local\r\nContent-Type: application/x-git-upload-pack-request\r\nContent-Length: 10\r\n\r\n")
	response, err := http.ReadResponse(bufio.NewReader(connection), nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 504 {
		t.Fatalf("body read timeout status=%d", response.StatusCode)
	}
	eventually(t, func() bool { return len(backend.slots) == 0 })
}

func TestCancellationInterruptsBlockedCGIInput(t *testing.T) {
	backend, repository := scriptBackend(t, waitingScript, nil)
	server := backendServer(t, backend)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	request := rpcRequest(t, server.URL, bytes.NewReader(bytes.Repeat([]byte("x"), 2<<20)))
	request = request.WithContext(ctx)
	finished := make(chan error, 1)
	go func() {
		response, err := server.Client().Do(request)
		if response != nil {
			_ = response.Body.Close()
		}
		finished <- err
	}()
	eventually(t, func() bool { _, err := os.Stat(filepath.Join(repository, "child.pid")); return err == nil })
	cancel()
	if err := <-finished; err == nil {
		t.Fatal("cancelled upload unexpectedly succeeded")
	}
	eventually(t, func() bool { return len(backend.slots) == 0 })
	eventually(t, func() bool {
		return processGone(filepath.Join(repository, "parent.pid")) && processGone(filepath.Join(repository, "child.pid"))
	})
}

func TestRejectedUnfinishedUploadClosesConnection(t *testing.T) {
	backend, _ := scriptBackend(t, "exit 1", nil)
	if err := backend.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
	server := backendServer(t, backend)
	connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = fmt.Fprintf(connection, "POST /git/100.git/git-upload-pack HTTP/1.1\r\nHost: local\r\nContent-Type: application/x-git-upload-pack-request\r\nTransfer-Encoding: chunked\r\n\r\n")
	reader := bufio.NewReader(connection)
	response, err := http.ReadResponse(reader, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, readErr := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if response.StatusCode != 503 || readErr != nil {
		t.Fatalf("status=%d body error=%v", response.StatusCode, readErr)
	}
	if _, err := reader.ReadByte(); err != io.EOF {
		t.Fatalf("rejected connection remained open: %v", err)
	}
}

func TestShutdownEscalatesUncooperativeProcess(t *testing.T) {
	backend, repository := scriptBackend(t, "trap '' TERM\necho $$ > parent.pid\nwhile :; do sleep 1; done", nil)
	server := backendServer(t, backend)
	finished := make(chan error, 1)
	go func() {
		response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
		if response != nil {
			_ = response.Body.Close()
			if response.StatusCode != 503 {
				err = fmt.Errorf("shutdown status=%d", response.StatusCode)
			}
		}
		finished <- err
	}()
	eventually(t, func() bool { _, err := os.Stat(filepath.Join(repository, "parent.pid")); return err == nil })
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := backend.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if !processGone(filepath.Join(repository, "parent.pid")) {
		t.Fatal("process ignoring SIGTERM was not killed and reaped")
	}
}
