package httpapi

import (
	"net/http"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

func NewRouter(registerService *identity.RegisterService, secureCookie bool) *gin.Engine {
	r := gin.Default()
	api := r.Group("/api")
	api.GET("/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "pong"})
	})
	h := NewRegisterHandler(registerService, secureCookie)
	api.POST("/auth/registration/email", h.RequestEmail)
	api.POST("/auth/registration/verify", h.VerifyEmail)
	api.GET("/auth/registration/status", h.Status)
	api.POST("/auth/register", h.Handle)
	return r
}
