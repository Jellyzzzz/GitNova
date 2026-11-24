package httpapi

import (
	"net/http"
	"time"
)

type loginRequest struct {
	Identifier string `json:"identifier" binding:"required,max=254"`
	Password   string `json:"password" binding:"required"`
}

type changePasswordRequest struct {
	CurrentPassword string `json:"current_password" binding:"required"`
	NewPassword     string `json:"new_password" binding:"required"`
}

func readLoginRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (req loginRequest, err error) {
	err = readRegistrationJSON(w, r, timeout, 4<<10, map[string]*string{
		"identifier": &req.Identifier, "password": &req.Password,
	}, &req)
	return
}

func readChangePasswordRequest(w http.ResponseWriter, r *http.Request, timeout time.Duration) (req changePasswordRequest, err error) {
	err = readRegistrationJSON(w, r, timeout, 4<<10, map[string]*string{
		"current_password": &req.CurrentPassword, "new_password": &req.NewPassword,
	}, &req)
	return
}
