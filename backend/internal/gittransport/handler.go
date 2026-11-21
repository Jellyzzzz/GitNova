package gittransport

import (
	"fmt"
	"net/http"
)

type executor interface {
	Serve(http.ResponseWriter, *http.Request, Invocation)
}

type transportHandler struct {
	backend      executor
	repositoryID int64
}

// ReadOnlyRepository exposes one explicitly public repository. Private reads and
// writes must resolve credentials and ACL before calling Serve.
func (b *Backend) ReadOnlyRepository(repositoryID int64) http.Handler {
	return &transportHandler{backend: b, repositoryID: repositoryID}
}

func (h *transportHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	prefix := fmt.Sprintf("/git/%d.git", h.repositoryID)
	discovery := r.Method == http.MethodGet &&
		r.URL.Path == prefix+"/info/refs" && r.URL.Query().Get("service") == "git-upload-pack"
	transfer := r.Method == http.MethodPost && r.URL.Path == prefix+"/git-upload-pack"
	if !discovery && !transfer {
		reject(w, r, http.StatusForbidden, "Git operation not allowed")
		return
	}
	h.backend.Serve(w, r, Invocation{RepositoryID: h.repositoryID, Service: UploadPack, Discovery: discovery})
}
