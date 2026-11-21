package httpapi

import (
	"context"
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

type RegisterHandler struct {
	service         *identity.RegisterService
	bodyReadTimeout time.Duration
}

func NewRegisterHandler(
	service *identity.RegisterService,
) *RegisterHandler {
	return &RegisterHandler{service: service, bodyReadTimeout: 5 * time.Second}
}
func (h *RegisterHandler) Handle(c *gin.Context) {
	req, err := readRegisterRequest(c.Writer, c.Request, h.bodyReadTimeout)
	if err != nil {
		status, message := registerRequestError(err)
		c.JSON(status, gin.H{"error": message})
		return
	}
	result, err := h.service.Register(c.Request.Context(), identity.RegisterInput{
		Username: req.Username,
		Email:    req.Email,
		Password: req.Password,
	})
	if err != nil {
		switch {
		case errors.Is(err, identity.ErrInvalidRegistration):
			c.JSON(http.StatusBadRequest, gin.H{
				"error": "invalid registration input",
			})
		case errors.Is(err, identity.ErrUserAlreadyExists):
			c.JSON(http.StatusConflict, gin.H{
				"error": "username or email already exists",
			})
		case errors.Is(err, context.DeadlineExceeded):
			c.JSON(http.StatusGatewayTimeout, gin.H{"error": "registration timed out"})
		default:
			log.Printf("register failed: %v", err)

			c.JSON(http.StatusInternalServerError, gin.H{
				"error": "internal server error",
			})
		}
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"id":             result.ID,
		"username":       result.Username,
		"display_name":   result.DisplayName,
		"email":          result.Email,
		"email_verified": false,
	})
}
