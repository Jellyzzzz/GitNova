package identity

import (
	"context"
	"errors"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type fakeStore struct {
	created *User
	err     error
}

func (s *fakeStore) Create(_ context.Context, u *User) error {
	if s.err != nil {
		return s.err
	}
	u.ID = 42
	s.created = u
	return nil
}

type fakeTokens struct {
	proof   RegistrationProof
	deleted bool
}

func (f *fakeTokens) Issue(context.Context, string) (string, error) { return "secret-email-token", nil }
func (f *fakeTokens) Verify(context.Context, string, string) (string, error) {
	return "verified-ticket", nil
}
func (f *fakeTokens) Read(_ context.Context, ticket string) (RegistrationProof, error) {
	if ticket != "verified-ticket" || f.deleted {
		return RegistrationProof{}, ErrInvalidTicket
	}
	return f.proof, nil
}
func (f *fakeTokens) Delete(_ context.Context, _ string) error {
	f.deleted = true
	return nil
}

type fakeMailer struct{ to, token string }

func (m *fakeMailer) SendVerification(_ context.Context, email, token string) error {
	m.to, m.token = email, token
	return nil
}

func TestRegistrationRequiresProof(t *testing.T) {
	ctx := context.Background()
	store := &fakeStore{}
	now := time.Now().UTC()
	tokens := &fakeTokens{proof: RegistrationProof{Email: "alice@example.test", VerifiedAt: now, ExpiresAt: now.Add(time.Minute)}}
	mailer := &fakeMailer{}
	svc := NewRegisterService(store, tokens, mailer)

	if err := svc.RequestEmail(ctx, " Alice@Example.Test "); err != nil {
		t.Fatal(err)
	}
	if mailer.to != "alice@example.test" || mailer.token == "" || store.created != nil {
		t.Fatal("requesting verification must not create a user")
	}
	if _, err := svc.Register(ctx, "", RegisterInput{Username: "alice", Password: "Password-2026"}); !errors.Is(err, ErrInvalidTicket) {
		t.Fatalf("register without ticket = %v", err)
	}
	result, err := svc.Register(ctx, "verified-ticket", RegisterInput{Username: "alice", Password: "Password-2026"})
	if err != nil {
		t.Fatal(err)
	}
	if result.ID != 42 || store.created.Email != "alice@example.test" || store.created.EmailVerifiedAt == nil || !store.created.EmailVerifiedAt.Equal(now) {
		t.Fatal("email and verification time must come from Redis proof")
	}
	if err := bcrypt.CompareHashAndPassword([]byte(store.created.PasswordHash), []byte("Password-2026")); err != nil {
		t.Fatal(err)
	}
	if !tokens.deleted {
		t.Fatal("successful registration should remove ticket")
	}
}

func TestRegistrationRejectsInvalidInput(t *testing.T) {
	ctx := context.Background()
	svc := NewRegisterService(&fakeStore{}, &fakeTokens{}, &fakeMailer{})
	if _, err := svc.Register(ctx, "verified-ticket", RegisterInput{Username: "../", Password: "Password-2026"}); !errors.Is(err, ErrInvalidRegistration) {
		t.Fatalf("invalid username = %v", err)
	}
	if _, err := svc.Register(ctx, "verified-ticket", RegisterInput{Username: "alice", Password: "short"}); !errors.Is(err, ErrInvalidRegistration) {
		t.Fatalf("invalid password = %v", err)
	}
}

func TestRegistrationRetainsTicketOnDatabaseFailure(t *testing.T) {
	ctx := context.Background()
	tokens := &fakeTokens{proof: RegistrationProof{Email: "alice@example.test", VerifiedAt: time.Now(), ExpiresAt: time.Now().Add(time.Minute)}}
	svc := NewRegisterService(&fakeStore{err: ErrUserAlreadyExists}, tokens, &fakeMailer{})
	_, err := svc.Register(ctx, "verified-ticket", RegisterInput{Username: "alice", Password: "Password-2026"})
	if !errors.Is(err, ErrUserAlreadyExists) || tokens.deleted {
		t.Fatalf("error=%v; proof deleted=%v", err, tokens.deleted)
	}
}
