package gittransport

import (
	"net/http"
	"net/http/cgi"
	"strings"
)

func New(repoRoot, gitBackend string) http.Handler {
	return &transportHandler{
		cgi: &cgi.Handler{
			Path: gitBackend,
			Root: "/git",
			Env:  []string{"GIT_PROJECT_ROOT=" + repoRoot},
		},
	}
}

type transportHandler struct {
	cgi http.Handler
}

func (t *transportHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	service := r.URL.Query().Get("service")

	// 当前阶段只开放只读的 fetch/clone，push 走 receive-pack，先拒绝。
	const uploadPack = "git-upload-pack"
	isDiscovery := r.Method == http.MethodGet &&
		strings.HasSuffix(r.URL.Path, "/info/refs") &&
		service == uploadPack
	isTransfer := r.Method == http.MethodPost &&
		strings.HasSuffix(r.URL.Path, "/"+uploadPack)
	if !isDiscovery && !isTransfer {
		http.Error(
			w,
			"Git operation not allowed",
			http.StatusForbidden,
		)
		return
	}
	t.cgi.ServeHTTP(w, r)
}
