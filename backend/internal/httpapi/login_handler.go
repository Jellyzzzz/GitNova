package httpapi

import (
	"context"
	"errors"
	"log"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

const (
	loginCookie    = "gitnova_session"
	loginCookieAge = 7 * 24 * 60 * 60
	authContextKey = "gitnova.authenticated"
)

type LoginHandler struct {
	service      *identity.LoginService
	cookieSecure bool
}

func NewLoginHandler(service *identity.LoginService, cookieSecure bool) *LoginHandler {
	return &LoginHandler{service: service, cookieSecure: cookieSecure}
}

func (h *LoginHandler) setCookie(c *gin.Context, token string, seconds int) {
	c.SetSameSite(http.SameSiteStrictMode)
	c.SetCookie(loginCookie, token, seconds, "/api", "", h.cookieSecure, true)
}

// Browsers set Origin for cross-site writes; reject a conflicting Origin.
// This assumes the API is hosted under the same origin used by the browser.
func checkWriteOrigin(c *gin.Context) bool {
	if c.Request.Method == http.MethodGet || c.Request.Method == http.MethodHead || c.Request.Method == http.MethodOptions {
		return true
	}
	origin := c.GetHeader("Origin")
	if origin == "" {
		return true
	} // non-browser clients without Origin
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Path != "" {
		return false
	}
	if !strings.EqualFold(u.Host, c.Request.Host) {
		return false
	}
	if c.Request.TLS != nil {
		return u.Scheme == "https"
	}
	return u.Scheme == "http"
}

func (h *LoginHandler) requireOrigin(c *gin.Context) bool {
	if checkWriteOrigin(c) {
		return true
	}
	c.JSON(http.StatusForbidden, gin.H{"error": "invalid_origin"})
	return false
}

func (h *LoginHandler) Login(c *gin.Context) {
	if !h.requireOrigin(c) {
		return
	}
	req, err := readLoginRequest(c.Writer, c.Request, 5*time.Second)
	if err != nil {
		code, message := registerRequestError(err)
		c.JSON(code, gin.H{"error": message})
		return
	}
	token, user, err := h.service.Login(c.Request.Context(), req.Identifier, req.Password, c.Request.UserAgent())
	if err != nil {
		switch {
		case errors.Is(err, identity.ErrInvalidCredentials):
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid_credentials"})
		case errors.Is(err, context.DeadlineExceeded):
			c.JSON(http.StatusGatewayTimeout, gin.H{"error": "login_timeout"})
		default:
			log.Printf("login failed: %v", err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "login_unavailable"})
		}
		return
	}
	h.setCookie(c, token, loginCookieAge)
	c.JSON(http.StatusOK, gin.H{"id": user.ID, "username": user.Username, "display_name": user.DisplayName, "email": user.Email})
}

func (h *LoginHandler) RequireAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !h.requireOrigin(c) {
			c.Abort()
			return
		}
		token, err := c.Cookie(loginCookie)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			c.Abort()
			return
		}
		auth, err := h.service.Authenticate(c.Request.Context(), token)
		if err != nil {
			switch {
			case errors.Is(err, identity.ErrPasswordChanged):
				h.setCookie(c, "", -1)
				c.JSON(http.StatusUnauthorized, gin.H{"error": "password_changed", "message": "密码已修改，请重新登录"})
			case errors.Is(err, identity.ErrUnauthenticated):
				h.setCookie(c, "", -1)
				c.JSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			default:
				log.Printf("authenticate failed: %v", err)
				c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication_unavailable"})
			}
			c.Abort()
			return
		}
		c.Set(authContextKey, auth)
		c.Next()
	}
}

func getAuthenticated(c *gin.Context) identity.Authenticated {
	value, ok := c.Get(authContextKey)
	if !ok {
		panic("authenticated handler mounted without RequireAuth middleware")
	}
	return value.(identity.Authenticated)
}

func (h *LoginHandler) Me(c *gin.Context) {
	auth := getAuthenticated(c)
	u := auth.User
	c.JSON(http.StatusOK, gin.H{"id": u.ID, "username": u.Username, "display_name": u.DisplayName, "email": u.Email})
}

func (h *LoginHandler) Logout(c *gin.Context) {
	auth := getAuthenticated(c)
	if err := h.service.Logout(c.Request.Context(), auth); err != nil {
		log.Printf("logout failed: %v", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "logout_unavailable"})
		return
	}
	h.setCookie(c, "", -1)
	c.Status(http.StatusNoContent)
}

func (h *LoginHandler) ListSessions(c *gin.Context) {
	auth := getAuthenticated(c)
	records, err := h.service.ListSessions(c.Request.Context(), auth)
	if err != nil {
		log.Printf("list sessions: %v", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "sessions_unavailable"})
		return
	}
	out := make([]gin.H, 0, len(records))
	for _, item := range records {
		out = append(out, gin.H{
			"id": item.ID, "created_at": item.CreatedAt, "expires_at": item.ExpiresAt,
			"user_agent": item.UserAgent, "current": item.ID == auth.Session.ID,
		})
	}
	c.JSON(http.StatusOK, gin.H{"sessions": out})
}

func (h *LoginHandler) RevokeSession(c *gin.Context) {
	auth := getAuthenticated(c)
	id := c.Param("id")
	if err := h.service.RevokeSession(c.Request.Context(), auth, id); err != nil {
		if errors.Is(err, identity.ErrSessionNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "session_not_found"})
		} else {
			log.Printf("revoke session: %v", err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "revoke_unavailable"})
		}
		return
	}
	if auth.Session.ID == id {
		h.setCookie(c, "", -1)
	}
	c.Status(http.StatusNoContent)
}

func (h *LoginHandler) LogoutAll(c *gin.Context) {
	auth := getAuthenticated(c)
	if err := h.service.LogoutAll(c.Request.Context(), auth); err != nil {
		log.Printf("logout all: %v", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "logout_unavailable"})
		return
	}
	h.setCookie(c, "", -1)
	c.Status(http.StatusNoContent)
}

func (h *LoginHandler) ChangePassword(c *gin.Context) {
	req, err := readChangePasswordRequest(c.Writer, c.Request, 5*time.Second)
	if err != nil {
		status, message := registerRequestError(err)
		c.JSON(status, gin.H{"error": message})
		return
	}
	auth := getAuthenticated(c)
	err = h.service.ChangePassword(c.Request.Context(), auth, req.CurrentPassword, req.NewPassword)
	if err != nil {
		switch {
		case errors.Is(err, identity.ErrInvalidCredentials):
			c.JSON(http.StatusBadRequest, gin.H{"error": "incorrect_current_password"})
		case errors.Is(err, identity.ErrInvalidPassword):
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_new_password"})
		case errors.Is(err, identity.ErrPasswordChanged):
			h.setCookie(c, "", -1)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "password_changed", "message": "密码已修改，请重新登录"})
		case errors.Is(err, identity.ErrUnauthenticated):
			h.setCookie(c, "", -1)
			c.JSON(http.StatusUnauthorized, gin.H{"error": "authentication_required"})
		default:
			log.Printf("change password: %v", err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "password_update_unavailable"})
		}
		return
	}
	h.setCookie(c, "", -1)
	c.JSON(http.StatusOK, gin.H{"code": "password_changed", "message": "密码已修改，请重新登录"})
}
