package identity

import (
	"context"
	"errors"
	"fmt"
	"net/mail"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

var ErrInvalidRegistration = errors.New("invalid registration input")
var ErrUserAlreadyExists = errors.New("user already exists")

var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$`)

type UserStore interface {
	Create(ctx context.Context, user *User) error
}

type RegisterInput struct {
	Username string
	Email    string
	Password string
}
type RegisterResult struct {
	ID          int64
	Username    string
	DisplayName string
	Email       string
}
type RegisterService struct {
	users UserStore
}

func NewRegisterService(users UserStore) *RegisterService {
	return &RegisterService{
		users: users,
	}
}
func (s *RegisterService) Register(ctx context.Context, input RegisterInput) (RegisterResult, error) {
	if err := ctx.Err(); err != nil {
		return RegisterResult{}, err
	}
	username := strings.TrimSpace(input.Username)
	email := strings.ToLower(strings.TrimSpace(input.Email))

	addr, emailErr := mail.ParseAddress(email)
	if !usernamePattern.MatchString(username) ||
		isReservedUsername(username) ||
		len(email) > 254 ||
		emailErr != nil ||
		addr.Address != email ||
		utf8.RuneCountInString(input.Password) < 8 ||
		len([]byte(input.Password)) > 72 {
		return RegisterResult{}, ErrInvalidRegistration
	}
	hash, err := bcrypt.GenerateFromPassword(
		[]byte(input.Password),
		bcrypt.DefaultCost,
	)
	if err != nil {
		return RegisterResult{}, fmt.Errorf("hash password: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return RegisterResult{}, err
	}
	user := &User{
		Username:     username,
		Email:        email,
		PasswordHash: string(hash),
		DisplayName:  username,
	}
	if err := s.users.Create(ctx, user); err != nil {
		return RegisterResult{}, err
	}
	return RegisterResult{
		ID:          user.ID,
		Username:    user.Username,
		DisplayName: user.DisplayName,
		Email:       user.Email,
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
