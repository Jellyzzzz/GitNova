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

const registrationCookie = "gitnova_registration"

type RegisterHandler struct {
	service         *identity.RegisterService
	bodyReadTimeout time.Duration
	cookieSecure    bool
}

func NewRegisterHandler(service *identity.RegisterService, secureCookie bool) *RegisterHandler {
	return &RegisterHandler{service: service, bodyReadTimeout: 5 * time.Second, cookieSecure: secureCookie}
}

func (h *RegisterHandler) setTicketCookie(c *gin.Context, ticket string, maxAge int) {
	c.SetSameSite(http.SameSiteStrictMode)
	c.SetCookie(registrationCookie, ticket, maxAge, "/api/auth", "", h.cookieSecure, true)
}

func (h *RegisterHandler) RequestEmail(c *gin.Context) {
	req, err := readEmailRequest(c.Writer, c.Request, h.bodyReadTimeout)
	if err != nil {
		status, message := registerRequestError(err)
		if status == http.StatusBadRequest {
			message = "invalid email request"
		}
		c.JSON(status, gin.H{"error": message})
		return
	}
	if err := h.service.RequestEmail(c.Request.Context(), req.Email); err != nil {
		switch {
		case errors.Is(err, identity.ErrInvalidRegistration):
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid email"})
		case errors.Is(err, identity.ErrEmailCooldown):
			c.JSON(http.StatusTooManyRequests, gin.H{"error": "try again in one minute"})
		default:
			log.Printf("registration email failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "could not send verification email"})
		}
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"message": "verification requested"})
}

func (h *RegisterHandler) VerifyEmail(c *gin.Context) {
	req, err := readVerifyEmailRequest(c.Writer, c.Request, h.bodyReadTimeout)
	if err != nil {
		status, message := registerRequestError(err)
		if status == http.StatusBadRequest {
			message = "invalid verification request"
		}
		c.JSON(status, gin.H{"error": message})
		return
	}
	ticket, err := h.service.VerifyEmail(c.Request.Context(), req.Email, req.Token)
	if err != nil {
		if errors.Is(err, identity.ErrInvalidVerification) {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid or expired token"})
		} else {
			log.Printf("email verification failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "verification unavailable"})
		}
		return
	}
	h.setTicketCookie(c, ticket, 600)
	c.JSON(http.StatusOK, gin.H{"email_verified": true, "expires_in": 600})
}

func (h *RegisterHandler) Status(c *gin.Context) {
	ticket, err := c.Cookie(registrationCookie)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"email_verified": false})
		return
	}
	proof, err := h.service.Status(c.Request.Context(), ticket)
	if err != nil {
		if errors.Is(err, identity.ErrInvalidTicket) {
			h.setTicketCookie(c, "", -1)
			c.JSON(http.StatusUnauthorized, gin.H{"email_verified": false})
		} else {
			log.Printf("registration status failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "registration state unavailable"})
		}
		return
	}
	seconds := int(time.Until(proof.ExpiresAt).Seconds())
	if seconds < 0 {
		seconds = 0
	}
	c.JSON(http.StatusOK, gin.H{"email_verified": true, "email": proof.Email, "expires_in": seconds})
}

// Handle finishes registration. The email is NOT accepted from the JSON body.
func (h *RegisterHandler) Handle(c *gin.Context) {
	req, err := readRegisterRequest(c.Writer, c.Request, h.bodyReadTimeout)
	if err != nil {
		status, message := registerRequestError(err)
		c.JSON(status, gin.H{"error": message})
		return
	}
	ticket, err := c.Cookie(registrationCookie)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "email verification required"})
		return
	}
	result, err := h.service.Register(c.Request.Context(), ticket, identity.RegisterInput{
		Username: req.Username, Password: req.Password,
	})
	if err != nil {
		switch {
		case errors.Is(err, identity.ErrInvalidTicket):
			c.JSON(http.StatusUnauthorized, gin.H{"error": "email verification expired"})
		case errors.Is(err, identity.ErrInvalidRegistration):
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid registration input"})
		case errors.Is(err, identity.ErrEmailExists):
			c.JSON(http.StatusConflict, gin.H{"error": "email already exists"})
		case errors.Is(err, identity.ErrUserAlreadyExists):
			c.JSON(http.StatusConflict, gin.H{"error": "username already exists"})
		case errors.Is(err, context.DeadlineExceeded):
			c.JSON(http.StatusGatewayTimeout, gin.H{"error": "registration timed out"})
		default:
			log.Printf("register failed: %v", err)
			c.JSON(http.StatusInternalServerError, gin.H{"error": "internal server error"})
		}
		return
	}
	h.setTicketCookie(c, "", -1)
	c.JSON(http.StatusCreated, gin.H{
		"id": result.ID, "username": result.Username,
		"display_name": result.DisplayName, "email": result.Email,
		"email_verified": true,
	})
}
