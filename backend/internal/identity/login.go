package identity

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrUnauthenticated    = errors.New("authentication required")
	ErrPasswordChanged    = errors.New("password changed; please sign in again")
	ErrUserNotFound       = errors.New("user not found")
	ErrInvalidPassword    = errors.New("invalid password")
	ErrSessionNotFound    = errors.New("session not found")
)

const sessionHours = 7 * 24 * time.Hour

type LoginUsers interface {
	FindUserByName(context.Context, string) (*User, error)
	FindUserByEmail(context.Context, string) (*User, error)
	FindUserByID(context.Context, int64) (*User, error)
	UpdatePasswordIfCurrent(
		ctx context.Context,
		userID int64,
		oldHash, newHash string,
	) (bool, error)
}
type WebSession struct {
	ID                  string    `json:"id"`
	UserID              int64     `json:"user_id"`
	PasswordFingerprint string    `json:"password_fingerprint"`
	UserAgent           string    `json:"user_agent"`
	CreatedAt           time.Time `json:"created_at"`
	ExpiresAt           time.Time `json:"expires_at"`
}
type WebSessionStore interface {
	Create(context.Context, WebSession) error
	Read(ctx context.Context, id string) (WebSession, error)
	List(ctx context.Context, userID int64) ([]WebSession, error)
	Revoke(ctx context.Context, userID int64, id string) (bool, error)
	RevokeAll(ctx context.Context, userID int64) error
}
type Authenticated struct {
	User    *User
	Session WebSession
}
type LoginService struct {
	users    LoginUsers
	sessions WebSessionStore
}

func NewLoginService(
	users LoginUsers,
	sessions WebSessionStore,
) *LoginService {
	return &LoginService{
		users:    users,
		sessions: sessions,
	}
}

// Records the password state when a session is created.
// Changing the password invalidates sessions with old fingerprints.
func sessionFingerprint(passwordHash string) string {
	digest := sha256.Sum256([]byte(passwordHash))
	return hex.EncodeToString(digest[:])
}

// Returns the raw token and its SHA-256 ID.
// Only the raw token is sent to the browser.
func sessionToken() (string, string, error) {
	data := make([]byte, 32)

	if _, err := rand.Read(data); err != nil {
		return "", "", fmt.Errorf("generate session token: %w", err)
	}

	token := base64.RawURLEncoding.EncodeToString(data)

	idBytes := sha256.Sum256([]byte(token))

	return token, hex.EncodeToString(idBytes[:]), nil
}

// Validates the format and calculates the token's Redis ID.
func sessionID(token string) (string, error) {
	if len(token) != 43 {
		return "", ErrUnauthenticated
	}

	data, err := base64.RawURLEncoding.DecodeString(token)

	if err != nil ||
		len(data) != 32 ||
		base64.RawURLEncoding.EncodeToString(data) != token {
		return "", ErrUnauthenticated
	}

	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:]), nil
}
func (s *LoginService) Login(ctx context.Context, identifier, password, userAgent string) (string, *User, error) {
	if err := ctx.Err(); err != nil {
		return "", nil, err
	}
	identifier = strings.TrimSpace(identifier)
	if identifier == "" ||
		password == "" ||
		len(identifier) > 254 ||
		len([]byte(password)) > 72 {
		return "", nil, ErrInvalidCredentials
	}

	var user *User
	var err error
	if strings.Contains(identifier, "@") {
		email, normalizeErr := NormalizeEmail(identifier)
		if normalizeErr != nil {
			return "", nil, ErrInvalidCredentials
		}
		user, err = s.users.FindUserByEmail(ctx, email)
	} else {
		user, err = s.users.FindUserByName(ctx, identifier)
	}
	if errors.Is(err, ErrUserNotFound) {
		return "", nil, ErrInvalidCredentials
	}
	if err != nil {
		return "", nil, fmt.Errorf("login user lookup: %w", err)
	}

	if bcrypt.CompareHashAndPassword(
		[]byte(user.PasswordHash),
		[]byte(password),
	) != nil {
		return "", nil, ErrInvalidCredentials
	}

	if user.DisabledAt != nil || user.EmailVerifiedAt == nil {
		return "", nil, ErrInvalidCredentials
	}

	token, id, err := sessionToken()
	now := time.Now().UTC()

	if len(userAgent) > 256 {
		userAgent = userAgent[:256]
	}

	session := WebSession{
		ID:                  id,
		UserID:              user.ID,
		PasswordFingerprint: sessionFingerprint(user.PasswordHash),
		UserAgent:           userAgent,
		CreatedAt:           now,
		ExpiresAt:           now.Add(sessionHours),
	}
	if err := s.sessions.Create(ctx, session); err != nil {
		return "", nil, fmt.Errorf("create session: %w", err)
	}
	return token, user, nil
}
func (s *LoginService) Authenticate(ctx context.Context, token string) (Authenticated, error) {
	id, err := sessionID(token)
	if err != nil {
		return Authenticated{}, ErrUnauthenticated
	}
	session, err := s.sessions.Read(ctx, id)
	if errors.Is(err, ErrSessionNotFound) {
		return Authenticated{}, ErrUnauthenticated
	}
	if err != nil {
		return Authenticated{}, fmt.Errorf("read session: %w", err)
	}
	if session.ID != id ||
		session.UserID <= 0 ||
		!time.Now().Before(session.ExpiresAt) {
		return Authenticated{}, ErrUnauthenticated
	}
	user, err := s.users.FindUserByID(ctx, session.UserID)

	if errors.Is(err, ErrUserNotFound) {
		return Authenticated{}, ErrUnauthenticated
	}
	if err != nil {
		return Authenticated{}, fmt.Errorf("load session user: %w", err)
	}

	if user.DisabledAt != nil || user.EmailVerifiedAt == nil {
		return Authenticated{}, ErrUnauthenticated
	}
	if session.PasswordFingerprint !=
		sessionFingerprint(user.PasswordHash) {
		return Authenticated{}, ErrPasswordChanged
	}
	return Authenticated{
		User:    user,
		Session: session,
	}, nil
}

func (s *LoginService) Logout(ctx context.Context, auth Authenticated) error {
	_, err := s.sessions.Revoke(ctx, auth.Session.UserID, auth.Session.ID)
	return err
}

func (s *LoginService) ListSessions(ctx context.Context, auth Authenticated) ([]WebSession, error) {
	sessions, err := s.sessions.List(ctx, auth.User.ID)
	if err != nil {
		return nil, err
	}
	fingerprint := sessionFingerprint(auth.User.PasswordHash)
	valid := make([]WebSession, 0, len(sessions))

	for _, item := range sessions {
		if item.PasswordFingerprint == fingerprint &&
			item.UserID == auth.User.ID &&
			time.Now().Before(item.ExpiresAt) {
			valid = append(valid, item)
		}
	}

	return valid, nil
}

func (s *LoginService) RevokeSession(ctx context.Context, auth Authenticated, targetID string) error {
	if len(targetID) != 64 {
		return ErrSessionNotFound
	}
	for _, ch := range targetID {
		if (ch < '0' || ch > '9') &&
			(ch < 'a' || ch > 'f') {
			return ErrSessionNotFound
		}
	}
	ok, err := s.sessions.Revoke(ctx, auth.Session.UserID, targetID)
	if err != nil {
		return err
	}
	if !ok {
		return ErrSessionNotFound
	}

	return nil
}

func (s *LoginService) LogoutAll(
	ctx context.Context,
	auth Authenticated,
) error {
	return s.sessions.RevokeAll(ctx, auth.User.ID)
}

// ChangePassword changes the MySQL password hash.
// Old sessions fail Authenticate() without a Redis bulk deletion.
func (s *LoginService) ChangePassword(ctx context.Context, auth Authenticated, current, next string) error {
	if len(current) == 0 ||
		len([]byte(current)) > 72 ||
		utf8.RuneCountInString(next) < 8 ||
		len([]byte(next)) > 72 {
		return ErrInvalidPassword
	}
	user, err := s.users.FindUserByID(ctx, auth.Session.UserID)

	if errors.Is(err, ErrUserNotFound) {
		return ErrUnauthenticated
	}
	if err != nil {
		return err
	}

	if user.DisabledAt != nil {
		return ErrUnauthenticated
	}
	// A concurrent password change may already have invalidated
	// the session.
	if sessionFingerprint(user.PasswordHash) !=
		auth.Session.PasswordFingerprint {
		return ErrPasswordChanged
	}
	if bcrypt.CompareHashAndPassword(
		[]byte(user.PasswordHash),
		[]byte(current),
	) != nil {
		return ErrInvalidCredentials
	}
	// Do not allow the new password to equal the old password.
	if bcrypt.CompareHashAndPassword(
		[]byte(user.PasswordHash),
		[]byte(next),
	) == nil {
		return ErrInvalidPassword
	}
	hash, err := bcrypt.GenerateFromPassword(
		[]byte(next),
		bcrypt.DefaultCost,
	)
	if err != nil {
		return fmt.Errorf("hash new password: %w", err)
	}
	ok, err := s.users.UpdatePasswordIfCurrent(
		ctx,
		user.ID,
		user.PasswordHash,
		string(hash),
	)
	if err != nil {
		return err
	}

	if !ok {
		return ErrPasswordChanged
	}

	// Existing sessions remain in Redis but are no longer
	// authorized because their password fingerprints differ.
	return nil
}
