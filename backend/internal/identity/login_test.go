package identity

import (
	"context"
	"errors"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type loginTestUsers struct{ user *User }

func (f *loginTestUsers) FindUserByName(_ context.Context, name string) (*User, error) {
	if f.user.Username != name {
		return nil, ErrUserNotFound
	}
	return f.user, nil
}
func (f *loginTestUsers) FindUserByEmail(_ context.Context, email string) (*User, error) {
	if f.user.Email != email {
		return nil, ErrUserNotFound
	}
	return f.user, nil
}
func (f *loginTestUsers) FindUserByID(_ context.Context, id int64) (*User, error) {
	if f.user.ID != id {
		return nil, ErrUserNotFound
	}
	return f.user, nil
}
func (f *loginTestUsers) UpdatePasswordIfCurrent(_ context.Context, id int64, oldHash, newHash string) (bool, error) {
	if f.user.ID != id || f.user.PasswordHash != oldHash {
		return false, nil
	}
	f.user.PasswordHash = newHash
	return true, nil
}

type loginTestSessions struct{ values map[string]WebSession }

func (f *loginTestSessions) Create(_ context.Context, s WebSession) error {
	f.values[s.ID] = s
	return nil
}
func (f *loginTestSessions) Read(_ context.Context, id string) (WebSession, error) {
	s, ok := f.values[id]
	if !ok {
		return WebSession{}, ErrSessionNotFound
	}
	return s, nil
}
func (f *loginTestSessions) List(_ context.Context, userID int64) ([]WebSession, error) {
	out := []WebSession{}
	for _, s := range f.values {
		if s.UserID == userID {
			out = append(out, s)
		}
	}
	return out, nil
}
func (f *loginTestSessions) Revoke(_ context.Context, userID int64, id string) (bool, error) {
	s, ok := f.values[id]
	if !ok || s.UserID != userID {
		return false, nil
	}
	delete(f.values, id)
	return true, nil
}
func (f *loginTestSessions) RevokeAll(_ context.Context, userID int64) error {
	for id, s := range f.values {
		if s.UserID == userID {
			delete(f.values, id)
		}
	}
	return nil
}

func newTestLogin(t *testing.T) (*LoginService, *loginTestUsers, *loginTestSessions) {
	t.Helper()
	passwordHash, err := bcrypt.GenerateFromPassword([]byte("OldPassword-2026"), bcrypt.MinCost)
	if err != nil {
		t.Fatal(err)
	}
	verified := time.Now().UTC()
	users := &loginTestUsers{user: &User{ID: 99, Username: "alice", Email: "alice@example.test", PasswordHash: string(passwordHash), EmailVerifiedAt: &verified}}
	sessions := &loginTestSessions{values: map[string]WebSession{}}
	return NewLoginService(users, sessions), users, sessions
}

func TestLoginAndPasswordChanged(t *testing.T) {
	ctx := context.Background()
	service, users, sessions := newTestLogin(t)
	if _, _, err := service.Login(ctx, "alice", "wrong", "Firefox"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("bad password: %v", err)
	}
	if len(sessions.values) != 0 {
		t.Fatal("invalid login created session")
	}
	token, _, err := service.Login(ctx, " ALICE@example.test ", "OldPassword-2026", "Firefox")
	if err != nil {
		t.Fatal(err)
	}
	auth, err := service.Authenticate(ctx, token)
	if err != nil || auth.User.ID != users.user.ID {
		t.Fatalf("authentication err=%v", err)
	}
	if err := service.ChangePassword(ctx, auth, "OldPassword-2026", "NewPassword-2026"); err != nil {
		t.Fatal(err)
	}
	// Password change does not physically DEL every Redis session.
	if len(sessions.values) != 1 {
		t.Fatal("password change unexpectedly deleted session")
	}
	if _, err := service.Authenticate(ctx, token); !errors.Is(err, ErrPasswordChanged) {
		t.Fatalf("old session: %v", err)
	}
	if _, _, err := service.Login(ctx, "alice", "OldPassword-2026", ""); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("old password accepted: %v", err)
	}
	fresh, _, err := service.Login(ctx, "alice", "NewPassword-2026", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authenticate(ctx, fresh); err != nil {
		t.Fatal(err)
	}
}

func TestMultipleSessionsAndRevocation(t *testing.T) {
	ctx := context.Background()
	service, _, sessions := newTestLogin(t)
	token1, _, err := service.Login(ctx, "alice", "OldPassword-2026", "Mac")
	if err != nil {
		t.Fatal(err)
	}
	token2, _, err := service.Login(ctx, "alice", "OldPassword-2026", "Windows")
	if err != nil {
		t.Fatal(err)
	}
	auth1, err := service.Authenticate(ctx, token1)
	if err != nil {
		t.Fatal(err)
	}
	list, err := service.ListSessions(ctx, auth1)
	if err != nil || len(list) != 2 {
		t.Fatalf("list: %v %#v", err, list)
	}
	var otherID string
	for _, v := range list {
		if v.ID != auth1.Session.ID {
			otherID = v.ID
		}
	}
	if err := service.RevokeSession(ctx, auth1, otherID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Authenticate(ctx, token2); !errors.Is(err, ErrUnauthenticated) {
		t.Fatalf("revoked token: %v", err)
	}
	if _, err := service.Authenticate(ctx, token1); err != nil {
		t.Fatal(err)
	}
	if err := service.LogoutAll(ctx, auth1); err != nil {
		t.Fatal(err)
	}
	if len(sessions.values) != 0 {
		t.Fatal("logout-all left records")
	}
	if _, err := service.Authenticate(ctx, token1); !errors.Is(err, ErrUnauthenticated) {
		t.Fatalf("logout-all: %v", err)
	}
}

func TestDisabledAccountStopsExistingSession(t *testing.T) {
	ctx := context.Background()
	service, users, _ := newTestLogin(t)
	token, _, err := service.Login(ctx, "alice", "OldPassword-2026", "")
	if err != nil {
		t.Fatal(err)
	}
	disabled := time.Now()
	users.user.DisabledAt = &disabled
	if _, err := service.Authenticate(ctx, token); !errors.Is(err, ErrUnauthenticated) {
		t.Fatalf("disabled user authenticated: %v", err)
	}
}
