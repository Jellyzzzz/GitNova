package identity

import "time"

type User struct {
	ID           int64 `gorm:"primaryKey;autoIncrement"`
	Username     string
	Email        string `json:"-"`
	PasswordHash string `json:"-"`

	DisplayName string
	AvatarURL   *string
	Bio         *string

	EmailVerifiedAt *time.Time `json:"-"`
	DisabledAt      *time.Time `json:"-"`
	CreatedAt       time.Time
}
