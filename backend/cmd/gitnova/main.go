package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/database"
	"github.com/Jellyzzzz/GitNova/backend/internal/gittransport"
	"github.com/Jellyzzzz/GitNova/backend/internal/httpapi"
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/Jellyzzzz/GitNova/backend/internal/mailer"
	mysqlstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/mysql"
	redisstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/redis"
	"github.com/redis/go-redis/v9"
)

func main() {
	if err := run(); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}

func run() error {
	rootDefault := os.Getenv("GITNOVA_REPOSITORY_ROOT")
	if rootDefault == "" {
		rootDefault = "data/repositories"
	}
	repositoryRoot := flag.String("repository-root", rootDefault, "persistent bare repository directory")
	maxConcurrent := flag.Int("git-max-concurrent", 8, "maximum simultaneously admitted Git RPCs")
	maxRequestBytes := flag.Int64("git-max-request-bytes", 1<<30, "maximum encoded and decoded request bytes")
	maxDuration := flag.Duration("git-max-duration", 30*time.Minute, "maximum lifetime of one Git RPC")
	idleTimeout := flag.Duration("git-idle-timeout", 5*time.Minute, "maximum period without Git RPC progress")
	flag.Parse()

	lookupCtx, lookupCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer lookupCancel()
	gitExecPath, err := exec.CommandContext(lookupCtx, "git", "--exec-path").Output()
	if err != nil {
		return fmt.Errorf("find Git executable directory: %w", err)
	}
	gitBackend := filepath.Join(strings.TrimSpace(string(gitExecPath)), "git-http-backend")
	repoRoot, err := filepath.Abs(*repositoryRoot)
	if err != nil {
		return fmt.Errorf("resolve repository root: %w", err)
	}
	if err := os.MkdirAll(repoRoot, 0700); err != nil {
		return fmt.Errorf("create repository root: %w", err)
	}
	transport, err := gittransport.New(gittransport.Config{
		RepositoryRoot: repoRoot, Executable: gitBackend,
		MaxConcurrent: *maxConcurrent, MaxRequestBytes: *maxRequestBytes,
		MaxDuration: *maxDuration, IdleTimeout: *idleTimeout,
	})
	if err != nil {
		return err
	}
	shutdownStarted := false
	defer func() {
		if shutdownStarted {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := transport.Shutdown(ctx); err != nil {
			log.Printf("Git shutdown: %v", err)
		}
	}()

	// Initialize the single shared MySQL and Redis clients at startup.
	dsn := os.Getenv("GITNOVA_MYSQL_DSN")
	if dsn == "" {
		return fmt.Errorf("GITNOVA_MYSQL_DSN is not set")
	}
	startupCtx, startupCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer startupCancel()
	db, err := database.OpenMySQL(startupCtx, dsn)
	if err != nil {
		return err
	}
	sqlDB, err := db.DB()
	if err != nil {
		return err
	}
	defer sqlDB.Close()

	redisAddr := os.Getenv("GITNOVA_REDIS_ADDR")
	if redisAddr == "" {
		redisAddr = "127.0.0.1:6379"
	}
	rdb := redis.NewClient(&redis.Options{
		Addr: redisAddr, Password: os.Getenv("GITNOVA_REDIS_PASSWORD"),
	})
	defer rdb.Close()
	if err := rdb.Ping(startupCtx).Err(); err != nil {
		return fmt.Errorf("connect Redis: %w", err)
	}
	mailSender, err := mailer.NewFromEnv()
	if err != nil {
		return err
	}
	secureCookie := false
	if raw := os.Getenv("GITNOVA_COOKIE_SECURE"); raw != "" {
		secureCookie, err = strconv.ParseBool(raw)
		if err != nil {
			return fmt.Errorf("invalid GITNOVA_COOKIE_SECURE: %w", err)
		}
	}

	userStore := mysqlstore.NewUserStore(db)
	tokenStore := redisstore.NewRegistrationStore(rdb)
	registerService := identity.NewRegisterService(userStore, tokenStore, mailSender)

	mux := http.NewServeMux()
	mux.Handle("/api/", httpapi.NewRouter(registerService, secureCookie))
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.Handle("/git/100.git/", transport.ReadOnlyRepository(100))

	addr := os.Getenv("GITNOVA_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	server := &http.Server{
		Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 90 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serverErrors := make(chan error, 1)
	go func() { serverErrors <- server.ListenAndServe() }()
	log.Printf("starting HTTP on %s, repositories=%s, Git concurrency=%d", server.Addr, repoRoot, *maxConcurrent)
	select {
	case err := <-serverErrors:
		if !errors.Is(err, http.ErrServerClosed) {
			return fmt.Errorf("HTTP server: %w", err)
		}
		return nil
	case <-ctx.Done():
	}
	shutdownStarted = true
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	gitErr := transport.Shutdown(shutdownCtx)
	httpErr := server.Shutdown(shutdownCtx)
	if httpErr != nil {
		_ = server.Close()
	}
	<-serverErrors
	return errors.Join(gitErr, httpErr)
}
