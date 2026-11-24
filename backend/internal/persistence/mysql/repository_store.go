package mysql

import (
	"context"
	"errors"
	"fmt"

	"github.com/Jellyzzzz/GitNova/backend/internal/repository"
	"gorm.io/gorm"
)

type RepositoryStore struct {
	db *gorm.DB
}

func NewRepositoryStore(db *gorm.DB) *RepositoryStore {
	return &RepositoryStore{db: db}
}
func (s *RepositoryStore) Create(
	ctx context.Context,
	item *repository.Repository,
) error {
	err := s.db.WithContext(ctx).Create(item).Error

	if errors.Is(err, gorm.ErrDuplicatedKey) {
		// Duplicate key may mean a repeated repository name
		// or a collision of repository IDs.
		if _, lookupErr := s.FindByOwnerAndName(
			ctx,
			item.OwnerID,
			item.Name,
		); lookupErr == nil {
			return repository.ErrNameTaken
		}

		return fmt.Errorf(
			"duplicate repository key: %w",
			err,
		)
	}

	if err != nil {
		return fmt.Errorf("insert repository: %w", err)
	}

	return nil
}

func (s *RepositoryStore) FindByID(
	ctx context.Context,
	id string,
) (*repository.Repository, error) {
	var item repository.Repository

	err := s.db.WithContext(ctx).
		Where("id = ?", id).
		First(&item).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrNotFound
		}

		return nil, fmt.Errorf(
			"find repository by id: %w",
			err,
		)
	}

	return &item, nil
}

func (s *RepositoryStore) FindByOwnerAndName(
	ctx context.Context,
	ownerID int64,
	name string,
) (*repository.Repository, error) {
	var item repository.Repository

	err := s.db.WithContext(ctx).
		Where("owner_id = ? AND name = ?", ownerID, name).
		First(&item).Error

	if err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, repository.ErrNotFound
		}

		return nil, fmt.Errorf(
			"find repository by owner and name: %w",
			err,
		)
	}

	return &item, nil
}

func (s *RepositoryStore) ListByOwner(
	ctx context.Context,
	ownerID int64,
) ([]repository.Repository, error) {
	var items []repository.Repository

	err := s.db.WithContext(ctx).
		Where("owner_id = ?", ownerID).
		Order("created_at DESC").
		Find(&items).Error

	if err != nil {
		return nil, fmt.Errorf(
			"list repositories by owner: %w",
			err,
		)
	}

	return items, nil
}
