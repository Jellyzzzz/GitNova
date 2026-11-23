package mysql

import (
	"context"
	"errors"
	"fmt"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"gorm.io/gorm"
)

type UserStore struct {
	db *gorm.DB
}

func NewUserStore(db *gorm.DB) *UserStore {
	return &UserStore{db: db}
}

func (s *UserStore) Create(ctx context.Context, user *identity.User) error {
	err := s.db.WithContext(ctx).Create(user).Error
	if err == nil {
		return nil
	}

	if errors.Is(err, gorm.ErrDuplicatedKey) {
		return s.classifyUserConflict(ctx, user)
	}
	// GORM can format the original operation error as text when adding a
	// rollback error. Preserve the request's cancellation alongside both.
	if ctxErr := ctx.Err(); ctxErr != nil {
		err = errors.Join(ctxErr, err)
	}
	return fmt.Errorf("create user: %w", err)
}

func (s *UserStore) classifyUserConflict(ctx context.Context, user *identity.User) error {
	var conflicts struct {
		UsernameExists bool
		EmailExists    bool
	}
	err := s.db.WithContext(ctx).Raw(`
		SELECT EXISTS(SELECT 1 FROM users WHERE username = ?) AS username_exists,
		       EXISTS(SELECT 1 FROM users WHERE email = ?) AS email_exists`,
		user.Username, user.Email,
	).Scan(&conflicts).Error
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			err = errors.Join(ctxErr, err)
		}
		return fmt.Errorf("classify user conflict: %w", err)
	}
	if conflicts.EmailExists {
		return identity.ErrEmailExists
	}
	if conflicts.UsernameExists {
		return identity.ErrUsernameExists
	}
	// A conflicting row may have disappeared before the follow-up query.
	return identity.ErrUserAlreadyExists
}

func (s *UserStore) FindUserByName(ctx context.Context, username string) (*identity.User, error) {
	var user identity.User
	if err := s.db.WithContext(ctx).Where("username = ?", username).First(&user).Error; err != nil {
		return nil, fmt.Errorf("find user by username: %w", err)
	}
	return &user, nil
}

func (s *UserStore) FindUserByEmail(ctx context.Context, email string) (*identity.User, error) {
	var user identity.User
	if err := s.db.WithContext(ctx).Where("email = ?", email).First(&user).Error; err != nil {
		return nil, fmt.Errorf("find user by email: %w", err)
	}
	return &user, nil
}

func (s *UserStore) FindUserByID(ctx context.Context, id int64) (*identity.User, error) {
	var user identity.User
	if err := s.db.WithContext(ctx).Where("id = ?", id).First(&user).Error; err != nil {
		return nil, fmt.Errorf("find user by id: %w", err)
	}
	return &user, nil
}
