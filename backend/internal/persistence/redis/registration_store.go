package redisstore

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/redis/go-redis/v9"
)

const (
	challengeTTL = 15 * time.Minute
	verifiedTTL  = 10 * time.Minute
	resendDelay  = 60 * time.Second
)

// Both the cooldown and challenge are created atomically.
var issueScript = redis.NewScript(`
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[2])
redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[3])
return 1
`)

// The pending token can only be exchanged once. The verified proof is a
// separate short-lived credential and survives page refreshes.
var verifyScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
redis.call('DEL', KEYS[1])
return 1
`)

type RegistrationStore struct {
	client *redis.Client
}

func NewRegistrationStore(client *redis.Client) *RegistrationStore {
	return &RegistrationStore{client: client}
}

func digest(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}

func randomToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("secure random token: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func pendingKey(email string) string {
	return "registration:pending:" + digest(email)
}
func cooldownKey(email string) string {
	return "registration:cooldown:" + digest(email)
}
func verifiedKey(ticket string) string {
	return "registration:verified:" + digest(ticket)
}

func (s *RegistrationStore) Issue(ctx context.Context, email string) (string, error) {
	token, err := randomToken()
	if err != nil {
		return "", err
	}
	ok, err := issueScript.Run(ctx, s.client,
		[]string{cooldownKey(email), pendingKey(email)},
		digest(token), int(resendDelay.Seconds()), int(challengeTTL.Seconds()),
	).Int()
	if err != nil {
		return "", fmt.Errorf("issue verification token: %w", err)
	}
	if ok == 0 {
		return "", identity.ErrEmailCooldown
	}
	return token, nil
}

func (s *RegistrationStore) Verify(ctx context.Context, email, token string) (string, error) {
	ticket, err := randomToken()
	if err != nil {
		return "", err
	}
	now := time.Now().UTC()
	proof := identity.RegistrationProof{
		Email: email, VerifiedAt: now, ExpiresAt: now.Add(verifiedTTL),
	}
	payload, err := json.Marshal(proof)
	if err != nil {
		return "", err
	}
	ok, err := verifyScript.Run(ctx, s.client,
		[]string{pendingKey(email), verifiedKey(ticket)},
		digest(token), string(payload), int(verifiedTTL.Seconds()),
	).Int()
	if err != nil {
		return "", fmt.Errorf("verify email token: %w", err)
	}
	if ok == 0 {
		return "", identity.ErrInvalidVerification
	}
	return ticket, nil
}

func (s *RegistrationStore) Read(ctx context.Context, ticket string) (identity.RegistrationProof, error) {
	if ticket == "" || len(ticket) > 256 {
		return identity.RegistrationProof{}, identity.ErrInvalidTicket
	}
	data, err := s.client.Get(ctx, verifiedKey(ticket)).Bytes()
	if err == redis.Nil {
		return identity.RegistrationProof{}, identity.ErrInvalidTicket
	}
	if err != nil {
		return identity.RegistrationProof{}, fmt.Errorf("read registration proof: %w", err)
	}
	var proof identity.RegistrationProof
	if err := json.Unmarshal(data, &proof); err != nil {
		return identity.RegistrationProof{}, fmt.Errorf("decode registration proof: %w", err)
	}
	if proof.Email == "" || !time.Now().Before(proof.ExpiresAt) {
		return identity.RegistrationProof{}, identity.ErrInvalidTicket
	}
	return proof, nil
}

func (s *RegistrationStore) Delete(ctx context.Context, ticket string) error {
	if ticket == "" {
		return nil
	}
	return s.client.Del(ctx, verifiedKey(ticket)).Err()
}
