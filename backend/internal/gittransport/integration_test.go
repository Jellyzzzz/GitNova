//go:build linux || darwin

package gittransport

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/rand"
	"fmt"
	"io"
	"log"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func gitRun(t *testing.T, directory string, args ...string) (string, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, "git", args...)
	command.Dir = directory
	command.Env = append(os.Environ(), "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL="+os.DevNull, "GIT_TERMINAL_PROMPT=0")
	output, err := command.CombinedOutput()
	return string(output), err
}

func gitMust(t *testing.T, directory string, args ...string) string {
	t.Helper()
	output, err := gitRun(t, directory, args...)
	if err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, output)
	}
	return strings.TrimSpace(output)
}

func TestNativeGitHTTP(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("native Git is not installed")
	}
	root := t.TempDir()
	bare := filepath.Join(root, "100.git")
	gitMust(t, root, "init", "--bare", "--initial-branch=main", bare)
	execPath := gitMust(t, root, "--exec-path")
	backend, err := New(Config{RepositoryRoot: root, Executable: filepath.Join(execPath, "git-http-backend"),
		IdleTimeout: 10 * time.Second, MaxDuration: 30 * time.Second,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		service := UploadPack
		if strings.HasSuffix(r.URL.Path, "/git-receive-pack") || r.URL.Query().Get("service") == "git-receive-pack" {
			service = ReceivePack
		}
		// The fixture is the trusted caller. Production routes must check PAT and ACL.
		backend.Serve(w, r, Invocation{RepositoryID: 100, Service: service, Discovery: r.Method == http.MethodGet, UserID: 1})
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
	hook := filepath.Join(bare, "hooks", "pre-receive")
	if err := os.WriteFile(hook, []byte("#!/bin/sh\nprintf '%s' \"$GIT_QUARANTINE_PATH\" > quarantine-path\n"), 0700); err != nil {
		t.Fatal(err)
	}
	working := filepath.Join(root, "working")
	gitMust(t, root, "init", "--initial-branch=main", working)
	gitMust(t, working, "config", "user.name", "GitNova Integration")
	gitMust(t, working, "config", "user.email", "test@example.invalid")
	payload := make([]byte, 2<<20)
	if _, err := rand.Read(payload); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(working, "payload.bin"), payload, 0600); err != nil {
		t.Fatal(err)
	}
	gitMust(t, working, "add", "payload.bin")
	gitMust(t, working, "commit", "-m", "first")
	url := server.URL + "/git/100.git"
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	push := exec.CommandContext(ctx, "git", "-c", "http.version=HTTP/1.1", "-c", "http.postBuffer=1024", "push", url, "main")
	push.Dir = working
	push.Env = append(os.Environ(), "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL="+os.DevNull,
		"GIT_TERMINAL_PROMPT=0", "GIT_TRACE_CURL=1", "GIT_TRACE_CURL_NO_DATA=1")
	trace, err := push.CombinedOutput()
	if err != nil {
		t.Fatalf("chunked push: %v\n%s", err, trace)
	}
	if !strings.Contains(strings.ToLower(string(trace)), "transfer-encoding: chunked") {
		t.Fatal("Git did not use a chunked upload")
	}
	oid := gitMust(t, working, "rev-parse", "HEAD")
	if got := gitMust(t, root, "--git-dir="+bare, "rev-parse", "refs/heads/main"); got != oid {
		t.Fatal("pushed ref mismatch")
	}
	quarantine, err := os.ReadFile(filepath.Join(bare, "quarantine-path"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(string(quarantine)); !os.IsNotExist(err) {
		t.Fatalf("quarantine was not migrated: %v", err)
	}
	clone := filepath.Join(root, "clone")
	gitMust(t, root, "-c", "protocol.version=2", "clone", url, clone)
	got, err := os.ReadFile(filepath.Join(clone, "payload.bin"))
	if err != nil || !bytes.Equal(got, payload) {
		t.Fatal("cloned file mismatch")
	}

	if err := os.WriteFile(filepath.Join(working, "next.txt"), []byte("next\n"), 0600); err != nil {
		t.Fatal(err)
	}
	gitMust(t, working, "add", "next.txt")
	gitMust(t, working, "commit", "-m", "second")
	gitMust(t, working, "push", url, "main")
	oid = gitMust(t, working, "rev-parse", "HEAD")
	gitMust(t, clone, "-c", "protocol.version=2", "fetch", "origin")
	if got := gitMust(t, clone, "rev-parse", "refs/remotes/origin/main"); got != oid {
		t.Fatal("fetched ref mismatch")
	}
	for _, compressed := range []bool{false, true} {
		requestBody := append(packet("want "+oid+" multi_ack_detailed side-band-64k ofs-delta\n"), []byte("0000")...)
		requestBody = append(requestBody, packet("done\n")...)
		if compressed {
			var output bytes.Buffer
			writer := gzip.NewWriter(&output)
			_, _ = writer.Write(requestBody)
			_ = writer.Close()
			requestBody = output.Bytes()
		}
		request := rpcRequest(t, server.URL, bytes.NewReader(requestBody))
		request.ContentLength = -1
		if compressed {
			request.Header.Set("Content-Encoding", "gzip")
		}
		response, err := server.Client().Do(request)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		_ = response.Body.Close()
		if err != nil || response.StatusCode != 200 || !bytes.Contains(body, []byte("PACK")) {
			t.Fatalf("fetch gzip=%v status=%d error=%v", compressed, response.StatusCode, err)
		}
	}
	if err := os.WriteFile(hook, []byte("#!/bin/sh\necho 'rejected by integration hook' >&2\nexit 1\n"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(working, "rejected.txt"), []byte("rejected\n"), 0600); err != nil {
		t.Fatal(err)
	}
	gitMust(t, working, "add", "rejected.txt")
	gitMust(t, working, "commit", "-m", "rejected")
	output, err := gitRun(t, working, "push", url, "main")
	if err == nil || !strings.Contains(output, "rejected by integration hook") {
		t.Fatalf("Git rejection was not preserved: %v\n%s", err, output)
	}
	if got := gitMust(t, root, "--git-dir="+bare, "rev-parse", "refs/heads/main"); got != oid {
		t.Fatal("rejected push changed ref")
	}
	gitMust(t, root, "--git-dir="+bare, "fsck", "--strict")
}

func packet(text string) []byte { return []byte(fmt.Sprintf("%04x%s", len(text)+4, text)) }

func TestHTTP2UnknownLength(t *testing.T) {
	backend, _ := scriptBackend(t, "printf 'Content-Type: application/octet-stream\r\n\r\n'\ncat", nil)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 2 {
			t.Error("HTTP/2 was not negotiated")
		}
		backend.Serve(w, r, Invocation{RepositoryID: 100, Service: UploadPack})
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = backend.Shutdown(ctx)
		server.CloseClientConnections()
		server.Close()
	})
	request := rpcRequest(t, server.URL, strings.NewReader("http2 stream"))
	request.ContentLength = -1
	response, err := server.Client().Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || string(body) != "http2 stream" || response.StatusCode != 200 {
		t.Fatalf("HTTP/2 status=%d body=%q error=%v", response.StatusCode, body, err)
	}
}
