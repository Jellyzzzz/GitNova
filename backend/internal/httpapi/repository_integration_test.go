package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"

	"github.com/Jellyzzzz/GitNova/backend/internal/gitstorage"
	"github.com/Jellyzzzz/GitNova/backend/internal/httpapi"
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	mysqlstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/mysql"
	redisstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/redis"
	"github.com/Jellyzzzz/GitNova/backend/internal/repository"
)

func TestRepositoryIntegration(t *testing.T) {
	f := newRegistrationIntegration(t)
	root := t.TempDir()
	git, err := exec.LookPath("git")
	if err != nil {
		t.Fatal(err)
	}
	storage, err := gitstorage.NewLocal(root, git)
	if err != nil {
		t.Fatal(err)
	}
	users := mysqlstore.NewUserStore(f.db)
	login := identity.NewLoginService(users, redisstore.NewSessionStore(f.redis))
	router := httpapi.NewRouter(identity.NewRegisterService(users, f.tokens, f.mail), false)
	httpapi.MountLoginRoutes(router, login, false)
	httpapi.MountRepositoryRoutes(router, repository.NewService(mysqlstore.NewRepositoryStore(f.db), storage), login, false)
	f.server.Close()
	f.server = httptest.NewServer(router)
	owner := accountRegister(t, f, "RepoOwner", "repo-owner@example.test")
	a, _ := accountLogin(t, f, "repo-owner@example.test", integrationPassword)
	accountRegister(t, f, "RepoOther", "repo-other@example.test")
	b, _ := accountLogin(t, f, "RepoOther", integrationPassword)
	call := func(client *http.Client, method, path, body string) (int, []byte) {
		req, _ := http.NewRequest(method, f.server.URL+path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		res, e := client.Do(req)
		if e != nil {
			t.Error(e)
			return 0, nil
		}
		defer res.Body.Close()
		raw, e := io.ReadAll(res.Body)
		if e != nil {
			t.Error(e)
		}
		return res.StatusCode, raw
	}
	create := func(client *http.Client, body string, want int) repository.Repository {
		status, raw := call(client, "POST", "/api/repositories", body)
		if status != want {
			t.Fatalf("create status=%d want=%d body=%s", status, want, raw)
		}
		var item repository.Repository
		if status == 201 {
			if e := json.Unmarshal(raw, &item); e != nil {
				t.Fatal(e)
			}
		}
		return item
	}
	t.Run("authentication validation and full chain", func(t *testing.T) {
		create(f.client(t), `{"name":"anonymous"}`, 401)
		create(a, `{"name":"../escape"}`, 400)
		create(a, `{"name":"bad.git"}`, 400)
		create(a, `{"name":"owner-injection","owner_id":999}`, 400)
		item := create(a, `{"name":"PrivateRepo","visibility":"private"}`, 201)
		if item.OwnerID != owner || item.Visibility != repository.Private {
			t.Fatal("owner or visibility mismatch")
		}
		path := filepath.Join(root, item.ID+".git")
		out, e := exec.Command(git, "--git-dir="+path, "rev-parse", "--is-bare-repository").CombinedOutput()
		if e != nil || string(out) != "true\n" {
			t.Fatalf("bare git=%s err=%v", out, e)
		}
		out, e = exec.Command(git, "--git-dir="+path, "symbolic-ref", "HEAD").CombinedOutput()
		if e != nil || string(out) != "refs/heads/main\n" {
			t.Fatal("HEAD not main")
		}
		if status, _ := call(b, "GET", "/api/repositories/"+item.ID, ""); status != 404 {
			t.Fatalf("foreign private repo status=%d", status)
		}
		if status, _ := call(a, "GET", "/api/repositories/"+item.ID, ""); status != 200 {
			t.Fatal(status)
		}
		create(a, `{"name":"PrivateRepo"}`, 409)
		create(b, `{"name":"PrivateRepo"}`, 201)
		create(a, `{"name":"privaterepo"}`, 201)
		omitted := create(a, `{"name":"DefaultVisibility"}`, 201)
		t.Logf("AUDIT omitted visibility yields %s; schema default is private", omitted.Visibility)
	})
	t.Run("concurrent names and filesystem cleanup", func(t *testing.T) {
		var wg sync.WaitGroup
		statuses := make([]int, 4)
		for i := range statuses {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				statuses[i], _ = call(a, "POST", "/api/repositories", `{"name":"ConcurrentRepo"}`)
			}(i)
		}
		wg.Wait()
		wins := 0
		for _, s := range statuses {
			if s == 201 {
				wins++
			} else if s != 409 {
				t.Fatalf("statuses=%v", statuses)
			}
		}
		if wins != 1 {
			t.Fatal(statuses)
		}
		var count int64
		f.db.Model(&repository.Repository{}).Count(&count)
		entries, e := os.ReadDir(root)
		if e != nil {
			t.Fatal(e)
		}
		if int64(len(entries)) != count {
			t.Fatalf("dirs=%d records=%d", len(entries), count)
		}
		t.Logf("concurrent statuses=%v; directories=%d database records=%d", statuses, len(entries), count)
	})
	t.Run("Git failure creates no record", func(t *testing.T) {
		broken, e := gitstorage.NewLocal(root, "/nonexistent/gitnova-test-git")
		if e != nil {
			t.Fatal(e)
		}
		service := repository.NewService(mysqlstore.NewRepositoryStore(f.db), broken)
		_, e = service.Create(context.Background(), owner, repository.CreateInput{Name: "GitFailure"})
		if e == nil {
			t.Fatal("expected init failure")
		}
		var count int64
		f.db.Model(&repository.Repository{}).Count(&count)
		entries, _ := os.ReadDir(root)
		if int64(len(entries)) != count {
			t.Fatal("failed init left directory")
		}
	})
	t.Run("database rejection cleans initialized Git", func(t *testing.T) {
		service := repository.NewService(mysqlstore.NewRepositoryStore(f.db), storage)
		_, e := service.Create(context.Background(), 999999999, repository.CreateInput{Name: "MissingOwner"})
		if e == nil {
			t.Fatal("expected FK rejection")
		}
		var count int64
		f.db.Model(&repository.Repository{}).Count(&count)
		entries, _ := os.ReadDir(root)
		if int64(len(entries)) != count {
			t.Fatal("failed insert left directory")
		}
	})
}
