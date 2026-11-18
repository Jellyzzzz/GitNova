package identity

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestUserJSONOmitsPrivateFields(t *testing.T) {
	now := time.Now()
	user := User{
		Username:        "example-user",
		Email:           "private@example.test",
		PasswordHash:    "secret-hash",
		EmailVerifiedAt: &now,
		DisabledAt:      &now,
	}

	data, err := json.Marshal(user)
	if err != nil {
		t.Fatal(err)
	}
	output := string(data)
	if !strings.Contains(output, "example-user") {
		t.Fatal("username should remain available")
	}
	for _, private := range []string{"Email", "PasswordHash", "EmailVerifiedAt", "DisabledAt", user.Email, user.PasswordHash} {
		if strings.Contains(output, private) {
			t.Fatalf("JSON contains private field or value %q", private)
		}
	}
}
