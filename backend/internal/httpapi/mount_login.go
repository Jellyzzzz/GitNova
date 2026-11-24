package httpapi

import (
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

// MountLoginRoutes preserves the existing NewRouter registration signature and tests.
func MountLoginRoutes(r *gin.Engine, login *identity.LoginService, secureCookie bool) {
	h := NewLoginHandler(login, secureCookie)
	auth := r.Group("/api/auth")
	auth.POST("/login", h.Login)

	protected := auth.Group("")
	protected.Use(h.RequireAuth())
	protected.GET("/me", h.Me)
	protected.POST("/logout", h.Logout)
	protected.POST("/logout-all", h.LogoutAll)
	protected.GET("/sessions", h.ListSessions)
	protected.DELETE("/sessions/:id", h.RevokeSession)
	protected.POST("/password", h.ChangePassword)
}
