//go:build linux || darwin

package gittransport

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func scriptBackend(t *testing.T, script string, change func(*Config)) (*Backend, string) {
	t.Helper()
	root := t.TempDir()
	repository := filepath.Join(root, "100.git")
	if err := os.Mkdir(repository, 0700); err != nil {
		t.Fatal(err)
	}
	executable := filepath.Join(root, "backend")
	if err := os.WriteFile(executable, []byte("#!/bin/sh\n"+script+"\n"), 0700); err != nil {
		t.Fatal(err)
	}
	config := Config{RepositoryRoot: root, Executable: executable, MaxDuration: 5 * time.Second,
		IdleTimeout: 2 * time.Second, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if change != nil {
		change(&config)
	}
	backend, err := New(config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := backend.Shutdown(ctx); err != nil {
			t.Errorf("cleanup: %v", err)
		}
	})
	return backend, repository
}

func backendServer(t *testing.T, backend *Backend) *httptest.Server {
	t.Helper()
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backend.Serve(w, r, Invocation{RepositoryID: "100", Service: UploadPack, Discovery: r.Method == http.MethodGet})
	}))
	server.Config.ErrorLog = log.New(io.Discard, "", 0)
	server.Start()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = backend.Shutdown(ctx)
		server.CloseClientConnections()
		server.Close()
	})
	return server
}

func rpcRequest(t *testing.T, url string, body io.Reader) *http.Request {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, url+"/git/100.git/git-upload-pack", body)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", "application/x-git-upload-pack-request")
	return request
}

func eventually(t *testing.T, condition func() bool) {
	t.Helper()
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if condition() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition did not become true")
}

func processGone(path string) bool {
	data, err := os.ReadFile(path)
	if err != nil {
		return false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	return err == nil && syscall.Kill(pid, 0) == syscall.ESRCH
}

func TestStreamBodies(t *testing.T) {
	for _, mode := range []string{"fixed", "chunked", "gzip"} {
		t.Run(mode, func(t *testing.T) {
			backend, _ := scriptBackend(t, "printf 'Content-Type: application/octet-stream\r\n\r\n'\ncat", nil)
			server := backendServer(t, backend)
			payload := bytes.Repeat([]byte("stream-data"), 10000)
			body := payload
			if mode == "gzip" {
				var compressed bytes.Buffer
				writer := gzip.NewWriter(&compressed)
				_, _ = writer.Write(payload)
				_ = writer.Close()
				body = compressed.Bytes()
			}
			request := rpcRequest(t, server.URL, bytes.NewReader(body))
			if mode != "fixed" {
				request.ContentLength = -1
			}
			if mode == "gzip" {
				request.Header.Set("Content-Encoding", "gzip")
			}
			response, err := server.Client().Do(request)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			got, err := io.ReadAll(response.Body)
			if err != nil || response.StatusCode != 200 || !bytes.Equal(got, payload) {
				t.Fatalf("response status=%d bytes=%d error=%v", response.StatusCode, len(got), err)
			}
			eventually(t, func() bool { return len(backend.slots) == 0 })
		})
	}
}

func TestResponseBeforeUploadEOF(t *testing.T) {
	backend, _ := scriptBackend(t, "printf 'Content-Type: application/octet-stream\r\n\r\n'\ncat", nil)
	server := backendServer(t, backend)
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	request := rpcRequest(t, server.URL, reader)
	client := server.Client()
	client.Timeout = 3 * time.Second
	responseDone := make(chan *http.Response, 1)
	errorsDone := make(chan error, 1)
	go func() {
		response, err := client.Do(request)
		if err != nil {
			errorsDone <- err
			return
		}
		responseDone <- response
	}()
	if _, err := writer.Write([]byte("first")); err != nil {
		t.Fatal(err)
	}
	select {
	case response := <-responseDone:
		defer response.Body.Close()
		first := make([]byte, 5)
		if _, err := io.ReadFull(response.Body, first); err != nil || string(first) != "first" {
			t.Fatalf("first streamed response=%q error=%v", first, err)
		}
		_ = writer.Close()
		if _, err := io.ReadAll(response.Body); err != nil {
			t.Fatal(err)
		}
	case err := <-errorsDone:
		t.Fatal(err)
	case <-time.After(2 * time.Second):
		t.Fatal("response was buffered until request EOF")
	}
}

func TestBodyLimitsAndInvalidGzip(t *testing.T) {
	for _, mode := range []string{"known", "unknown", "decompressed", "bad-gzip"} {
		t.Run(mode, func(t *testing.T) {
			backend, _ := scriptBackend(t, "cat >/dev/null\nprintf 'Content-Type: text/plain\r\n\r\nok'", func(config *Config) {
				config.MaxRequestBytes = 64
			})
			server := backendServer(t, backend)
			body := bytes.Repeat([]byte("a"), 128)
			status := http.StatusRequestEntityTooLarge
			if mode == "decompressed" {
				var compressed bytes.Buffer
				writer := gzip.NewWriter(&compressed)
				_, _ = writer.Write(body)
				_ = writer.Close()
				body = compressed.Bytes()
			}
			if mode == "bad-gzip" {
				body = []byte("invalid")
				status = http.StatusBadRequest
			}
			request := rpcRequest(t, server.URL, bytes.NewReader(body))
			if mode == "unknown" || mode == "decompressed" {
				request.ContentLength = -1
			}
			if mode == "decompressed" || mode == "bad-gzip" {
				request.Header.Set("Content-Encoding", "gzip")
			}
			response, err := server.Client().Do(request)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = io.Copy(io.Discard, response.Body)
			_ = response.Body.Close()
			if response.StatusCode != status {
				t.Fatalf("status=%d want=%d", response.StatusCode, status)
			}
			eventually(t, func() bool { return len(backend.slots) == 0 })
		})
	}
}

func TestBackendHeadersAndExit(t *testing.T) {
	cases := []struct{ name, script string }{
		{"missing type", "printf 'Status: 200 OK\r\n\r\nbody'"},
		{"invalid status", "printf 'Status: xyz\r\nContent-Type: text/plain\r\n\r\n'"},
		{"oversized", "printf 'Content-Type: text/plain\r\nX-Large: " + strings.Repeat("x", 200) + "\r\n\r\n'"},
		{"early exit", "exit 2"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			backend, _ := scriptBackend(t, test.script, func(config *Config) { config.MaxHeaderBytes = 128 })
			server := backendServer(t, backend)
			response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
			if err != nil {
				t.Fatal(err)
			}
			_ = response.Body.Close()
			if response.StatusCode != http.StatusBadGateway {
				t.Fatalf("status=%d", response.StatusCode)
			}
			eventually(t, func() bool { return len(backend.slots) == 0 })
		})
	}
}

func TestResponseFailureAbortsStream(t *testing.T) {
	for _, script := range []string{
		"printf 'Content-Type: application/octet-stream\r\nContent-Length: 20\r\n\r\nabc'",
		"printf 'Content-Type: application/octet-stream\r\n\r\nabc'\nexit 2",
	} {
		backend, _ := scriptBackend(t, script, nil)
		server := backendServer(t, backend)
		response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if err == nil || string(body) != "abc" {
			t.Fatalf("body=%q error=%v", body, err)
		}
		eventually(t, func() bool { return len(backend.slots) == 0 })
	}
}

const waitingScript = `sleep 30 &
child=$!
trap 'kill "$child" 2>/dev/null; wait "$child"; exit 0' TERM
echo $$ > parent.pid
echo "$child" > child.pid
wait "$child"`

func TestAdmissionAndShutdown(t *testing.T) {
	backend, repository := scriptBackend(t, waitingScript, func(config *Config) { config.MaxConcurrent = 1 })
	server := backendServer(t, backend)
	finished := make(chan error, 1)
	go func() {
		response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
		if err == nil {
			_ = response.Body.Close()
			if response.StatusCode != 503 {
				err = fmt.Errorf("status=%d", response.StatusCode)
			}
		}
		finished <- err
	}()
	eventually(t, func() bool { _, err := os.Stat(filepath.Join(repository, "child.pid")); return err == nil })
	response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 503 || response.Header.Get("Retry-After") == "" {
		t.Fatal("missing overload response")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := backend.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	if len(backend.slots) != 0 {
		t.Fatal("slot released before cleanup finished")
	}
	eventually(t, func() bool {
		return processGone(filepath.Join(repository, "parent.pid")) && processGone(filepath.Join(repository, "child.pid"))
	})
}

func TestClientCancellationReapsProcessGroup(t *testing.T) {
	backend, repository := scriptBackend(t, waitingScript, nil)
	server := backendServer(t, backend)
	ctx, cancel := context.WithCancel(context.Background())
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, server.URL+"/git/100.git/info/refs", nil)
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
		t.Fatal("client cancellation should fail the request")
	}
	eventually(t, func() bool { return len(backend.slots) == 0 })
	eventually(t, func() bool {
		return processGone(filepath.Join(repository, "parent.pid")) && processGone(filepath.Join(repository, "child.pid"))
	})
}

func TestTruncatedUpload(t *testing.T) {
	backend, _ := scriptBackend(t, "cat >/dev/null\nprintf 'Content-Type: text/plain\r\n\r\nok'", nil)
	server := backendServer(t, backend)
	connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprintf(connection, "POST /git/100.git/git-upload-pack HTTP/1.1\r\nHost: local\r\nContent-Type: application/x-git-upload-pack-request\r\nContent-Length: 100\r\n\r\nshort")
	_ = connection.(*net.TCPConn).CloseWrite()
	response, err := http.ReadResponse(bufio.NewReader(connection), nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 400 {
		t.Fatalf("status=%d", response.StatusCode)
	}
	eventually(t, func() bool { return len(backend.slots) == 0 })
}

func TestIdleAndMaximumDuration(t *testing.T) {
	t.Run("idle", func(t *testing.T) {
		backend, _ := scriptBackend(t, "sleep 30", func(config *Config) { config.IdleTimeout = 100 * time.Millisecond })
		server := backendServer(t, backend)
		response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
		if err != nil {
			t.Fatal(err)
		}
		_ = response.Body.Close()
		if response.StatusCode != 504 {
			t.Fatalf("status=%d", response.StatusCode)
		}
	})
	t.Run("progress cannot bypass maximum duration", func(t *testing.T) {
		backend, _ := scriptBackend(t, "printf 'Content-Type: application/octet-stream\r\n\r\n'\nwhile :; do printf x; sleep 0.01; done", func(config *Config) {
			config.MaxDuration = time.Second
		})
		server := backendServer(t, backend)
		response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if err == nil || len(body) == 0 || strings.Trim(string(body), "x") != "" {
			t.Fatalf("body=%q error=%v", body, err)
		}
		eventually(t, func() bool { return len(backend.slots) == 0 })
	})
}

func TestSlowDownloadAndClientDisconnect(t *testing.T) {
	for _, mode := range []string{"disconnect", "slow reader"} {
		t.Run(mode, func(t *testing.T) {
			backend, repository := scriptBackend(t, "echo $$ > parent.pid\nprintf 'Content-Type: application/octet-stream\r\n\r\n'\nyes streamed-data", func(config *Config) { config.IdleTimeout = 750 * time.Millisecond })
			server := backendServer(t, backend)
			if mode == "disconnect" {
				response, err := server.Client().Get(server.URL + "/git/100.git/info/refs")
				if err != nil {
					t.Fatal(err)
				}
				if response.StatusCode != 200 {
					t.Fatalf("stream status=%d", response.StatusCode)
				}
				_, _ = io.CopyN(io.Discard, response.Body, 1024)
				_ = response.Body.Close()
			} else {
				connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
				if err != nil {
					t.Fatal(err)
				}
				defer connection.Close()
				_, _ = fmt.Fprintf(connection, "GET /git/100.git/info/refs HTTP/1.1\r\nHost: local\r\n\r\n")
			}
			eventually(t, func() bool { _, err := os.Stat(filepath.Join(repository, "parent.pid")); return err == nil })
			eventually(t, func() bool { return len(backend.slots) == 0 })
			eventually(t, func() bool { return processGone(filepath.Join(repository, "parent.pid")) })
		})
	}
}

func TestHeaderFilteringAndCaptureBounds(t *testing.T) {
	headers, status, length, err := readCGIHeaders(bufio.NewReader(strings.NewReader(
		"Status: 403 Forbidden\r\nContent-Type: text/plain\r\nConnection: X-Private\r\nX-Private: hidden\r\nContent-Length: 3\r\n\r\n")), 1024)
	if err != nil || status != 403 || length != 3 {
		t.Fatalf("status=%d length=%d error=%v", status, length, err)
	}
	if headers.Get("Connection") != "" || headers.Get("X-Private") != "" || headers.Get("Transfer-Encoding") != "" {
		t.Fatal("hop-by-hop headers leaked")
	}
	capture := &limitedCapture{limit: 8}
	if count, err := capture.Write(bytes.Repeat([]byte("x"), 100)); err != nil || count != 100 || capture.buffer.Len() != 8 || !capture.truncated {
		t.Fatal("stderr capture is not bounded")
	}
}
