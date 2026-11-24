package httpapi

import (
	"context"
	"errors"
	"log"
	"net/http"
	"time"

	"github.com/Jellyzzzz/GitNova/backend/internal/repository"
	"github.com/gin-gonic/gin"
)

type RepositoryHandler struct {
	service *repository.Service
}

func NewRepositoryHandler(service *repository.Service) *RepositoryHandler {
	return &RepositoryHandler{service: service}
}

type createRepositoryRequest struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Visibility  string `json:"visibility"`
}

func (h *RepositoryHandler) Create(c *gin.Context) {
	var req createRepositoryRequest
	err := readRegistrationJSON(c.Writer, c.Request, 5*time.Second, 4<<10,
		map[string]*string{
			"name":        &req.Name,
			"description": &req.Description,
			"visibility":  &req.Visibility,
		}, &req)
	if err != nil {
		status, message := registerRequestError(err)
		c.JSON(status, gin.H{"error": message})
		return
	}
	auth := getAuthenticated(c)
	item, err := h.service.Create(c.Request.Context(), auth.User.ID, repository.CreateInput{Name: req.Name, Description: req.Description, Visibility: repository.Visibility(req.Visibility)})
	if err != nil {
		switch {
		case errors.Is(err, repository.ErrInvalidRepository):
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid_repository"})
		case errors.Is(err, repository.ErrNameTaken):
			c.JSON(http.StatusConflict, gin.H{"error": "repository_name_taken"})
		case errors.Is(err, context.DeadlineExceeded):
			c.JSON(http.StatusGatewayTimeout, gin.H{"error": "repository_creation_timeout"})
		default:
			log.Printf("create repository failed: %v", err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "repository_creation_unavailable"})
		}
		return
	}
	c.JSON(http.StatusCreated, item)
}

func (h *RepositoryHandler) ListMine(c *gin.Context) {
	auth := getAuthenticated(c)
	items, err := h.service.ListMine(c.Request.Context(), auth.User.ID)
	if err != nil {
		log.Printf("list repositories failed: %v", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "repositories_unavailable"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"repositories": items})
}

func (h *RepositoryHandler) GetMine(c *gin.Context) {
	auth := getAuthenticated(c)
	item, err := h.service.GetMine(c.Request.Context(), auth.User.ID, c.Param("id"))
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			c.JSON(http.StatusNotFound, gin.H{"error": "repository_not_found"})
		} else {
			log.Printf("get repository failed: %v", err)
			c.JSON(http.StatusServiceUnavailable, gin.H{"error": "repository_unavailable"})
		}
		return
	}
	c.JSON(http.StatusOK, item)
}
