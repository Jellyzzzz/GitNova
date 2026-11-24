package gitstorage

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
)

type Local struct {
	root string
	git  string
}

func NewLocal(root, gitExecutable string) (*Local, error) {
	if root == "" || gitExecutable == "" {
		return nil, errors.New("repository root and git executable are required")
	}

	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}

	absolute, err = filepath.EvalSymlinks(absolute)
	if err != nil {
		return nil, fmt.Errorf("repository root: %w", err)
	}

	info, err := os.Stat(absolute)
	if err != nil || !info.IsDir() {
		return nil, errors.New("repository root must be an existing directory")
	}

	return &Local{
		root: absolute,
		git:  gitExecutable,
	}, nil
}
func (l *Local) repoPath(id string) (string, error) {
	parsed, err := uuid.Parse(id)
	if err != nil || parsed.Version() != 7 || parsed.String() != id {
		return "", errors.New("invalid repository UUIDv7")
	}

	return filepath.Join(l.root, id+".git"), nil
}

// Mkdir is exclusive: never initialize on an existing directory or symlink.
func (l *Local) InitBare(
	ctx context.Context,
	id, defaultBranch string,
) error {
	path, err := l.repoPath(id)
	if err != nil {
		return err
	}

	if defaultBranch != "main" {
		return errors.New("unsupported default branch")
	}

	if err := ctx.Err(); err != nil {
		return err
	}

	if err := os.Mkdir(path, 0700); err != nil {
		return fmt.Errorf("reserve repository path: %w", err)
	}

	// Only after successfully reserving this path may we clean it up.
	cmd := exec.CommandContext(
		ctx,
		l.git,
		"init",
		"--bare",
		"--initial-branch="+defaultBranch,
		path,
	)

	output, err := cmd.CombinedOutput()
	if err != nil {
		cleanupErr := os.RemoveAll(path)
		return errors.Join(
			fmt.Errorf(
				"git init: %w: %s",
				err,
				strings.TrimSpace(string(output)),
			),
			cleanupErr,
		)
	}

	return nil
}

// Caller must establish that no MySQL record exists before removing
// a bare repository. This is not a general repository deletion API.
func (l *Local) RemoveBare(ctx context.Context, id string) error {
	path, err := l.repoPath(id)
	if err != nil {
		return err
	}

	if err := ctx.Err(); err != nil {
		return err
	}

	return os.RemoveAll(path)
}
