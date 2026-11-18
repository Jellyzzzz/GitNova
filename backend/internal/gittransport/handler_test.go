package gittransport

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestReadOnlyGitEndpoints(t *testing.T) {
	cases := []struct {
		name   string
		method string
		url    string
		allow  bool
	}{
		{
			"advertise upload pack",
			http.MethodGet,
			"/git/100.git/info/refs?service=git-upload-pack",
			true,
		},
		{
			"fetch pack",
			http.MethodPost,
			"/git/100.git/git-upload-pack",
			true,
		},
		{
			"advertise receive pack",
			http.MethodGet,
			"/git/100.git/info/refs?service=git-receive-pack",
			false,
		},
		{
			"receive pack",
			http.MethodPost,
			"/git/100.git/git-receive-pack",
			false,
		},
		{
			"other git path",
			http.MethodGet,
			"/git/100.git/HEAD",
			false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			called := false

			h := &transportHandler{
				cgi: http.HandlerFunc(
					func(w http.ResponseWriter, r *http.Request) {
						called = true
						w.WriteHeader(http.StatusNoContent)
					},
				),
			}

			w := httptest.NewRecorder()
			h.ServeHTTP(
				w,
				httptest.NewRequest(tc.method, tc.url, nil),
			)

			if called != tc.allow {
				t.Fatalf(
					"backend called = %v, want %v",
					called, tc.allow,
				)
			}

			want := http.StatusForbidden
			if tc.allow {
				want = http.StatusNoContent
			}

			if w.Code != want {
				t.Fatalf(
					"status = %d, want %d",
					w.Code, want,
				)
			}
		})
	}
}
