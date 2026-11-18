package database

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"time"

	mysqldriver "github.com/go-sql-driver/mysql"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func OpenMySQL(ctx context.Context, dsn string) (*gorm.DB, error) {
	dsnConfig, err := parseMySQLDSN(dsn)
	if err != nil {
		return nil, err
	}

	db, err := gorm.Open(mysql.New(mysql.Config{
		DSNConfig:                 dsnConfig,
		SkipInitializeWithVersion: true,
	}), &gorm.Config{
		TranslateError:       true,
		DisableAutomaticPing: true,
		Logger:               newMySQLLogger(os.Stdout),
	})
	if err != nil {
		return nil, fmt.Errorf("open mysql: %w", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("get sql.DB: %w", err)
	}

	sqlDB.SetMaxOpenConns(10)
	sqlDB.SetMaxIdleConns(5)
	sqlDB.SetConnMaxLifetime(30 * time.Minute)
	if err := sqlDB.PingContext(ctx); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("ping mysql: %w", err)
	}
	return db, nil
}

func parseMySQLDSN(dsn string) (*mysqldriver.Config, error) {
	config, err := mysqldriver.ParseDSN(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse mysql dsn: %w", err)
	}
	config.ParseTime = true
	return config, nil
}

func newMySQLLogger(writer io.Writer) logger.Interface {
	return logger.New(log.New(writer, "\r\n", log.LstdFlags), logger.Config{
		SlowThreshold:        200 * time.Millisecond,
		LogLevel:             logger.Warn,
		ParameterizedQueries: true,
	})
}
