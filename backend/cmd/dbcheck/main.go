package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/database"
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
)

func main() {
	dsn := os.Getenv("GITNOVA_MYSQL_DSN")
	if dsn == "" {
		log.Fatal("GITNOVA_MYSQL_DSN is not set")
	}

	ctx, cancel := context.WithTimeout(
		context.Background(),
		5*time.Second,
	)
	defer cancel()

	db, err := database.OpenMySQL(ctx, dsn)
	if err != nil {
		log.Fatal(err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		log.Fatal(err)
	}
	defer sqlDB.Close()

	// 通过 GORM 模型查询 users 表
	var count int64

	err = db.WithContext(ctx).
		Model(&identity.User{}).
		Count(&count).Error

	if err != nil {
		log.Fatal(err)
	}

	fmt.Println("MySQL connected")
	fmt.Printf("Users: %d\n", count)
}
