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
	"strings"
	"syscall"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/database"
	"github.com/Jellyzzzz/GitNova/backend/internal/gittransport"
	"github.com/Jellyzzzz/GitNova/backend/internal/httpapi"
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	mysqlstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/mysql"
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
	gitBackend := filepath.Join(
		strings.TrimSpace(string(gitExecPath)),
		"git-http-backend",
	)
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
	mux := http.NewServeMux()
	dsn := os.Getenv("GITNOVA_MYSQL_DSN")
if dsn == "" {
    log.Fatal("GITNOVA_MYSQL_DSN is not set")
}

startupCtx, cancel := context.WithTimeout(
    context.Background(),
    5*time.Second,
)

db, err := database.OpenMySQL(startupCtx, dsn)
cancel()

if err != nil {
    log.Fatal(err)
}

sqlDB, err := db.DB()
if err != nil {
    log.Fatal(err)
}
defer sqlDB.Close()

userStore := mysqlstore.NewUserStore(db)
registerService := identity.NewRegisterService(userStore)
	apiRouter:=httpapi.NewRouter(registerService)
	mux.Handle("/api/",apiRouter)
	mux.HandleFunc("GET /health",
		func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte("ok\n"))
		},
	)
	mux.Handle(
		"/git/100.git/",
		transport.ReadOnlyRepository(100),
	)
	addr := os.Getenv("GITNOVA_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}

	server := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       90 * time.Second,
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
