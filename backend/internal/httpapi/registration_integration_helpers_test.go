package httpapi_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/database"
	"github.com/Jellyzzzz/GitNova/backend/internal/httpapi"
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	mysqlstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/mysql"
	redisstore "github.com/Jellyzzzz/GitNova/backend/internal/persistence/redis"
	mysqldriver "github.com/go-sql-driver/mysql"
	"github.com/redis/go-redis/v9"
	"gorm.io/gorm"
)

const integrationCookie = "gitnova_registration"
const integrationPassword = "Integration-Password-2026"

type capturedRegistrationMail struct {
	mu     sync.Mutex
	tokens map[string]string
	count  int
}

func (m *capturedRegistrationMail) SendVerification(ctx context.Context, email, token string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[email] = token
	m.count++
	return nil
}

func (m *capturedRegistrationMail) token(email string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.tokens[email]
}

func (m *capturedRegistrationMail) sent() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.count
}

type registrationIntegration struct {
	db        *gorm.DB
	redis     *redis.Client
	stopRedis func()
	tokens    *redisstore.RegistrationStore
	mail      *capturedRegistrationMail
	server    *httptest.Server
	url       *url.URL
}

type integrationPayload struct {
	ID            int64  `json:"id"`
	Username      string `json:"username"`
	DisplayName   string `json:"display_name"`
	Email         string `json:"email"`
	EmailVerified bool   `json:"email_verified"`
	ExpiresIn     int    `json:"expires_in"`
	Error         string `json:"error"`
}

type integrationResponse struct {
	status  int
	body    integrationPayload
	cookies []*http.Cookie
	headers http.Header
	raw     []byte
}

func newRegistrationIntegration(t *testing.T) *registrationIntegration {
	t.Helper()
	dsn := os.Getenv("GITNOVA_INTEGRATION_MYSQL_DSN")
	if dsn == "" {
		t.Skip("set GITNOVA_INTEGRATION_MYSQL_DSN to run real Redis/MySQL integration tests")
	}
	redisBinary, err := exec.LookPath("redis-server")
	if err != nil {
		t.Fatal("redis-server must be installed for this integration test")
	}
	f := &registrationIntegration{
		db:   isolatedRegistrationMySQL(t, dsn),
		mail: &capturedRegistrationMail{tokens: make(map[string]string)},
	}
	f.redis, f.stopRedis = isolatedRegistrationRedis(t, redisBinary)
	f.tokens = redisstore.NewRegistrationStore(f.redis)
	service := identity.NewRegisterService(mysqlstore.NewUserStore(f.db), f.tokens, f.mail)
	router := httpapi.NewRouter(service, false)
	httpapi.MountLoginRoutes(router, identity.NewLoginService(mysqlstore.NewUserStore(f.db), redisstore.NewSessionStore(f.redis)), false)
	f.server = httptest.NewServer(router)
	t.Cleanup(func() { f.server.CloseClientConnections(); f.server.Close() })
	f.url, err = url.Parse(f.server.URL + "/api/auth/register")
	if err != nil {
		t.Fatal(err)
	}
	return f
}

func isolatedRegistrationMySQL(t *testing.T, dsn string) *gorm.DB {
	t.Helper()
	config, err := mysqldriver.ParseDSN(dsn)
	if err != nil {
		t.Fatal("invalid integration DSN; credentials omitted")
	}
	config.DBName = ""
	config.Timeout, config.ReadTimeout, config.WriteTimeout = 3*time.Second, 5*time.Second, 5*time.Second
	admin, err := sql.Open("mysql", config.FormatDSN())
	if err != nil {
		t.Fatal("cannot open integration administrator connection; credentials omitted")
	}
	t.Cleanup(func() { _ = admin.Close() })
	var random [8]byte
	if _, err := rand.Read(random[:]); err != nil {
		t.Fatal(err)
	}
	name := "gitnova_registration_test_" + hex.EncodeToString(random[:])
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := admin.ExecContext(ctx, "CREATE DATABASE "+name+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); err != nil {
		t.Fatal("cannot create isolated database; test account needs CREATE/DROP DATABASE privileges")
	}
	t.Cleanup(func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := admin.ExecContext(cleanup, "DROP DATABASE "+name); err != nil {
			t.Errorf("remove isolated database %s: %v", name, err)
			return
		}
		var remaining int
		if err := admin.QueryRowContext(cleanup, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?", name).Scan(&remaining); err != nil || remaining != 0 {
			t.Error("isolated MySQL database cleanup could not be verified")
		} else {
			t.Log("isolated MySQL database removed and absence verified")
		}
	})
	config.DBName = name
	db, err := database.OpenMySQL(ctx, config.FormatDSN())
	if err != nil {
		t.Fatal("cannot open isolated integration database; credentials omitted")
	}
	pool, err := db.DB()
	if err != nil {
		t.Fatal(err)
	}
	pool.SetMaxOpenConns(4)
	t.Cleanup(func() { _ = pool.Close() })
	schema, err := os.ReadFile("../../database/schema.sql")
	if err != nil {
		t.Fatal(err)
	}
	_, ddl, found := strings.Cut(string(schema), "CREATE TABLE users (")
	if !found {
		t.Fatal("users DDL not found in checked-in schema")
	}
	for _, statement := range strings.Split("CREATE TABLE users ("+ddl, ";") {
		if strings.TrimSpace(statement) != "" {
			if err := db.WithContext(ctx).Exec(statement).Error; err != nil {
				t.Fatal(err)
			}
		}
	}
	var version string
	if err := db.WithContext(ctx).Raw("SELECT VERSION()").Scan(&version).Error; err != nil {
		t.Fatal(err)
	}
	t.Logf("real MySQL %s; loaded current schema into isolated database", version)
	return db
}

func isolatedRegistrationRedis(t *testing.T, binary string) (*redis.Client, func()) {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()
	directory := t.TempDir()
	logFile, err := os.Create(filepath.Join(directory, "redis.log"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = logFile.Close() })
	command := exec.Command(binary, "--bind", "127.0.0.1", "--port", strconv.Itoa(port),
		"--save", "", "--appendonly", "no", "--maxmemory", "32mb", "--maxmemory-policy", "noeviction", "--dir", directory)
	command.Stdout, command.Stderr = logFile, logFile
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = command.Wait(); close(done) }()
	var stopOnce sync.Once
	stop := func() {
		stopOnce.Do(func() {
			_ = command.Process.Signal(syscall.SIGTERM)
			select {
			case <-done:
			case <-time.After(5 * time.Second):
				_ = command.Process.Kill()
				<-done
			}
			t.Log("dedicated Redis process stopped")
		})
	}
	t.Cleanup(stop)
	client := redis.NewClient(&redis.Options{
		Addr: net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), PoolSize: 4, MaxRetries: -1,
		DialTimeout: time.Second, ReadTimeout: time.Second, WriteTimeout: time.Second,
	})
	t.Cleanup(func() { _ = client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for client.Ping(ctx).Err() != nil {
		select {
		case <-ctx.Done():
			t.Fatal("isolated Redis did not become ready")
		case <-done:
			t.Fatal("isolated Redis exited during startup")
		case <-time.After(30 * time.Millisecond):
		}
	}
	info, err := client.Info(ctx, "server").Result()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(info, "\r\n") {
		if strings.HasPrefix(line, "redis_version:") {
			t.Log("real " + line + "; dedicated temporary Redis process")
		}
	}
	return client, stop
}

func (f *registrationIntegration) client(t *testing.T) *http.Client {
	t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	return &http.Client{Jar: jar, Timeout: 8 * time.Second, Transport: f.server.Client().Transport}
}

func (f *registrationIntegration) call(client *http.Client, method, path string, body any) (integrationResponse, error) {
	var data []byte
	if body != nil {
		var err error
		data, err = json.Marshal(body)
		if err != nil {
			return integrationResponse{}, err
		}
	}
	contentType := ""
	if body != nil {
		contentType = "application/json"
	}
	return f.callRaw(client, method, path, data, contentType)
}

func (f *registrationIntegration) callRaw(client *http.Client, method, path string, data []byte, contentType string) (integrationResponse, error) {
	request, err := http.NewRequest(method, f.server.URL+path, bytes.NewReader(data))
	if err != nil {
		return integrationResponse{}, err
	}
	if contentType != "" {
		request.Header.Set("Content-Type", contentType)
	}
	response, err := client.Do(request)
	if err != nil {
		return integrationResponse{}, err
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, 16<<10))
	if err != nil {
		return integrationResponse{}, err
	}
	var payload integrationPayload
	if len(raw) != 0 {
		if err := json.Unmarshal(raw, &payload); err != nil {
			return integrationResponse{}, err
		}
	}
	return integrationResponse{status: response.StatusCode, body: payload, cookies: response.Cookies(), headers: response.Header.Clone(), raw: raw}, nil
}

func (f *registrationIntegration) mustCall(t *testing.T, client *http.Client, method, path string, body any, want int) integrationResponse {
	t.Helper()
	response, err := f.call(client, method, path, body)
	if err != nil {
		t.Fatal(err)
	}
	if response.status != want {
		t.Fatalf("%s %s: status=%d want=%d error=%q", method, path, response.status, want, response.body.Error)
	}
	return response
}

func (f *registrationIntegration) requestToken(t *testing.T, client *http.Client, email string) string {
	t.Helper()
	f.mustCall(t, client, http.MethodPost, "/api/auth/registration/email", map[string]string{"email": email}, http.StatusAccepted)
	token := f.mail.token(email)
	if token == "" {
		t.Fatal("local mail capture did not receive verification token")
	}
	return token
}

func (f *registrationIntegration) verifiedClient(t *testing.T, email string) (*http.Client, string) {
	t.Helper()
	client := f.client(t)
	token := f.requestToken(t, client, email)
	f.mustCall(t, client, http.MethodPost, "/api/auth/registration/verify", map[string]string{"email": email, "token": token}, http.StatusOK)
	return client, f.ticket(t, client)
}

func (f *registrationIntegration) ticket(t *testing.T, client *http.Client) string {
	t.Helper()
	for _, cookie := range client.Jar.Cookies(f.url) {
		if cookie.Name == integrationCookie {
			return cookie.Value
		}
	}
	t.Fatal("registration cookie was not saved by HTTP cookie jar")
	return ""
}

func (f *registrationIntegration) emailRows(t *testing.T, email string) int64 {
	t.Helper()
	var rows int64
	if err := f.db.Model(&identity.User{}).Where("email = ?", email).Count(&rows).Error; err != nil {
		t.Fatal(err)
	}
	return rows
}

func registrationRedisKey(kind, value string) string {
	hash := sha256.Sum256([]byte(value))
	return "registration:" + kind + ":" + hex.EncodeToString(hash[:])
}

func expireIntegrationKey(t *testing.T, client *redis.Client, key string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if ok, err := client.PExpire(ctx, key, 50*time.Millisecond).Result(); err != nil || !ok {
		t.Fatal("cannot shorten isolated test key TTL")
	}
	for {
		count, err := client.Exists(ctx, key).Result()
		if err != nil {
			t.Fatal(err)
		}
		if count == 0 {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatal("isolated test key did not expire")
		case <-time.After(10 * time.Millisecond):
		}
	}
}
