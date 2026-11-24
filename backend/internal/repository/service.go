package repository

import (
	"context"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
)

type Store interface {
	Create(ctx context.Context, item *Repository) error
	FindByID(ctx context.Context, id string) (*Repository, error)
	FindByOwnerAndName(ctx context.Context, ownerID int64, name string) (*Repository, error)
	ListByOwner(ctx context.Context, ownerID int64) ([]Repository, error)
}
type GitStorage interface {
	InitBare(ctx context.Context, id string, DefaultBranch string) error
	RemoveBare(ctx context.Context, id string) error
}
type Service struct {
	repositories Store
	git          GitStorage
}

func NewService(repositories Store, git GitStorage) *Service {
	return &Service{repositories: repositories, git: git}
}

// Create initializes Git before inserting the business record. The single
// INSERT runs in MySQL's implicit transaction; no long-running DB transaction
// is held around a child process.
func (s *Service) Create(ctx context.Context, ownerID int64, input CreateInput) (Repository, error) {
	if err := ctx.Err(); err != nil {
		return Repository{}, err
	}
	name := strings.TrimSpace(input.Name)
	if input.Visibility == "" {
		input.Visibility = Public
	}
	if ownerID <= 0 || !validName(name) || utf8.RuneCountInString(input.Description) > 500 ||
		(input.Visibility != Public && input.Visibility != Private) {
		return Repository{}, ErrInvalidRepository
	}
	id, err := uuid.NewV7()
	if err != nil {
		return Repository{}, fmt.Errorf("generate repository id: %w", err)
	}
	item := Repository{
		ID:            id.String(),
		OwnerID:       ownerID,
		Name:          name,
		Description:   input.Description,
		Visibility:    input.Visibility,
		DefaultBranch: "main",
		CreatedAt:     time.Now().UTC(),
	}
	if err := s.git.InitBare(ctx, item.ID, item.DefaultBranch); err != nil {
		return Repository{}, fmt.Errorf("initialize bare repository: %w", err)
	}
	if err := s.repositories.Create(ctx, &item); err != nil {
		checkCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		persisted, checkErr := s.repositories.FindByID(checkCtx, item.ID)
		cancel()
		switch {
		case checkErr == nil && persisted != nil:
			// A UUID collision must never be treated as a successful create.
			if persisted.OwnerID == item.OwnerID && persisted.Name == item.Name {
				return *persisted, nil
			}
			return Repository{}, fmt.Errorf("repository ID collision %s; filesystem requires operator recovery: %w", item.ID, err)
		case errors.Is(checkErr, ErrNotFound):
			cleanupCtx, done := context.WithTimeout(context.Background(), 5*time.Second)
			cleanupErr := s.git.RemoveBare(cleanupCtx, item.ID)
			done()
			if cleanupErr != nil {
				return Repository{}, errors.Join(err, fmt.Errorf("cleanup bare repository: %w", cleanupErr))
			}
		default:
			log.Printf("repo create: insert failed; cannot confirm persistence of %s, leaving Git directory for recovery: %v", item.ID, checkErr)
			return Repository{}, fmt.Errorf("create repository %s: %w; persistence outcome unknown", item.ID, err)
		}
		return Repository{}, err
	}
	return item, nil
}
func (s *Service) ListMine(ctx context.Context, ownerID int64) ([]Repository, error) {
	if ownerID <= 0 {
		return nil, ErrInvalidRepository
	}
	return s.repositories.ListByOwner(ctx, ownerID)
}
func (s *Service) GetMine(ctx context.Context, ownerID int64, id string) (*Repository, error) {
	if ownerID <= 0 {
		return nil, ErrNotFound
	}
	parsed, err := uuid.Parse(id)
	if err != nil || parsed.Version() != 7 {
		return nil, ErrNotFound
	}
	item, err := s.repositories.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if item.OwnerID != ownerID {
		return nil, ErrNotFound
	}
	return item, nil
}
