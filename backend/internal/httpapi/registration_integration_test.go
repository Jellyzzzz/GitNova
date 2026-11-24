package httpapi_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"golang.org/x/crypto/bcrypt"
)

// This suite creates/drops a random database and starts its own redis-server.
// The database selected in GITNOVA_INTEGRATION_MYSQL_DSN is never modified.
// Only email delivery is replaced with an in-memory capture; HTTP, stores,
// Redis scripts, MySQL constraints and password hashing use production code.
func TestRegistrationIntegration(t *testing.T) {
	f := newRegistrationIntegration(t)
	ctx := context.Background()

	t.Run("complete registration and cookie lifecycle", func(t *testing.T) {
		const email = "complete@example.test"
		client := f.client(t)
		input := map[string]string{"username": "Jelly_User", "password": integrationPassword}
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", input, http.StatusUnauthorized)
		token := f.requestToken(t, client, email)
		if f.emailRows(t, email) != 0 {
			t.Fatal("requesting email created a user before password setup")
		}
		pendingKey := registrationRedisKey("pending", email)
		stored, err := f.redis.Get(ctx, pendingKey).Result()
		if err != nil {
			t.Fatal(err)
		}
		digest := sha256.Sum256([]byte(token))
		if stored != hex.EncodeToString(digest[:]) {
			t.Fatal("Redis did not store the token digest")
		}
		if ttl := f.redis.PTTL(ctx, pendingKey).Val(); ttl <= 0 {
			t.Fatal("pending email token has no positive TTL")
		}
		mailCount := f.mail.sent()
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/email", map[string]string{"email": email}, http.StatusTooManyRequests)
		if f.mail.sent() != mailCount {
			t.Fatal("cooldown allowed another email")
		}
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": "incorrect"}, http.StatusBadRequest)
		verification := f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": token}, http.StatusOK)
		if !verification.body.EmailVerified {
			t.Fatal("successful verification was not reported")
		}
		if len(verification.cookies) != 1 || !verification.cookies[0].HttpOnly || verification.cookies[0].SameSite != http.SameSiteStrictMode || verification.cookies[0].MaxAge <= 0 {
			t.Fatal("verification response did not set the expected HttpOnly cookie")
		}
		ticket := f.ticket(t, client)
		proof, err := f.tokens.Read(ctx, ticket)
		if err != nil || proof.Email != email || proof.VerifiedAt.IsZero() {
			t.Fatalf("verified Redis proof is invalid: %v", err)
		}
		if f.redis.Exists(ctx, pendingKey).Val() != 0 || f.emailRows(t, email) != 0 {
			t.Fatal("verification did not consume pending token or prematurely created a user")
		}
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": token}, http.StatusBadRequest)
		key := registrationRedisKey("verified", ticket)
		before := f.redis.PTTL(ctx, key).Val()
		for i := 0; i < 2; i++ {
			state := f.mustCall(t, client, http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
			if !state.body.EmailVerified || state.body.Email != email || state.body.ExpiresIn <= 0 {
				t.Fatal("cookie did not restore verified registration state")
			}
		}
		if after := f.redis.PTTL(ctx, key).Val(); after <= 0 || after > before {
			t.Fatal("status refresh extended or lost ticket TTL")
		}
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", map[string]string{"username": "../", "password": integrationPassword}, http.StatusBadRequest)
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", map[string]string{"username": "Jelly_User", "password": integrationPassword, "email": "substitute@example.test"}, http.StatusBadRequest)
		f.mustCall(t, client, http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
		created := f.mustCall(t, client, http.MethodPost, "/api/auth/register", input, http.StatusCreated)
		var user identity.User
		if err := f.db.First(&user, created.body.ID).Error; err != nil {
			t.Fatal(err)
		}
		if user.Email != email || user.Username != "Jelly_User" || user.DisplayName != "Jelly_User" || !created.body.EmailVerified || created.body.Email != email || created.body.Username != user.Username || created.body.DisplayName != user.DisplayName || user.EmailVerifiedAt == nil || user.EmailVerifiedAt.Sub(proof.VerifiedAt).Abs() > time.Millisecond || f.emailRows(t, email) != 1 {
			t.Fatal("created user does not match the verified proof and supplied username")
		}
		if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(integrationPassword)); err != nil {
			t.Fatal("stored password hash does not verify")
		}
		var responseFields map[string]json.RawMessage
		if err := json.Unmarshal(created.raw, &responseFields); err != nil {
			t.Fatal(err)
		}
		for _, field := range []string{"password", "password_hash", "PasswordHash"} {
			if _, found := responseFields[field]; found {
				t.Fatal("registration response exposes a password field")
			}
		}
		if f.redis.Exists(ctx, key).Val() != 0 {
			t.Fatal("successful registration left a Redis ticket")
		}
		if len(created.cookies) != 1 || created.cookies[0].MaxAge >= 0 {
			t.Fatal("successful registration did not clear its cookie")
		}
		f.mustCall(t, client, http.MethodGet, "/api/auth/registration/status", nil, http.StatusUnauthorized)
		staleClient := f.client(t)
		staleClient.Jar.SetCookies(f.url, []*http.Cookie{{Name: integrationCookie, Value: ticket, Path: "/api/auth"}})
		f.mustCall(t, staleClient, http.MethodPost, "/api/auth/register", input, http.StatusUnauthorized)
	})

	t.Run("conflict classification and retry", func(t *testing.T) {
		const email = "owner@example.test"
		owner, _ := f.verifiedClient(t, email)
		created := f.mustCall(t, owner, http.MethodPost, "/api/auth/register", map[string]string{"username": "ConflictOwner", "password": integrationPassword}, http.StatusCreated)
		var original identity.User
		if err := f.db.First(&original, created.body.ID).Error; err != nil {
			t.Fatal(err)
		}
		other, _ := f.verifiedClient(t, "other@example.test")
		conflict := f.mustCall(t, other, http.MethodPost, "/api/auth/register", map[string]string{"username": "ConflictOwner", "password": integrationPassword}, http.StatusConflict)
		if conflict.body.Error != "username already exists" {
			t.Fatal("username conflict was not classified correctly")
		}
		f.mustCall(t, other, http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
		f.mustCall(t, other, http.MethodPost, "/api/auth/register", map[string]string{"username": "conflictowner", "password": integrationPassword}, http.StatusCreated)
		// Accelerate this test's cooldown instead of waiting the full minute.
		expireIntegrationKey(t, f.redis, registrationRedisKey("cooldown", email))
		repeated, _ := f.verifiedClient(t, email)
		for _, username := range []string{"AvailableName", "ConflictOwner"} {
			conflict := f.mustCall(t, repeated, http.MethodPost, "/api/auth/register", map[string]string{"username": username, "password": "Different-Password-2026"}, http.StatusConflict)
			if conflict.body.Error != "email already exists" {
				t.Fatal("email conflict did not take priority over username conflict")
			}
		}
		f.mustCall(t, repeated, http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
		var unchanged identity.User
		if err := f.db.First(&unchanged, original.ID).Error; err != nil {
			t.Fatal(err)
		}
		if unchanged.PasswordHash != original.PasswordHash || f.emailRows(t, email) != 1 {
			t.Fatal("duplicate registration changed the existing account")
		}
	})

	t.Run("concurrent token exchange", func(t *testing.T) {
		const email = "token-race@example.test"
		token := f.requestToken(t, f.client(t), email)
		const workers = 4
		clients := make([]*http.Client, workers)
		for i := range clients {
			clients[i] = f.client(t)
		}
		results := parallelRegistrationRequests(workers, func(i int) (integrationResponse, error) {
			return f.call(clients[i], http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": token})
		})
		succeeded, rejected := 0, 0
		for i, result := range results {
			if result.err != nil {
				t.Fatal(result.err)
			}
			switch result.response.status {
			case http.StatusOK:
				succeeded++
				f.mustCall(t, clients[i], http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
			case http.StatusBadRequest:
				rejected++
				if len(clients[i].Jar.Cookies(f.url)) != 0 {
					t.Fatal("failed concurrent verification received a ticket cookie")
				}
			default:
				t.Fatalf("unexpected token exchange status=%d", result.response.status)
			}
		}
		if succeeded != 1 || rejected != workers-1 || f.emailRows(t, email) != 0 {
			t.Fatalf("token exchange successes=%d rejected=%d", succeeded, rejected)
		}
	})

	t.Run("concurrent final registration", func(t *testing.T) {
		const email = "register-race@example.test"
		_, ticket := f.verifiedClient(t, email)
		const workers = 4
		clients := make([]*http.Client, workers)
		for i := range clients {
			clients[i] = f.client(t)
			clients[i].Jar.SetCookies(f.url, []*http.Cookie{{Name: integrationCookie, Value: ticket, Path: "/api/auth"}})
		}
		results := parallelRegistrationRequests(workers, func(i int) (integrationResponse, error) {
			return f.call(clients[i], http.MethodPost, "/api/auth/register", map[string]string{"username": fmt.Sprintf("RaceUser%d", i), "password": integrationPassword})
		})
		succeeded := 0
		for _, result := range results {
			if result.err != nil {
				t.Fatal(result.err)
			}
			switch result.response.status {
			case http.StatusCreated:
				succeeded++
			case http.StatusConflict:
				if result.response.body.Error != "email already exists" {
					t.Fatal("concurrent email conflict misclassified")
				}
			case http.StatusUnauthorized:
				// The winning request may delete the ticket before a peer reads it.
			default:
				t.Fatalf("unexpected registration status=%d", result.response.status)
			}
		}
		if succeeded != 1 || f.emailRows(t, email) != 1 {
			t.Fatalf("concurrent registration successes=%d", succeeded)
		}
	})

	t.Run("expiry and resend lifecycle", func(t *testing.T) {
		client := f.client(t)
		const expiredEmail = "expired-token@example.test"
		token := f.requestToken(t, client, expiredEmail)
		expireIntegrationKey(t, f.redis, registrationRedisKey("pending", expiredEmail))
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": expiredEmail, "token": token}, http.StatusBadRequest)
		expiredClient, ticket := f.verifiedClient(t, "expired-ticket@example.test")
		expireIntegrationKey(t, f.redis, registrationRedisKey("verified", ticket))
		f.mustCall(t, expiredClient, http.MethodGet, "/api/auth/registration/status", nil, http.StatusUnauthorized)
		if len(expiredClient.Jar.Cookies(f.url)) != 0 {
			t.Fatal("expired ticket cookie was not cleared")
		}
		f.mustCall(t, expiredClient, http.MethodPost, "/api/auth/register", map[string]string{"username": "ExpiredUser", "password": integrationPassword}, http.StatusUnauthorized)
		if f.emailRows(t, "expired-ticket@example.test") != 0 {
			t.Fatal("expired ticket created an account")
		}
		const resentEmail = "resend@example.test"
		old := f.requestToken(t, client, resentEmail)
		expireIntegrationKey(t, f.redis, registrationRedisKey("cooldown", resentEmail))
		newToken := f.requestToken(t, client, resentEmail)
		if newToken == old {
			t.Fatal("resend reused the previous token")
		}
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": resentEmail, "token": old}, http.StatusBadRequest)
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": resentEmail, "token": newToken}, http.StatusOK)
	})

	t.Run("invalid bodies cause no Redis or mail side effects", func(t *testing.T) {
		client := f.client(t)
		beforeKeys, err := f.redis.DBSize(ctx).Result()
		if err != nil {
			t.Fatal(err)
		}
		beforeMail := f.mail.sent()
		for _, tc := range []struct {
			body, media string
			status      int
		}{
			{`{"email":"body@example.test","email":"override@example.test"}`, "application/json", 400},
			{`{"email":"body@example.test"}{}`, "application/json", 400},
			{`{"email":"body@example.test"}`, "text/plain", 415},
			{`{"email":"body@example.test"}` + strings.Repeat(" ", 4096), "application/json", 413},
		} {
			response, err := f.callRaw(client, http.MethodPost, "/api/auth/registration/email", []byte(tc.body), tc.media)
			if err != nil || response.status != tc.status {
				t.Fatalf("invalid request status=%d want=%d error=%v", response.status, tc.status, err)
			}
		}
		if f.mail.sent() != beforeMail || f.redis.DBSize(ctx).Val() != beforeKeys || f.emailRows(t, "body@example.test") != 0 {
			t.Fatal("invalid request changed registration state")
		}
		const email = "verify-body@example.test"
		token := f.requestToken(t, client, email)
		pendingKey := registrationRedisKey("pending", email)
		beforeToken, err := f.redis.Get(ctx, pendingKey).Result()
		if err != nil {
			t.Fatal(err)
		}
		body := fmt.Sprintf(`{"email":%q,"token":"wrong","token":%q}`, email, token)
		response, err := f.callRaw(client, http.MethodPost, "/api/auth/registration/verify", []byte(body), "application/json")
		if err != nil || response.status != http.StatusBadRequest {
			t.Fatalf("ambiguous verification body status=%d error=%v", response.status, err)
		}
		if afterToken, err := f.redis.Get(ctx, pendingKey).Result(); err != nil || afterToken != beforeToken || len(client.Jar.Cookies(f.url)) != 0 {
			t.Fatal("invalid verification body consumed a token or issued a cookie")
		}
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": token}, http.StatusOK)
	})

	t.Run("MySQL failure retains ticket for retry", func(t *testing.T) {
		const email = "db-retry@example.test"
		client, ticket := f.verifiedClient(t, email)
		if err := f.db.Exec("RENAME TABLE users TO integration_users_offline").Error; err != nil {
			t.Fatal(err)
		}
		renamed := true
		t.Cleanup(func() {
			if renamed {
				if err := f.db.Exec("RENAME TABLE integration_users_offline TO users").Error; err != nil {
					t.Errorf("restore temporary users table: %v", err)
				}
			}
		})
		input := map[string]string{"username": "DatabaseRetry", "password": integrationPassword}
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", input, http.StatusInternalServerError)
		if _, err := f.tokens.Read(ctx, ticket); err != nil {
			t.Fatal("database failure consumed registration ticket")
		}
		if err := f.db.Exec("RENAME TABLE integration_users_offline TO users").Error; err != nil {
			t.Fatal(err)
		}
		renamed = false
		f.mustCall(t, client, http.MethodGet, "/api/auth/registration/status", nil, http.StatusOK)
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", input, http.StatusCreated)
		if f.emailRows(t, email) != 1 {
			t.Fatal("database retry did not create exactly one account")
		}
	})

	// Keep outage testing last because it stops this suite's Redis process.
	t.Run("Redis outage is not reported as expired proof", func(t *testing.T) {
		const email = "redis-outage@example.test"
		client, _ := f.verifiedClient(t, email)
		f.stopRedis()
		f.mustCall(t, client, http.MethodGet, "/api/auth/registration/status", nil, http.StatusInternalServerError)
		f.mustCall(t, client, http.MethodPost, "/api/auth/register", map[string]string{"username": "RedisOutage", "password": integrationPassword}, http.StatusInternalServerError)
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": "unavailable"}, http.StatusInternalServerError)
		beforeMail := f.mail.sent()
		f.mustCall(t, client, http.MethodPost, "/api/auth/registration/email", map[string]string{"email": "redis-down@example.test"}, http.StatusInternalServerError)
		if f.mail.sent() != beforeMail || f.emailRows(t, email) != 0 {
			t.Fatal("Redis outage sent mail or created an unverified account")
		}
	})
}

type parallelRegistrationResult struct {
	response integrationResponse
	err      error
}

func parallelRegistrationRequests(workers int, request func(int) (integrationResponse, error)) []parallelRegistrationResult {
	start := make(chan struct{})
	results := make([]parallelRegistrationResult, workers)
	var wait sync.WaitGroup
	for i := range results {
		wait.Add(1)
		go func(i int) {
			defer wait.Done()
			<-start
			results[i].response, results[i].err = request(i)
		}(i)
	}
	close(start)
	wait.Wait()
	return results
}
