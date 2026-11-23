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

const (
	maxRegisterBodyBytes     = 16 << 10
	maxVerificationBodyBytes = 4 << 10
)

var (
	errRegisterMediaType = errors.New("registration requires application/json")
	errRegisterJSON      = errors.New("invalid registration JSON object")
	errRegisterDeadline  = errors.New("registration read deadline unavailable")
)

type registerRequest struct {
	Username string `json:"username" binding:"required,min=3,max=64"`
	Password string `json:"password" binding:"required,min=8"`
}

type requestEmailRequest struct {
	Email string `json:"email" binding:"required,email,max=254"`
}

type verifyEmailRequest struct {
	Email string `json:"email" binding:"required,email,max=254"`
	Token string `json:"token" binding:"required,max=256"`
}

func readRegisterRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (request registerRequest, err error) {
	err = readRegistrationJSON(w, r, timeout, maxRegisterBodyBytes, map[string]*string{
		"username": &request.Username,
		"password": &request.Password,
	}, &request)
	return request, err
}

func readEmailRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (request requestEmailRequest, err error) {
	err = readRegistrationJSON(w, r, timeout, maxVerificationBodyBytes, map[string]*string{
		"email": &request.Email,
	}, &request)
	return request, err
}

func readVerifyEmailRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (request verifyEmailRequest, err error) {
	err = readRegistrationJSON(w, r, timeout, maxVerificationBodyBytes, map[string]*string{
		"email": &request.Email,
		"token": &request.Token,
	}, &request)
	return request, err
}

func readRegistrationJSON(w http.ResponseWriter, r *http.Request, timeout time.Duration, limit int64, fields map[string]*string, target any) (err error) {
	controller := http.NewResponseController(w)
	if err := controller.SetReadDeadline(time.Now().Add(timeout)); err != nil {
		return fmt.Errorf("%w: %w", errRegisterDeadline, err)
	}
	defer func() {
		if err != nil && r.ProtoMajor == 1 {
			w.Header().Set("Connection", "close")
			_ = controller.SetReadDeadline(time.Now())
		}
		_ = r.Body.Close()
		_ = controller.SetReadDeadline(time.Time{})
	}()
	mediaType, _, parseErr := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if parseErr != nil || mediaType != "application/json" {
		return errRegisterMediaType
	}
	if r.ContentLength > limit {
		return &http.MaxBytesError{Limit: limit}
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, limit))
	if err != nil {
		return err
	}
	_ = controller.SetReadDeadline(time.Time{})
	if err := decodeRegistrationJSON(body, fields); err != nil {
		return err
	}
	return binding.Validator.ValidateStruct(target)
}

func decodeRegistrationJSON(body []byte, fields map[string]*string) error {
	decoder := json.NewDecoder(bytes.NewReader(body))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return errRegisterJSON
	}
	seen := make(map[string]bool, len(fields))
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return errRegisterJSON
		}
		name, ok := token.(string)
		if !ok || seen[name] {
			return errRegisterJSON
		}
		seen[name] = true
		field, ok := fields[name]
		if !ok {
			return errRegisterJSON
		}
		if err := decoder.Decode(field); err != nil {
			return errRegisterJSON
		}
	}
	if token, err := decoder.Token(); err != nil || token != json.Delim('}') {
		return errRegisterJSON
	}
	var extra any
	if err := decoder.Decode(&extra); err != io.EOF {
		return errRegisterJSON
	}
	return nil
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
