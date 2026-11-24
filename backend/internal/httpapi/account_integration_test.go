package httpapi_test

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"golang.org/x/crypto/bcrypt"
	"gorm.io/gorm"
)

const accountSessionCookie = "gitnova_session"

type accountSessionView struct {
	ID        string `json:"id"`
	Current   bool   `json:"current"`
	UserAgent string `json:"user_agent"`
}

func accountRegister(t *testing.T, f *registrationIntegration, username, email string) int64 {
	t.Helper()
	client, _ := f.verifiedClient(t, email)
	response := f.mustCall(t, client, http.MethodPost, "/api/auth/register", map[string]string{
		"username": username, "password": integrationPassword,
	}, http.StatusCreated)
	return response.body.ID
}

func accountLogin(t *testing.T, f *registrationIntegration, identifier, password string) (*http.Client, string) {
	t.Helper()
	client := f.client(t)
	response := f.mustCall(t, client, http.MethodPost, "/api/auth/login", map[string]string{
		"identifier": identifier, "password": password,
	}, http.StatusOK)
	if len(response.cookies) != 1 || !response.cookies[0].HttpOnly || response.cookies[0].SameSite != http.SameSiteStrictMode || response.cookies[0].Path != "/api" {
		t.Fatal("login did not set expected session cookie")
	}
	for _, cookie := range client.Jar.Cookies(f.url) {
		if cookie.Name == accountSessionCookie {
			return client, cookie.Value
		}
	}
	t.Fatal("HTTP cookie jar did not retain login session")
	return nil, ""
}

func accountReplay(t *testing.T, f *registrationIntegration, token string) *http.Client {
	t.Helper()
	client := f.client(t)
	client.Jar.SetCookies(f.url, []*http.Cookie{{Name: accountSessionCookie, Value: token, Path: "/api"}})
	return client
}

func accountSessionID(token string) string {
	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:])
}

func accountSessionList(t *testing.T, f *registrationIntegration, client *http.Client) []accountSessionView {
	t.Helper()
	response := f.mustCall(t, client, http.MethodGet, "/api/auth/sessions", nil, http.StatusOK)
	var payload struct {
		Sessions []accountSessionView `json:"sessions"`
	}
	if err := json.Unmarshal(response.raw, &payload); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(response.raw, []byte("password_fingerprint")) || bytes.Contains(response.raw, []byte("password_hash")) {
		t.Fatal("session list leaked password state")
	}
	return payload.Sessions
}

// Real HTTP, MySQL, Redis, bcrypt, and production handlers/stores are used.
// SMTP alone is captured; test data and Redis processes are isolated.
func TestAccountIntegration(t *testing.T) {
	f := newRegistrationIntegration(t)
	ctx := context.Background()

	t.Run("registration login and password change revoke every old session", func(t *testing.T) {
		id := accountRegister(t, f, "PasswordOwner", "password-owner@example.test")
		first, firstToken := accountLogin(t, f, "PasswordOwner", integrationPassword)
		second, secondToken := accountLogin(t, f, "password-owner@example.test", integrationPassword)
		me := f.mustCall(t, first, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
		if me.body.ID != id || me.body.Email != "password-owner@example.test" {
			t.Fatal("login returned another user's identity")
		}
		list := accountSessionList(t, f, first)
		current := 0
		for _, session := range list {
			if session.Current {
				current++
				if session.ID != accountSessionID(firstToken) {
					t.Fatal("current session ID did not match hashed token")
				}
			}
		}
		if len(list) != 2 || current != 1 {
			t.Fatalf("multi-device session list count=%d current=%d", len(list), current)
		}
		var before identity.User
		if err := f.db.First(&before, id).Error; err != nil {
			t.Fatal(err)
		}
		wrong := f.mustCall(t, first, http.MethodPost, "/api/auth/password", map[string]string{
			"current_password": "wrong-current-password", "new_password": "Changed-Password-2026",
		}, http.StatusBadRequest)
		if wrong.body.Error != "incorrect_current_password" {
			t.Fatal("wrong current password was misclassified")
		}
		f.mustCall(t, first, http.MethodPost, "/api/auth/password", map[string]string{
			"current_password": integrationPassword, "new_password": integrationPassword,
		}, http.StatusBadRequest)
		var unchanged identity.User
		if err := f.db.First(&unchanged, id).Error; err != nil || before.PasswordHash != unchanged.PasswordHash {
			t.Fatal("rejected change modified stored password")
		}
		f.mustCall(t, second, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
		changed := f.mustCall(t, first, http.MethodPost, "/api/auth/password", map[string]string{
			"current_password": integrationPassword, "new_password": "Changed-Password-2026",
		}, http.StatusOK)
		if len(changed.cookies) != 1 || changed.cookies[0].MaxAge >= 0 {
			t.Fatal("password change did not clear browser session cookie")
		}
		for _, token := range []string{firstToken, secondToken} {
			old := f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
			if old.body.Error != "password_changed" {
				t.Fatal("old session was not rejected for its password fingerprint")
			}
		}
		f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
			"identifier": "PasswordOwner", "password": integrationPassword,
		}, http.StatusUnauthorized)
		fresh, _ := accountLogin(t, f, "PasswordOwner", "Changed-Password-2026")
		if sessions := accountSessionList(t, f, fresh); len(sessions) != 1 {
			t.Fatal("invalidated sessions remained in the visible session list")
		}
		t.Log("username/email login succeeded; rejected changes preserved state; old sessions from both devices rejected after password change")
	})

	t.Run("logout chosen session and logout all enforce ownership", func(t *testing.T) {
		accountRegister(t, f, "SessionOwner", "session-owner@example.test")
		first, firstToken := accountLogin(t, f, "SessionOwner", integrationPassword)
		second, secondToken := accountLogin(t, f, "SessionOwner", integrationPassword)
		accountRegister(t, f, "OtherOwner", "other-owner@example.test")
		other, otherToken := accountLogin(t, f, "OtherOwner", integrationPassword)
		f.mustCall(t, first, http.MethodDelete, "/api/auth/sessions/"+accountSessionID(otherToken), nil, http.StatusNotFound)
		f.mustCall(t, other, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
		f.mustCall(t, first, http.MethodDelete, "/api/auth/sessions/"+accountSessionID(secondToken), nil, http.StatusNoContent)
		f.mustCall(t, second, http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		f.mustCall(t, first, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
		f.mustCall(t, first, http.MethodPost, "/api/auth/logout", nil, http.StatusNoContent)
		f.mustCall(t, accountReplay(t, f, firstToken), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		a, aToken := accountLogin(t, f, "SessionOwner", integrationPassword)
		_, bToken := accountLogin(t, f, "SessionOwner", integrationPassword)
		f.mustCall(t, a, http.MethodPost, "/api/auth/logout-all", nil, http.StatusNoContent)
		for _, token := range []string{aToken, bToken} {
			f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
			if f.redis.Exists(ctx, "session:"+accountSessionID(token)).Val() != 0 {
				t.Fatal("explicit logout-all left a bearer session in Redis")
			}
		}
		f.mustCall(t, other, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
	})

	t.Run("concurrent password changes have one winner", func(t *testing.T) {
		id := accountRegister(t, f, "ConcurrentOwner", "concurrent-owner@example.test")
		const workers = 4
		clients := make([]*http.Client, workers)
		tokens := make([]string, workers)
		passwords := make([]string, workers)
		for i := range clients {
			clients[i], tokens[i] = accountLogin(t, f, "ConcurrentOwner", integrationPassword)
			passwords[i] = fmt.Sprintf("Concurrent-Password-%d-2026", i)
		}
		results := parallelRegistrationRequests(workers, func(i int) (integrationResponse, error) {
			return f.call(clients[i], http.MethodPost, "/api/auth/password", map[string]string{
				"current_password": integrationPassword, "new_password": passwords[i],
			})
		})
		wins := 0
		winner := -1
		for i, result := range results {
			if result.err != nil {
				t.Fatal(result.err)
			}
			if result.response.status == http.StatusOK {
				wins++
				winner = i
			} else if result.response.status != http.StatusUnauthorized || result.response.body.Error != "password_changed" {
				t.Fatalf("concurrent loser returned status=%d error=%s", result.response.status, result.response.body.Error)
			}
		}
		if wins != 1 {
			t.Fatalf("concurrent password updates had %d winners", wins)
		}
		var user identity.User
		if err := f.db.First(&user, id).Error; err != nil {
			t.Fatal(err)
		}
		for i, password := range passwords {
			valid := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)) == nil
			if valid != (i == winner) {
				t.Fatal("database password does not match the sole successful request")
			}
		}
		for _, token := range tokens {
			f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		}
		t.Log("4 real concurrent changes: one commit; three password_changed; every old session denied")
	})

	t.Run("disabled unverified and expired sessions cannot authenticate", func(t *testing.T) {
		id := accountRegister(t, f, "StateOwner", "state-owner@example.test")
		_, token := accountLogin(t, f, "StateOwner", integrationPassword)
		if err := f.db.Model(&identity.User{}).Where("id = ?", id).Update("disabled_at", time.Now().UTC()).Error; err != nil {
			t.Fatal(err)
		}
		f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
			"identifier": "StateOwner", "password": integrationPassword,
		}, http.StatusUnauthorized)
		if err := f.db.Model(&identity.User{}).Where("id = ?", id).Updates(map[string]any{"disabled_at": nil, "email_verified_at": nil}).Error; err != nil {
			t.Fatal(err)
		}
		f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
			"identifier": "StateOwner", "password": integrationPassword,
		}, http.StatusUnauthorized)
		if err := f.db.Model(&identity.User{}).Where("id = ?", id).Update("email_verified_at", time.Now().UTC()).Error; err != nil {
			t.Fatal(err)
		}
		expireIntegrationKey(t, f.redis, "session:"+accountSessionID(token))
		f.mustCall(t, accountReplay(t, f, token), http.MethodGet, "/api/auth/me", nil, http.StatusUnauthorized)
		fresh, _ := accountLogin(t, f, "StateOwner", integrationPassword)
		if sessions := accountSessionList(t, f, fresh); len(sessions) != 1 {
			t.Fatal("expired Redis entries appeared as active sessions")
		}
	})

	t.Run("login and password strict request body boundaries", func(t *testing.T) {
		accountRegister(t, f, "BodyOwner", "body-owner@example.test")
		client, _ := accountLogin(t, f, "BodyOwner", integrationPassword)
		before := f.redis.DBSize(ctx).Val()
		cases := []struct {
			path, body, contentType string
			status                  int
		}{
			{"/api/auth/login", `{"identifier":"BodyOwner","identifier":"OtherOwner","password":"wrong"}`, "application/json", http.StatusBadRequest},
			{"/api/auth/login", `{"identifier":"BodyOwner","password":"wrong"}{}`, "application/json", http.StatusBadRequest},
			{"/api/auth/login", `{"identifier":"BodyOwner","password":"wrong"}`, "text/plain", http.StatusUnsupportedMediaType},
			{"/api/auth/login", `{"identifier":"BodyOwner","password":"wrong"}` + strings.Repeat(" ", 4096), "application/json", http.StatusRequestEntityTooLarge},
			{"/api/auth/password", `{"current_password":"old","new_password":"new","new_password":"duplicate"}`, "application/json", http.StatusBadRequest},
			{"/api/auth/password", `{"current_password":"old","new_password":"new"}{}`, "application/json", http.StatusBadRequest},
		}
		for _, tc := range cases {
			response, err := f.callRaw(client, http.MethodPost, tc.path, []byte(tc.body), tc.contentType)
			if err != nil || response.status != tc.status {
				t.Fatalf("request boundary for %s: status=%d want=%d err=%v", tc.path, response.status, tc.status, err)
			}
		}
		if f.redis.DBSize(ctx).Val() != before {
			t.Fatal("invalid request bodies changed Redis state")
		}
		f.mustCall(t, client, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
	})

	t.Run("MySQL outage preserves cookie and recovers", func(t *testing.T) {
		accountRegister(t, f, "DatabaseOwner", "database-owner@example.test")
		client, _ := accountLogin(t, f, "DatabaseOwner", integrationPassword)
		if err := f.db.Exec("RENAME TABLE users TO users_outage").Error; err != nil {
			t.Fatal(err)
		}
		restored := false
		defer func() {
			if !restored {
				if err := f.db.Exec("RENAME TABLE users_outage TO users").Error; err != nil {
					t.Error(err)
				}
			}
		}()
		response := f.mustCall(t, client, http.MethodGet, "/api/auth/me", nil, http.StatusServiceUnavailable)
		if response.body.Error != "authentication_unavailable" || len(response.cookies) != 0 {
			t.Fatal("MySQL outage was misclassified as bad credentials or deleted the cookie")
		}
		f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
			"identifier": "DatabaseOwner", "password": integrationPassword,
		}, http.StatusServiceUnavailable)
		if err := f.db.Exec("RENAME TABLE users_outage TO users").Error; err != nil {
			t.Fatal(err)
		}
		restored = true
		f.mustCall(t, client, http.MethodGet, "/api/auth/me", nil, http.StatusOK)
	})

	t.Run("Redis outage does not authorize or update password", func(t *testing.T) {
		id := accountRegister(t, f, "RedisOwner", "redis-owner@example.test")
		client, _ := accountLogin(t, f, "RedisOwner", integrationPassword)
		var before identity.User
		if err := f.db.First(&before, id).Error; err != nil {
			t.Fatal(err)
		}
		f.stopRedis()
		response := f.mustCall(t, client, http.MethodGet, "/api/auth/me", nil, http.StatusServiceUnavailable)
		if response.body.Error != "authentication_unavailable" || len(response.cookies) != 0 {
			t.Fatal("Redis outage was misclassified as a revoked session")
		}
		f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
			"identifier": "RedisOwner", "password": integrationPassword,
		}, http.StatusServiceUnavailable)
		f.mustCall(t, client, http.MethodPost, "/api/auth/password", map[string]string{
			"current_password": integrationPassword, "new_password": "Redis-Outage-Password-2026",
		}, http.StatusServiceUnavailable)
		var after identity.User
		if err := f.db.First(&after, id).Error; err != nil || before.PasswordHash != after.PasswordHash {
			t.Fatal("password changed while authentication store was unavailable")
		}
	})
}

// Audit probes record observed behavior. Reproduced functional defects assert
// the desired behavior, so they fail until the production implementation is fixed.
func TestAccountIntegrationAudit(t *testing.T) {
	f := newRegistrationIntegration(t)
	ctx := context.Background()
	id := accountRegister(t, f, "AuditOwner", "audit-owner@example.test")
	client, token := accountLogin(t, f, "AuditOwner", integrationPassword)

	t.Run("repeated incorrect credentials", func(t *testing.T) {
		started := time.Now()
		for i := 0; i < 12; i++ {
			response := f.mustCall(t, f.client(t), http.MethodPost, "/api/auth/login", map[string]string{
				"identifier": "AuditOwner", "password": "incorrect-password",
			}, http.StatusUnauthorized)
			if response.body.Error != "invalid_credentials" {
				t.Fatal("unexpected error in repeated-login probe")
			}
		}
		t.Logf("AUDIT 12 consecutive wrong passwords: all 401, no 429, total=%s", time.Since(started))
	})

	t.Run("private response caching headers", func(t *testing.T) {
		for _, path := range []string{"/api/auth/me", "/api/auth/sessions"} {
			response := f.mustCall(t, client, http.MethodGet, path, nil, http.StatusOK)
			t.Logf("AUDIT %s: Cache-Control=%q Vary=%q", path, response.headers.Get("Cache-Control"), response.headers.Get("Vary"))
		}
	})

	t.Run("TLS termination at reverse proxy", func(t *testing.T) {
		target, err := url.Parse(f.server.URL)
		if err != nil {
			t.Fatal(err)
		}
		proxy := httputil.NewSingleHostReverseProxy(target)
		director := proxy.Director
		proxy.Director = func(request *http.Request) {
			director(request)
			request.Header.Set("X-Forwarded-Proto", "https")
		}
		front := httptest.NewTLSServer(proxy)
		defer front.Close()
		body := `{"identifier":"AuditOwner","password":"` + integrationPassword + `"}`
		request, err := http.NewRequest(http.MethodPost, front.URL+"/api/auth/login", strings.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Origin", front.URL)
		response, err := front.Client().Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var payload integrationPayload
		if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		t.Logf("AUDIT same-origin HTTPS -> HTTP backend with X-Forwarded-Proto=https: status=%d error=%q", response.StatusCode, payload.Error)
		if response.StatusCode != http.StatusOK {
			t.Fatalf("legitimate same-origin HTTPS login behind proxy: status=%d error=%q; want 200", response.StatusCode, payload.Error)
		}
	})

	t.Run("account disabled after middleware authentication", func(t *testing.T) {
		loaded := make(chan struct{})
		var once sync.Once
		const callbackName = "integration_audit:observe_authentication"
		if err := f.db.Callback().Query().After("gorm:query").Register(callbackName, func(tx *gorm.DB) {
			if tx.Statement.Table == "users" {
				once.Do(func() { close(loaded) })
			}
		}); err != nil {
			t.Fatal(err)
		}
		defer f.db.Callback().Query().Remove(callbackName)
		reader, writer := io.Pipe()
		defer reader.Close()
		defer writer.Close()
		request, err := http.NewRequest(http.MethodPost, f.server.URL+"/api/auth/password", reader)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Content-Type", "application/json")
		request.AddCookie(&http.Cookie{Name: accountSessionCookie, Value: token})
		type result struct {
			response *http.Response
			err      error
		}
		completed := make(chan result, 1)
		go func() {
			response, err := f.server.Client().Do(request)
			completed <- result{response, err}
		}()
		select {
		case <-loaded:
		case <-time.After(3 * time.Second):
			t.Fatal("request did not complete initial MySQL authentication lookup")
		}
		if err := f.db.Model(&identity.User{}).Where("id = ?", id).Update("disabled_at", time.Now().UTC()).Error; err != nil {
			t.Fatal(err)
		}
		defer f.db.Model(&identity.User{}).Where("id = ?", id).Update("disabled_at", nil)
		body := `{"current_password":"` + integrationPassword + `","new_password":"Audit-New-Password-2026"}`
		if _, err := io.WriteString(writer, body); err != nil {
			t.Fatal(err)
		}
		if err := writer.Close(); err != nil {
			t.Fatal(err)
		}
		var received result
		select {
		case received = <-completed:
		case <-time.After(5 * time.Second):
			t.Fatal("password request did not finish")
		}
		if received.err != nil {
			t.Fatal(received.err)
		}
		defer received.response.Body.Close()
		var payload integrationPayload
		if err := json.NewDecoder(received.response.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		var user identity.User
		if err := f.db.First(&user, id).Error; err != nil {
			t.Fatal(err)
		}
		if bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(integrationPassword)) != nil {
			t.Fatal("disabled user managed to update password")
		}
		t.Logf("AUDIT disabled after initial auth, before body completion: status=%d error=%q clear-cookie=%t; database unchanged", received.response.StatusCode, payload.Error, len(received.response.Cookies()) != 0)
		if received.response.StatusCode != http.StatusUnauthorized || payload.Error != "unauthorized" {
			t.Fatalf("account disabled during request: status=%d error=%q; want 401 unauthorized", received.response.StatusCode, payload.Error)
		}
		if cookies := received.response.Cookies(); len(cookies) != 1 || cookies[0].MaxAge >= 0 {
			t.Fatal("account disabled during request did not clear the session cookie")
		}
	})

	t.Run("session Redis expiry exists", func(t *testing.T) {
		if ttl := f.redis.TTL(ctx, "session:"+accountSessionID(token)).Val(); ttl <= 0 || ttl > 7*24*time.Hour {
			t.Fatalf("session Redis TTL=%s", ttl)
		}
	})
}
