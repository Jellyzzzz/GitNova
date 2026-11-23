package identity

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

var (
	ErrInvalidRegistration = errors.New("invalid registration input")
	ErrUserAlreadyExists   = errors.New("user already exists")
	ErrInvalidVerification = errors.New("invalid or expired verification token")
	ErrInvalidTicket       = errors.New("invalid or expired registration ticket")
	ErrEmailCooldown       = errors.New("verification email requested too frequently")
)

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$`)

type UserStore interface {
	Create(ctx context.Context, user *User) error
}

type RegistrationProof struct {
	Email      string    `json:"email"`
	VerifiedAt time.Time `json:"verified_at"`
	ExpiresAt  time.Time `json:"expires_at"`
}

type RegistrationTokens interface {
	Issue(ctx context.Context, email string) (string, error)
	Verify(ctx context.Context, email, token string) (string, error)
	Read(ctx context.Context, ticket string) (RegistrationProof, error)
	Delete(ctx context.Context, ticket string) error
}

type VerificationMailer interface {
	SendVerification(ctx context.Context, email, token string) error
}

type RegisterInput struct {
	Username string
	Password string
}

type RegisterResult struct {
	ID          int64
	Username    string
	DisplayName string
	Email       string
}

type RegisterService struct {
	users  UserStore
	tokens RegistrationTokens
	mailer VerificationMailer
}

func NewRegisterService(users UserStore, tokens RegistrationTokens, mailer VerificationMailer) *RegisterService {
	return &RegisterService{users: users, tokens: tokens, mailer: mailer}
}

func NormalizeEmail(email string) (string, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	addr, err := mail.ParseAddress(email)
	if err != nil || addr.Address != email || len(email) > 254 {
		return "", ErrInvalidRegistration
	}
	return email, nil
}

// RequestEmail creates a short-lived challenge and sends its plaintext token.
func (s *RegisterService) RequestEmail(ctx context.Context, rawEmail string) error {
	email, err := NormalizeEmail(rawEmail)
	if err != nil {
		return err
	}
	token, err := s.tokens.Issue(ctx, email)
	if err != nil {
		return err
	}
	if err := s.mailer.SendVerification(ctx, email, token); err != nil {
		return fmt.Errorf("send verification email: %w", err)
	}
	return nil
}

// VerifyEmail trades a one-time email challenge for a registration ticket.
func (s *RegisterService) VerifyEmail(ctx context.Context, rawEmail, token string) (string, error) {
	email, err := NormalizeEmail(rawEmail)
	if err != nil {
		return "", ErrInvalidVerification
	}
	if len(token) == 0 || len(token) > 256 {
		return "", ErrInvalidVerification
	}
	return s.tokens.Verify(ctx, email, token)
}

func (s *RegisterService) Status(ctx context.Context, ticket string) (RegistrationProof, error) {
	return s.tokens.Read(ctx, ticket)
}

func (s *RegisterService) Register(ctx context.Context, ticket string, input RegisterInput) (RegisterResult, error) {
	if err := ctx.Err(); err != nil {
		return RegisterResult{}, err
	}
	username := strings.TrimSpace(input.Username)
	if !usernamePattern.MatchString(username) || isReservedUsername(username) ||
		utf8.RuneCountInString(input.Password) < 8 || len([]byte(input.Password)) > 72 {
		return RegisterResult{}, ErrInvalidRegistration
	}

	// The email is always sourced from Redis, never from client JSON.
	proof, err := s.tokens.Read(ctx, ticket)
	if err != nil {
		return RegisterResult{}, err
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(input.Password), bcrypt.DefaultCost)
	if err != nil {
		return RegisterResult{}, fmt.Errorf("hash password: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return RegisterResult{}, err
	}

	user := &User{
		Username: username, Email: proof.Email, PasswordHash: string(hash),
		DisplayName: username, EmailVerifiedAt: &proof.VerifiedAt,
	}
	if err := s.users.Create(ctx, user); err != nil {
		return RegisterResult{}, err
	}

	// A successful MySQL INSERT is authoritative. Cleanup is best-effort;
	// a surviving ticket cannot create a second account for the same email.
	if err := s.tokens.Delete(ctx, ticket); err != nil {
		log.Printf("registration ticket cleanup failed: %v", err)
	}
	return RegisterResult{
		ID: user.ID, Username: user.Username,
		DisplayName: user.DisplayName, Email: user.Email,
	}, nil
}

func isReservedUsername(username string) bool {
	switch strings.ToLower(username) {
	case "api", "git", "admin":
		return true
	default:
		return false
	}
}
