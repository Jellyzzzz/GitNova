package main

import (
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/gittransport"
)

func main() {
	gitExecPath, err := exec.Command("git", "--exec-path").Output()
	if err != nil {
		log.Fatalf("find Git executable directory: %v", err)
	}
	gitBackend := filepath.Join(
		strings.TrimSpace(string(gitExecPath)),
		"git-http-backend",
	)
	if info, err := os.Stat(gitBackend); err != nil || info.IsDir() {
		log.Fatalf(
			"git-http-backend not executable file: %s (%v)",
			gitBackend, err,
		)
	}
	repoRoot, err := filepath.Abs("data/repositories")
	if err != nil {
		log.Fatalf("resolve repository root: %v", err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health",
		func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte("ok\n"))
		},
	)
	mux.Handle(
		"/git/100.git/",
		gittransport.New(repoRoot, gitBackend),
	)
	addr := os.Getenv("GITNOVA_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}

	server := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	log.Printf("listening on http://%s", server.Addr)
	log.Fatal(server.ListenAndServe())
}
