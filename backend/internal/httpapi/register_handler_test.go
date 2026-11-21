package httpapi

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

type registerStoreFunc func(context.Context, *identity.User) error

func (fn registerStoreFunc) Create(ctx context.Context, user *identity.User) error {
	return fn(ctx, user)
}

const validRegisterJSON = `{"username":"Jelly_User","email":"jelly@example.test","password":"Register-Password-2026"}`

func registerServer(t *testing.T, store identity.UserStore, readTimeout time.Duration) *httptest.Server {
	t.Helper()
	handler := NewRegisterHandler(identity.NewRegisterService(store))
	if readTimeout > 0 {
		handler.bodyReadTimeout = readTimeout
	}
	router := gin.New()
	router.POST("/api/auth/register", handler.Handle)
	server := httptest.NewServer(router)
	t.Cleanup(func() { server.CloseClientConnections(); server.Close() })
	return server
}

func postRegister(t *testing.T, server *httptest.Server, body, mediaType string, chunked bool) (int, []byte) {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, server.URL+"/api/auth/register", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Content-Type", mediaType)
	if chunked {
		request.ContentLength = -1
	}
	client := server.Client()
	client.Timeout = 5 * time.Second
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	return response.StatusCode, data
}

func TestRegisterRouterResponseContainsBothNames(t *testing.T) {
	store := registerStoreFunc(func(ctx context.Context, user *identity.User) error {
		if user.Username != "Jelly_User" || user.DisplayName != "Jelly_User" {
			t.Error("persisted username or display name lost the supplied spelling")
		}
		user.ID = 42
		return nil
	})
	server := httptest.NewServer(NewRouter(identity.NewRegisterService(store)))
	defer server.Close()
	status, data := postRegister(t, server, validRegisterJSON, "application/json; charset=utf-8", false)
	var result map[string]any
	if err := json.Unmarshal(data, &result); err != nil {
		t.Fatal(err)
	}
	if status != 201 || result["username"] != "Jelly_User" || result["display_name"] != "Jelly_User" || result["email_verified"] != false {
		t.Fatalf("status=%d body=%s", status, data)
	}
	for _, field := range []string{"password", "password_hash", "PasswordHash"} {
		if _, exists := result[field]; exists {
			t.Fatal("response exposed password material")
		}
	}
}

func TestRegisterRejectsAmbiguousJSONBeforePersistence(t *testing.T) {
	var calls atomic.Int32
	server := registerServer(t, registerStoreFunc(func(context.Context, *identity.User) error {
		calls.Add(1)
		return nil
	}), 0)
	cases := map[string]string{
		"second object":     validRegisterJSON + `{}`,
		"trailing garbage":  validRegisterJSON + `not-json`,
		"duplicate field":   strings.Replace(validRegisterJSON, `"username":"Jelly_User"`, `"username":"Jelly_User","username":"Other_User"`, 1),
		"case variant":      strings.Replace(validRegisterJSON, `"username":"Jelly_User"`, `"username":"Jelly_User","Username":"Other_User"`, 1),
		"escaped duplicate": strings.Replace(validRegisterJSON, `"username":"Jelly_User"`, `"username":"Jelly_User","user\u006eame":"Other_User"`, 1),
		"unknown field":     strings.TrimSuffix(validRegisterJSON, "}") + `,"email_verified":true}`,
		"number password":   strings.Replace(validRegisterJSON, `"Register-Password-2026"`, `12345678`, 1),
		"null username":     strings.Replace(validRegisterJSON, `"Jelly_User"`, `null`, 1),
		"missing email":     `{"username":"Jelly_User","password":"Register-Password-2026"}`,
		"invalid email":     strings.Replace(validRegisterJSON, "jelly@example.test", "not-an-email", 1),
		"root array":        `[]`, "root null": `null`, "empty body": "", "incomplete object": `{"username":`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			status, _ := postRegister(t, server, body, "application/json", false)
			if status != 400 {
				t.Fatalf("status=%d, want 400", status)
			}
		})
	}
	if calls.Load() != 0 {
		t.Fatal("invalid request reached persistence")
	}
}

func TestRegisterMediaTypeAndBodyLimits(t *testing.T) {
	store := registerStoreFunc(func(ctx context.Context, user *identity.User) error { user.ID = 1; return nil })
	server := registerServer(t, store, 0)
	for _, mediaType := range []string{"text/plain", "", "application/json; broken"} {
		status, _ := postRegister(t, server, validRegisterJSON, mediaType, false)
		if status != 415 {
			t.Fatalf("media type=%q status=%d", mediaType, status)
		}
	}
	exact := validRegisterJSON + strings.Repeat(" ", maxRegisterBodyBytes-len(validRegisterJSON))
	for _, chunked := range []bool{false, true} {
		if status, _ := postRegister(t, server, exact, "application/json", chunked); status != 201 {
			t.Fatalf("exact body limit, chunked=%v status=%d", chunked, status)
		}
		if status, _ := postRegister(t, server, exact+" ", "application/json", chunked); status != 413 {
			t.Fatalf("exceeded body limit, chunked=%v status=%d", chunked, status)
		}
	}
}

func TestRegisterUnfinishedChunkedBodyTimesOut(t *testing.T) {
	var calls atomic.Int32
	server := registerServer(t, registerStoreFunc(func(context.Context, *identity.User) error {
		calls.Add(1)
		return nil
	}), 150*time.Millisecond)
	connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
	_, _ = fmt.Fprintf(connection, "POST /api/auth/register HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n%x\r\n%s\r\n", len(validRegisterJSON), validRegisterJSON)
	response, err := http.ReadResponse(bufio.NewReader(connection), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != 408 || calls.Load() != 0 {
		t.Fatalf("status=%d store calls=%d", response.StatusCode, calls.Load())
	}
}

func TestRegisterReadDeadlineDoesNotCancelBusinessWork(t *testing.T) {
	server := registerServer(t, registerStoreFunc(func(ctx context.Context, user *identity.User) error {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
			user.ID = 1
			return nil
		}
	}), 150*time.Millisecond)
	if status, _ := postRegister(t, server, validRegisterJSON, "application/json", false); status != 201 {
		t.Fatalf("body deadline affected completed input's business work: status=%d", status)
	}
}

func TestRegisterDeadlineErrorRemainsTimeoutResponse(t *testing.T) {
	server := registerServer(t, registerStoreFunc(func(context.Context, *identity.User) error {
		return fmt.Errorf("insert interrupted: %w", context.DeadlineExceeded)
	}), 0)
	status, body := postRegister(t, server, validRegisterJSON, "application/json", false)
	if status != 504 || strings.Contains(string(body), "insert interrupted") {
		t.Fatalf("status=%d body=%s", status, body)
	}
}

func TestRegisterHTTP2UnknownLength(t *testing.T) {
	store := registerStoreFunc(func(ctx context.Context, user *identity.User) error { user.ID = 1; return nil })
	server := httptest.NewUnstartedServer(NewRouter(identity.NewRegisterService(store)))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	for _, input := range []struct {
		body   string
		status int
	}{{validRegisterJSON, 201}, {validRegisterJSON + `{}`, 400}} {
		request, err := http.NewRequest(http.MethodPost, server.URL+"/api/auth/register", strings.NewReader(input.body))
		if err != nil {
			t.Fatal(err)
		}
		request.ContentLength = -1
		request.Header.Set("Content-Type", "application/json")
		response, err := server.Client().Do(request)
		if err != nil {
			t.Fatal(err)
		}
		_, readErr := io.Copy(io.Discard, response.Body)
		_ = response.Body.Close()
		if readErr != nil || response.ProtoMajor != 2 || response.StatusCode != input.status {
			t.Fatalf("protocol=%s status=%d read error=%v", response.Proto, response.StatusCode, readErr)
		}
	}
}
