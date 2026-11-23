package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
)

type testUsers struct{ count int }

func (u *testUsers) Create(_ context.Context, user *identity.User) error {
	u.count++
	user.ID = 7
	return nil
}

type testRegistrationTokens struct{ deleted bool }

func (*testRegistrationTokens) Issue(context.Context, string) (string, error) {
	return "email-token", nil
}
func (*testRegistrationTokens) Verify(context.Context, string, string) (string, error) {
	return "ticket", nil
}
func (t *testRegistrationTokens) Read(_ context.Context, ticket string) (identity.RegistrationProof, error) {
	if ticket != "ticket" || t.deleted {
		return identity.RegistrationProof{}, identity.ErrInvalidTicket
	}
	now := time.Now()
	return identity.RegistrationProof{Email: "alice@example.test", VerifiedAt: now, ExpiresAt: now.Add(time.Minute)}, nil
}
func (t *testRegistrationTokens) Delete(context.Context, string) error { t.deleted = true; return nil }

type testMailer struct{}

func (testMailer) SendVerification(context.Context, string, string) error { return nil }

func TestRegisterNeedsVerifiedCookie(t *testing.T) {
	users := &testUsers{}
	tokens := &testRegistrationTokens{}
	server := httptest.NewServer(NewRouter(identity.NewRegisterService(users, tokens, testMailer{}), false))
	defer server.Close()
	payload := `{"username":"alice","password":"Password-2026"}`
	send := func(cookie bool) (int, []byte) {
		t.Helper()
		req, err := http.NewRequest(http.MethodPost, server.URL+"/api/auth/register", strings.NewReader(payload))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if cookie {
			req.AddCookie(&http.Cookie{Name: registrationCookie, Value: "ticket"})
		}
		res, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		body, err := io.ReadAll(res.Body)
		if err != nil {
			t.Fatal(err)
		}
		return res.StatusCode, body
	}
	if status, _ := send(false); status != 401 || users.count != 0 {
		t.Fatal("registration without proof must be denied")
	}
	if status, body := send(true); status != 201 || users.count != 1 {
		t.Fatalf("status=%d body=%s", status, body)
	}
	if status, _ := send(true); status != 401 || users.count != 1 {
		t.Fatal("reusing consumed ticket must fail")
	}
}

func TestRegistrationStatusSurvivesRefresh(t *testing.T) {
	tokens := &testRegistrationTokens{}
	server := httptest.NewServer(NewRouter(identity.NewRegisterService(&testUsers{}, tokens, testMailer{}), false))
	defer server.Close()
	req, _ := http.NewRequest(http.MethodGet, server.URL+"/api/auth/registration/status", nil)
	req.AddCookie(&http.Cookie{Name: registrationCookie, Value: "ticket"})
	res, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var data struct {
		Verified bool `json:"email_verified"`
		Expires  int  `json:"expires_in"`
	}
	if err := json.NewDecoder(res.Body).Decode(&data); err != nil {
		t.Fatal(err)
	}
	if res.StatusCode != 200 || !data.Verified || data.Expires <= 0 {
		t.Fatalf("status=%d state=%+v", res.StatusCode, data)
	}
}
