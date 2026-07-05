package com.gitnova.dto;

import lombok.Data;

/**
 * Code Review 评论 DTO
 */
@Data
public class ReviewCommentDTO {

    private String file;
    private Integer line;
    private String severity;   // info | warning | error
    private String comment;
}
