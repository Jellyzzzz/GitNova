package identity

import "fmt"

var (
	ErrUsernameExists = fmt.Errorf("username already exists: %w", ErrUserAlreadyExists)
	ErrEmailExists    = fmt.Errorf("email already exists: %w", ErrUserAlreadyExists)
)
