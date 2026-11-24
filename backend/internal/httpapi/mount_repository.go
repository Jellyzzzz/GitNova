package httpapi

import (
	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/Jellyzzzz/GitNova/backend/internal/repository"
	"github.com/gin-gonic/gin"
)

func MountRepositoryRoutes(r *gin.Engine, service *repository.Service, login *identity.LoginService, secureCookie bool) {
	h := NewRepositoryHandler(service)

	group := r.Group("api/repositories")
	group.Use(NewLoginHandler(login, secureCookie).RequireAuth())
	group.POST("", h.Create)
	group.GET("", h.ListMine)
	group.GET("/:id", h.GetMine)
}
