package redisstore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/redis/go-redis/v9"
)

const webSessionTTL = 7 * 24 * time.Hour

// Atomically creates a session and updates its user index.
var createSessionScript = redis.NewScript(`
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[5])
redis.call('EXPIRE', KEYS[2], ARGV[2])
return 1
`)

// Revokes a session only when it belongs to the user.
var revokeSessionScript = redis.NewScript(`
if redis.call('ZSCORE', KEYS[2], ARGV[1]) == false then
    return 0
end

redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[1])

return 1
`)

// Explicit logout-all, not used when changing passwords.
var revokeAllSessionsScript = redis.NewScript(`
local ids = redis.call('ZRANGE', KEYS[1], 0, -1)

for _, id in ipairs(ids) do
    redis.call('DEL', 'session:' .. id)
end

redis.call('DEL', KEYS[1])

return #ids
`)

type SessionStore struct {
	client *redis.Client
}

func NewSessionStore(client *redis.Client) *SessionStore {
	return &SessionStore{client: client}
}

func sessionKey(id string) string {
	return "session:" + id
}
func userSessionKey(id int64) string {
	return fmt.Sprintf("user:sessions:%d", id)
}

// Create persists the Session and maintains the user's ZSET.
func (s *SessionStore) Create(ctx context.Context, session identity.WebSession) error {
	if session.UserID <= 0 || session.ID == "" {
		return errors.New("invalid session")
	}
	data, err := json.Marshal(session)
	if err != nil {
		return err
	}

	_, err = createSessionScript.Run(
		ctx,
		s.client,
		[]string{
			sessionKey(session.ID),
			userSessionKey(session.UserID),
		},
		string(data),
		int(webSessionTTL.Seconds()),
		session.ExpiresAt.UnixMilli(),
		session.ID,
		time.Now().UnixMilli(),
	).Int()
	if err != nil {
		return fmt.Errorf("create web session: %w", err)
	}

	return nil

}

// Read finds an individual Session by its hashed ID.
func (s *SessionStore) Read(ctx context.Context, id string) (identity.WebSession, error) {
	var session identity.WebSession

	data, err := s.client.Get(ctx, sessionKey(id)).Bytes()
	if errors.Is(err, redis.Nil) {
		return session, identity.ErrSessionNotFound
	}

	if err != nil {
		return session, fmt.Errorf("read web session: %w", err)
	}

	if err := json.Unmarshal(data, &session); err != nil {
		return session, fmt.Errorf("decode web session: %w", err)
	}
	return session, nil
}

func (s *SessionStore) List(
	ctx context.Context,
	userID int64,
) ([]identity.WebSession, error) {
	idx := userSessionKey(userID)
	cutoff := time.Now().UnixMilli()

	ids, err := s.client.ZRangeByScore(
		ctx,
		idx,
		&redis.ZRangeBy{
			Min: strconv.FormatInt(cutoff+1, 10),
			Max: "+inf",
		},
	).Result()

	if err != nil {
		return nil, fmt.Errorf("list session index: %w", err)
	}

	if len(ids) == 0 {
		return []identity.WebSession{}, nil
	}

	keys := make([]string, len(ids))

	for i, id := range ids {
		keys[i] = sessionKey(id)
	}

	values, err := s.client.MGet(
		ctx,
		keys...,
	).Result()

	if err != nil {
		return nil, fmt.Errorf("read session list: %w", err)
	}

	out := make([]identity.WebSession, 0, len(values))

	for i, value := range values {
		if value == nil {
			continue
		}

		raw, ok := value.(string)
		if !ok {
			continue
		}

		var item identity.WebSession

		if err := json.Unmarshal(
			[]byte(raw),
			&item,
		); err != nil {
			return nil, fmt.Errorf(
				"decode indexed web session: %w",
				err,
			)
		}

		if item.UserID == userID &&
			item.ID == ids[i] &&
			time.Now().Before(item.ExpiresAt) {
			out = append(out, item)
		}
	}

	return out, nil
}

// Revoke removes one Session if it belongs to the given user.
func (s *SessionStore) Revoke(
	ctx context.Context,
	userID int64,
	id string,
) (bool, error) {
	n, err := revokeSessionScript.Run(
		ctx,
		s.client,
		[]string{
			sessionKey(id),
			userSessionKey(userID),
		},
		id,
	).Int()

	if err != nil {
		return false, fmt.Errorf("revoke web session: %w", err)
	}

	return n == 1, nil
}

// RevokeAll is called by explicit logout-all operations.
func (s *SessionStore) RevokeAll(
	ctx context.Context,
	userID int64,
) error {
	if _, err := revokeAllSessionsScript.Run(
		ctx,
		s.client,
		[]string{userSessionKey(userID)},
	).Int(); err != nil {
		return fmt.Errorf("revoke user sessions: %w", err)
	}

	return nil
}
