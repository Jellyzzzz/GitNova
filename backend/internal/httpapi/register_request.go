package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"time"

	"github.com/gin-gonic/gin/binding"
)

const maxRegisterBodyBytes = 16 << 10

var (
	errRegisterMediaType = errors.New("registration requires application/json")
	errRegisterJSON      = errors.New("invalid registration JSON object")
	errRegisterDeadline  = errors.New("registration read deadline unavailable")
)

type registerRequest struct {
	Username string `json:"username" binding:"required,min=3,max=64"`
	Email    string `json:"email" binding:"required,email,max=254"`
	Password string `json:"password" binding:"required,min=8"`
}

func readRegisterRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (request registerRequest, err error) {
	controller := http.NewResponseController(w)
	if err := controller.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return request, fmt.Errorf("%w: %w", errRegisterDeadline, err)
	}
	defer func() {
		if err != nil && r.ProtoMajor == 1 {
			w.Header().Set("Connection", "close")
			// Body.Close may drain an unfinished HTTP/1 upload. Bound that
			// cleanup before clearing the deadline for the rest of the RPC.
			_ = controller.SetReadDeadline(time.Now())
		}
		_ = r.Body.Close()
		_ = controller.SetReadDeadline(time.Time{})
	}()

	mediaType, _, parseErr := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if parseErr != nil || mediaType != "application/json" {
		return request, errRegisterMediaType
	}
	if r.ContentLength > maxRegisterBodyBytes {
		return request, &http.MaxBytesError{Limit: maxRegisterBodyBytes}
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxRegisterBodyBytes))
	if err != nil {
		return request, err
	}
	// The body has reached EOF. Do not leave a network read deadline armed
	// while decoding or validating the already-buffered JSON.
	_ = controller.SetReadDeadline(time.Time{})
	request, err = decodeRegisterRequest(body)
	if err != nil {
		return request, err
	}
	// Preserve the validation ShouldBindJSON previously performed. Business
	// username rules and normalization remain in identity.RegisterService.
	return request, binding.Validator.ValidateStruct(&request)
}

func decodeRegisterRequest(body []byte) (request registerRequest, err error) {
	decoder := json.NewDecoder(bytes.NewReader(body))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return request, errRegisterJSON
	}
	seen := make(map[string]bool, 3)
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return request, errRegisterJSON
		}
		name, ok := token.(string)
		if !ok || seen[name] {
			return request, errRegisterJSON
		}
		seen[name] = true
		var field *string
		switch name {
		case "username":
			field = &request.Username
		case "email":
			field = &request.Email
		case "password":
			field = &request.Password
		default:
			return request, errRegisterJSON
		}
		if err := decoder.Decode(field); err != nil {
			return request, errRegisterJSON
		}
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim('}') {
		return request, errRegisterJSON
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return request, errRegisterJSON
	}
	return request, nil
}

func registerRequestError(err error) (int, string) {
	var tooLarge *http.MaxBytesError
	var networkError net.Error
	switch {
	case errors.Is(err, errRegisterDeadline):
		return http.StatusInternalServerError, "registration transport unavailable"
	case errors.Is(err, errRegisterMediaType):
		return http.StatusUnsupportedMediaType, "registration requires application/json"
	case errors.As(err, &tooLarge):
		return http.StatusRequestEntityTooLarge, "registration request too large"
	case errors.As(err, &networkError) && networkError.Timeout():
		return http.StatusRequestTimeout, "registration request body timed out"
	default:
		return http.StatusBadRequest, "invalid registration request"
	}
}
