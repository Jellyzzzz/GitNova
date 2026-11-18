package mysql

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	mysqldriver "github.com/go-sql-driver/mysql"
	gormmysql "gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type requestContextKey struct{}

type testDriver struct{}

func init() {
	sql.Register("gitnova-user-store-test", testDriver{})
}

func (testDriver) Open(scenario string) (driver.Conn, error) {
	return &testConnection{scenario: scenario}, nil
}

type testConnection struct {
	scenario string
}

func (*testConnection) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("unexpected prepare")
}

func (*testConnection) Close() error { return nil }

func (*testConnection) Begin() (driver.Tx, error) { return testTransaction{}, nil }

func (connection *testConnection) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	if ctx.Value(requestContextKey{}) != "request" {
		return nil, errors.New("request context was not passed to the database")
	}
	if !strings.Contains(query, "INSERT INTO `users`") {
		return nil, fmt.Errorf("unexpected insert query: %s", query)
	}
	if connection.scenario == "duplicate" {
		return nil, &mysqldriver.MySQLError{Number: 1062, Message: "duplicate"}
	}
	return testResult{}, nil
}

func (connection *testConnection) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	if ctx.Value(requestContextKey{}) != "request" {
		return nil, errors.New("request context was not passed to the database")
	}
	if connection.scenario == "missing" {
		return &testRows{}, nil
	}

	var column string
	var value driver.Value
	switch connection.scenario {
	case "username":
		column, value = "username", "alice"
	case "email":
		column, value = "email", "alice@example.test"
	case "id":
		column, value = "id", int64(42)
	default:
		return nil, fmt.Errorf("unexpected query scenario: %s", connection.scenario)
	}
	if !strings.Contains(query, column+" = ?") || len(args) == 0 || args[0].Value != value {
		return nil, fmt.Errorf("unexpected lookup query or arguments: %s", query)
	}
	return &testRows{hasUser: true}, nil
}

type testTransaction struct{}

func (testTransaction) Commit() error   { return nil }
func (testTransaction) Rollback() error { return nil }

type testResult struct{}

func (testResult) LastInsertId() (int64, error) { return 42, nil }
func (testResult) RowsAffected() (int64, error) { return 1, nil }

type testRows struct {
	hasUser bool
}

func (*testRows) Columns() []string {
	return []string{"id", "username", "email", "password_hash", "created_at"}
}

func (*testRows) Close() error { return nil }

func (rows *testRows) Next(values []driver.Value) error {
	if !rows.hasUser {
		return io.EOF
	}
	rows.hasUser = false
	values[0], values[1], values[2], values[3], values[4] = int64(42), "alice", "alice@example.test", "hash", time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	return nil
}

func newTestUserStore(t *testing.T, scenario string) *UserStore {
	t.Helper()
	sqlDB, err := sql.Open("gitnova-user-store-test", scenario)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sqlDB.Close() })

	db, err := gorm.Open(gormmysql.New(gormmysql.Config{
		Conn:                      sqlDB,
		SkipInitializeWithVersion: true,
	}), &gorm.Config{
		DisableAutomaticPing: true,
		TranslateError:       true,
		Logger:               logger.Discard,
	})
	if err != nil {
		t.Fatal(err)
	}
	return NewUserStore(db)
}

func TestUserStore(t *testing.T) {
	ctx := context.WithValue(context.Background(), requestContextKey{}, "request")

	t.Run("create", func(t *testing.T) {
		user := &identity.User{Username: "alice", Email: "alice@example.test", PasswordHash: "hash"}
		if err := newTestUserStore(t, "create").Create(ctx, user); err != nil {
			t.Fatal(err)
		}
		if user.ID != 42 {
			t.Fatalf("created user ID = %d, want 42", user.ID)
		}
	})

	t.Run("duplicate", func(t *testing.T) {
		err := newTestUserStore(t, "duplicate").Create(ctx, &identity.User{Username: "alice"})
		if !errors.Is(err, gorm.ErrDuplicatedKey) {
			t.Fatalf("Create error = %v, want duplicated key", err)
		}
	})

	for _, scenario := range []string{"username", "email", "id"} {
		t.Run(scenario, func(t *testing.T) {
			store := newTestUserStore(t, scenario)
			var user *identity.User
			var err error
			switch scenario {
			case "username":
				user, err = store.FindUserByName(ctx, "alice")
			case "email":
				user, err = store.FindUserByEmail(ctx, "alice@example.test")
			case "id":
				user, err = store.FindUserByID(ctx, 42)
			}
			if err != nil {
				t.Fatal(err)
			}
			if user.ID != 42 || user.Email != "alice@example.test" || user.CreatedAt.IsZero() {
				t.Fatalf("unexpected user: %+v", user)
			}
		})
	}

	t.Run("not found", func(t *testing.T) {
		_, err := newTestUserStore(t, "missing").FindUserByName(ctx, "missing")
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			t.Fatalf("lookup error = %v, want record not found", err)
		}
	})
}
