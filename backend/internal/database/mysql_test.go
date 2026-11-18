package database

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func TestParseMySQLDSNEnablesTimeParsing(t *testing.T) {
	config, err := parseMySQLDSN("example:secret@tcp(127.0.0.1:3306)/gitnova_go_test?parseTime=false&loc=Local")
	if err != nil {
		t.Fatal(err)
	}
	if !config.ParseTime {
		t.Fatal("DATETIME parsing must be enabled")
	}
	if config.User != "example" || config.DBName != "gitnova_go_test" || config.Loc.String() != "Local" {
		t.Fatal("DSN settings were not preserved")
	}
}

func TestOpenMySQLHonorsCanceledContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := OpenMySQL(ctx, "example:secret@tcp(127.0.0.1:1)/gitnova_go_test?timeout=100ms&readTimeout=100ms")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("OpenMySQL error = %v, want context.Canceled", err)
	}
}

func TestMySQLLoggerDoesNotLogQueryParameters(t *testing.T) {
	sqlDB, err := sql.Open("mysql", "example:secret@tcp(127.0.0.1:1)/gitnova_go_test")
	if err != nil {
		t.Fatal(err)
	}
	defer sqlDB.Close()

	var output bytes.Buffer
	db, err := gorm.Open(mysql.New(mysql.Config{
		Conn:                      sqlDB,
		SkipInitializeWithVersion: true,
	}), &gorm.Config{
		DryRun:                 true,
		DisableAutomaticPing:   true,
		SkipDefaultTransaction: true,
		Logger:                 newMySQLLogger(&output).LogMode(logger.Info),
	})
	if err != nil {
		t.Fatal(err)
	}

	user := identity.User{Username: "example-user", Email: "private@example.test", PasswordHash: "secret-hash"}
	if err := db.Create(&user).Error; err != nil {
		t.Fatal(err)
	}
	logged := output.String()
	if !strings.Contains(logged, "INSERT INTO") {
		t.Fatal("expected the SQL statement to be logged")
	}
	for _, value := range []string{user.Username, user.Email, user.PasswordHash} {
		if strings.Contains(logged, value) {
			t.Fatal("SQL log contains a query parameter")
		}
	}
}
