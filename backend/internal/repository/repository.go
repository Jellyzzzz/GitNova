package repository

import (
	"errors"
	"regexp"
	"strings"
	"time"
)

type Visibility string

const (
	Public  Visibility = "public"
	Private Visibility = "private"
)

var (
	ErrInvalidRepository = errors.New("invalid repository input")
	ErrNameTaken         = errors.New("repository name already exists")
	ErrNotFound          = errors.New("repository not found")
)

// Deliberately conservative URL-safe names. Repository names retain case.
var namePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$`)

func validName(name string) bool {
	return namePattern.MatchString(name) &&
		!strings.HasSuffix(name, ".") &&
		!strings.HasSuffix(strings.ToLower(name), ".git")
}

type Repository struct {
	ID            string     `gorm:"type:char(36);primaryKey" json:"id"`
	OwnerID       int64      `gorm:"not null" json:"owner_id"`
	Name          string     `gorm:"not null" json:"name"`
	Description   string     `gorm:"not null" json:"description"`
	Visibility    Visibility `gorm:"not null" json:"visibility"`
	DefaultBranch string     `gorm:"not null" json:"default_branch"`
	CreatedAt     time.Time  `json:"created_at"`
}

func (Repository) TableName() string { return "repositories" }

type CreateInput struct {
	Name        string     `json:"name"`
	Description string     `json:"description"`
	Visibility  Visibility `json:"visibility"`
}
