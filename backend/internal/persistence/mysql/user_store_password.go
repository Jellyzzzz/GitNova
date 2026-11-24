package mysql

import (
	"context"
	"fmt"
)

// Compare-and-swap avoids overwriting a concurrent password change.
func (s *UserStore) UpdatePasswordIfCurrent(ctx context.Context, userID int64, oldHash, newHash string) (bool, error) {
	result := s.db.WithContext(ctx).Table("users").Where(
		"id = ? AND BINARY password_hash = ?", userID, oldHash,
	).Update("password_hash", newHash)
	if result.Error != nil {
		return false, fmt.Errorf("update password: %w", result.Error)
	}
	return result.RowsAffected == 1, nil
}
