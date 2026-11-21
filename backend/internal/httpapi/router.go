package httpapi

import (
	"net/http"

	"github.com/Jellyzzzz/GitNova/backend/internal/identity"
	"github.com/gin-gonic/gin"
)

func NewRouter(registerService *identity.RegisterService) *gin.Engine{
	r:=gin.Default()
	api:=r.Group("api/")

	api.GET("/ping",func(c *gin.Context){
		c.JSON(http.StatusOK, gin.H{
            "message": "pong",
        })
	})
	handler:=NewRegisterHandler(registerService)
	api.POST("auth/register",handler.Handle)
	return r;
}