package identity

import (
	"context"
	"errors"
	"strings"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

type registerStoreFunc func(context.Context, *User) error

func (fn registerStoreFunc) Create(ctx context.Context, user *User) error { return fn(ctx, user) }

func TestRegisterPreservesUsernameAndDisplayName(t *testing.T) {
	store := registerStoreFunc(func(ctx context.Context, user *User) error {
		if user.Username != "Jelly_User-42" || user.DisplayName != "Jelly_User-42" || user.Email != "jelly@example.test" {
			t.Fatal("stored username spelling or normalized email differs from input")
		}
		if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte("Register-Password-2026")); err != nil {
			t.Fatal("stored hash does not verify the supplied password")
		}
		user.ID = 42
		return nil
	})
	result, err := NewRegisterService(store).Register(context.Background(), RegisterInput{
		Username: "  Jelly_User-42  ", Email: "  Jelly@Example.Test  ", Password: "Register-Password-2026",
	})
	if err != nil {
		t.Fatal(err)
	}
	if result.ID != 42 || result.Username != "Jelly_User-42" || result.DisplayName != "Jelly_User-42" || result.Email != "jelly@example.test" {
		t.Fatal("response account identifiers differ from stored values")
	}
}

func TestRegisterRejectsUnsafeUsernamesBeforePersistence(t *testing.T) {
	for _, name := range []string{"", "ab", strings.Repeat("a", 65), "a/b", "a\\b", "../", "a\nb", "a\x00b", "a\u202eb", "中文名", "Kelvin", "résumé", "re\u0301sume\u0301", "-alice", "_alice", "alice smith", "API", "Git", "AdMiN"} {
		t.Run(name, func(t *testing.T) {
			service := NewRegisterService(registerStoreFunc(func(context.Context, *User) error {
				t.Fatal("invalid username reached persistence")
				return nil
			}))
			_, err := service.Register(context.Background(), RegisterInput{Username: name, Email: "alice@example.test", Password: "Register-Password-2026"})
			if !errors.Is(err, ErrInvalidRegistration) {
				t.Fatalf("error=%v, want invalid registration", err)
			}
		})
	}
}

func TestRegisterUsernameLengthBoundaries(t *testing.T) {
	for _, name := range []string{"Ab1", "A" + strings.Repeat("b", 63), "jellyzzz", "JellyZZZ", "resume"} {
		service := NewRegisterService(registerStoreFunc(func(ctx context.Context, user *User) error {
			if user.Username != name || user.DisplayName != name {
				t.Fatal("username or display name lost the supplied spelling")
			}
			user.ID = 1
			return nil
		}))
		if _, err := service.Register(context.Background(), RegisterInput{Username: name, Email: "alice@example.test", Password: "Register-Password-2026"}); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRegisterCancellationAndStoreErrors(t *testing.T) {
	t.Run("already cancelled", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		service := NewRegisterService(registerStoreFunc(func(context.Context, *User) error {
			t.Fatal("cancelled registration reached persistence")
			return nil
		}))
		_, err := service.Register(ctx, RegisterInput{Username: "Alice", Email: "alice@example.test", Password: "Register-Password-2026"})
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("error=%v, want cancellation", err)
		}
	})
	t.Run("duplicate remains a business error", func(t *testing.T) {
		service := NewRegisterService(registerStoreFunc(func(context.Context, *User) error { return ErrUserAlreadyExists }))
		result, err := service.Register(context.Background(), RegisterInput{Username: "Alice", Email: "alice@example.test", Password: "Register-Password-2026"})
		if !errors.Is(err, ErrUserAlreadyExists) || result.ID != 0 {
			t.Fatalf("error=%v result ID=%d", err, result.ID)
		}
	})
}
