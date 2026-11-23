package httpapi

import (
	"bufio"
	"context"
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

type requestBodyTokens struct {
	issues   atomic.Int32
	verifies atomic.Int32
}

func (t *requestBodyTokens) Issue(context.Context, string) (string, error) {
	t.issues.Add(1)
	return "email-token", nil
}

func (t *requestBodyTokens) Verify(context.Context, string, string) (string, error) {
	t.verifies.Add(1)
	return "ticket", nil
}

func (*requestBodyTokens) Read(context.Context, string) (identity.RegistrationProof, error) {
	return identity.RegistrationProof{}, identity.ErrInvalidTicket
}

func (*requestBodyTokens) Delete(context.Context, string) error { return nil }

func newRequestBodyRouter(tokens identity.RegistrationTokens, timeout time.Duration) *gin.Engine {
	handler := NewRegisterHandler(identity.NewRegisterService(&testUsers{}, tokens, testMailer{}), false)
	if timeout > 0 {
		handler.bodyReadTimeout = timeout
	}
	router := gin.New()
	router.POST("/api/auth/registration/email", handler.RequestEmail)
	router.POST("/api/auth/registration/verify", handler.VerifyEmail)
	return router
}

type slowRequestBodyTokens struct{}

func waitAfterBodyRead(ctx context.Context) error {
	timer := time.NewTimer(300 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-timer.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (*slowRequestBodyTokens) Issue(ctx context.Context, _ string) (string, error) {
	return "email-token", waitAfterBodyRead(ctx)
}

func (*slowRequestBodyTokens) Verify(ctx context.Context, _, _ string) (string, error) {
	return "ticket", waitAfterBodyRead(ctx)
}

func (*slowRequestBodyTokens) Read(context.Context, string) (identity.RegistrationProof, error) {
	return identity.RegistrationProof{}, identity.ErrInvalidTicket
}

func (*slowRequestBodyTokens) Delete(context.Context, string) error { return nil }

func postRequestBody(t *testing.T, server *httptest.Server, path, body, contentType string, chunked bool) int {
	t.Helper()
	request, err := http.NewRequest(http.MethodPost, server.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
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
	if _, err := io.Copy(io.Discard, response.Body); err != nil {
		t.Fatal(err)
	}
	return response.StatusCode
}

func TestEmailEndpointsRejectInvalidRequestBodies(t *testing.T) {
	tokens := &requestBodyTokens{}
	server := httptest.NewServer(newRequestBodyRouter(tokens, 0))
	defer server.Close()
	endpoints := []struct {
		name, path, body string
		status           int
	}{
		{"email", "/api/auth/registration/email", `{"email":"alice@example.test"}`, http.StatusAccepted},
		{"verify", "/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}`, http.StatusOK},
	}
	for _, endpoint := range endpoints {
		t.Run(endpoint.name, func(t *testing.T) {
			type bodyCase struct {
				name, body, contentType string
				status                  int
			}
			cases := []bodyCase{
				{"valid", endpoint.body, "application/json; charset=utf-8", endpoint.status},
				{"duplicate", strings.Replace(endpoint.body, `"email":"alice@example.test"`, `"email":"alice@example.test","email":"other@example.test"`, 1), "application/json", 400},
				{"escaped duplicate", strings.Replace(endpoint.body, `"email":"alice@example.test"`, `"email":"alice@example.test","em\u0061il":"other@example.test"`, 1), "application/json", 400},
				{"case variant", strings.Replace(endpoint.body, `"email":"alice@example.test"`, `"email":"alice@example.test","Email":"other@example.test"`, 1), "application/json", 400},
				{"unknown field", strings.TrimSuffix(endpoint.body, "}") + `,"extra":"value"}`, "application/json", 400},
				{"second object", endpoint.body + `{}`, "application/json", 400},
				{"trailing garbage", endpoint.body + `garbage`, "application/json", 400},
				{"null email", strings.Replace(endpoint.body, `"alice@example.test"`, `null`, 1), "application/json", 400},
				{"number email", strings.Replace(endpoint.body, `"alice@example.test"`, `42`, 1), "application/json", 400},
				{"invalid email", strings.Replace(endpoint.body, `alice@example.test`, `invalid-email`, 1), "application/json", 400},
				{"array root", `[]`, "application/json", 400},
				{"null root", `null`, "application/json", 400},
				{"incomplete JSON", `{"email":`, "application/json", 400},
				{"empty", "", "application/json", 400},
				{"wrong media type", endpoint.body, "text/plain", 415},
				{"missing media type", endpoint.body, "", 415},
				{"malformed media type", endpoint.body, "application/json; broken", 415},
			}
			if endpoint.name == "verify" {
				cases = append(cases,
					bodyCase{"duplicate token", strings.Replace(endpoint.body, `"token":"example-token"`, `"token":"bad","token":"example-token"`, 1), "application/json", 400},
					bodyCase{"missing token", `{"email":"alice@example.test"}`, "application/json", 400},
				)
			}
			for _, tc := range cases {
				t.Run(tc.name, func(t *testing.T) {
					before := tokens.issues.Load() + tokens.verifies.Load()
					status := postRequestBody(t, server, endpoint.path, tc.body, tc.contentType, false)
					if status != tc.status {
						t.Fatalf("status=%d, want %d", status, tc.status)
					}
					after := tokens.issues.Load() + tokens.verifies.Load()
					if tc.status >= 400 && after != before {
						t.Fatal("invalid body reached token service")
					}
				})
			}
		})
	}
}

func TestEmailEndpointsEnforceWholeBodyLimit(t *testing.T) {
	tokens := &requestBodyTokens{}
	server := httptest.NewServer(newRequestBodyRouter(tokens, 0))
	defer server.Close()
	for _, endpoint := range []struct {
		path, body string
		valid      int
	}{
		{"/api/auth/registration/email", `{"email":"alice@example.test"}`, http.StatusAccepted},
		{"/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}`, http.StatusOK},
	} {
		for _, chunked := range []bool{false, true} {
			body := endpoint.body + strings.Repeat(" ", maxVerificationBodyBytes-len(endpoint.body))
			if status := postRequestBody(t, server, endpoint.path, body, "application/json", chunked); status != endpoint.valid {
				t.Fatalf("%s exact limit, chunked=%t: status=%d", endpoint.path, chunked, status)
			}
			before := tokens.issues.Load() + tokens.verifies.Load()
			if status := postRequestBody(t, server, endpoint.path, body+" ", "application/json", chunked); status != http.StatusRequestEntityTooLarge {
				t.Fatalf("%s above limit, chunked=%t: status=%d", endpoint.path, chunked, status)
			}
			if tokens.issues.Load()+tokens.verifies.Load() != before {
				t.Fatal("oversized body reached token service")
			}
		}
	}
}

func TestEmailEndpointsTimeoutUnfinishedBody(t *testing.T) {
	tokens := &requestBodyTokens{}
	server := httptest.NewServer(newRequestBodyRouter(tokens, 150*time.Millisecond))
	defer server.Close()
	for _, endpoint := range []struct{ path, body string }{
		{"/api/auth/registration/email", `{"email":"alice@example.test"}`},
		{"/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}`},
	} {
		for _, chunked := range []bool{false, true} {
			connection, err := net.Dial("tcp", strings.TrimPrefix(server.URL, "http://"))
			if err != nil {
				t.Fatal(err)
			}
			_ = connection.SetDeadline(time.Now().Add(3 * time.Second))
			if chunked {
				_, err = fmt.Fprintf(connection, "POST %s HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n%x\r\n%s\r\n", endpoint.path, len(endpoint.body), endpoint.body)
			} else {
				_, err = fmt.Fprintf(connection, "POST %s HTTP/1.1\r\nHost: local\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s", endpoint.path, len(endpoint.body)+1, endpoint.body)
			}
			if err != nil {
				connection.Close()
				t.Fatal(err)
			}
			response, err := http.ReadResponse(bufio.NewReader(connection), nil)
			if err != nil {
				connection.Close()
				t.Fatal(err)
			}
			_ = response.Body.Close()
			_ = connection.Close()
			if response.StatusCode != http.StatusRequestTimeout {
				t.Fatalf("%s unfinished body, chunked=%t: status=%d", endpoint.path, chunked, response.StatusCode)
			}
		}
	}
	if tokens.issues.Load() != 0 || tokens.verifies.Load() != 0 {
		t.Fatal("unfinished body reached token service")
	}
}

func TestEmailReadDeadlineDoesNotCancelBusinessWork(t *testing.T) {
	server := httptest.NewServer(newRequestBodyRouter(&slowRequestBodyTokens{}, 150*time.Millisecond))
	defer server.Close()
	for _, tc := range []struct {
		path, body string
		status     int
	}{
		{"/api/auth/registration/email", `{"email":"alice@example.test"}`, 202},
		{"/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}`, 200},
	} {
		if status := postRequestBody(t, server, tc.path, tc.body, "application/json", false); status != tc.status {
			t.Fatalf("%s completed body but delayed business work returned %d", tc.path, status)
		}
	}
}

func TestFinalRegistrationStillRejectsAmbiguousBodies(t *testing.T) {
	users := &testUsers{}
	server := httptest.NewServer(NewRouter(identity.NewRegisterService(users, &testRegistrationTokens{}, testMailer{}), false))
	defer server.Close()
	body := `{"username":"alice","password":"Password-2026"}`
	for _, tc := range []struct {
		body, contentType string
		status            int
	}{
		{`{"username":"alice","username":"other","password":"Password-2026"}`, "application/json", 400},
		{body + `{}`, "application/json", 400},
		{body + strings.Repeat(" ", maxRegisterBodyBytes), "application/json", 413},
		{body, "text/plain", 415},
	} {
		if status := postRequestBody(t, server, "/api/auth/register", tc.body, tc.contentType, false); status != tc.status {
			t.Fatalf("final registration status=%d, want %d", status, tc.status)
		}
	}
	if users.count != 0 {
		t.Fatal("invalid body reached user store")
	}
}

func TestEmailEndpointsHTTP2UnknownLength(t *testing.T) {
	tokens := &requestBodyTokens{}
	server := httptest.NewUnstartedServer(newRequestBodyRouter(tokens, 0))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	for _, tc := range []struct {
		path, body string
		status     int
	}{
		{"/api/auth/registration/email", `{"email":"alice@example.test"}`, 202},
		{"/api/auth/registration/email", `{"email":"alice@example.test"}{}`, 400},
		{"/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}`, 200},
		{"/api/auth/registration/verify", `{"email":"alice@example.test","token":"example-token"}{}`, 400},
	} {
		request, err := http.NewRequest(http.MethodPost, server.URL+tc.path, strings.NewReader(tc.body))
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
		if readErr != nil || response.ProtoMajor != 2 || response.StatusCode != tc.status {
			t.Fatalf("%s protocol=%s status=%d read error=%v", tc.path, response.Proto, response.StatusCode, readErr)
		}
	}
}
