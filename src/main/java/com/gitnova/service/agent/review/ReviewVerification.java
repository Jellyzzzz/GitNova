package com.gitnova.service.agent.review;

import java.util.List;
import java.util.Objects;

/** Structured verifier decision consumed by AgentRuntime. */
public record ReviewVerification(
        boolean accepted,
        boolean correctable,
        List<String> feedback
) {
    public ReviewVerification {
        Objects.requireNonNull(feedback,"feedback must not be null");
        feedback=List.copyOf(feedback);
        if(accepted&&(!feedback.isEmpty()||correctable)){
            throw new IllegalArgumentException("accepted verification cannot contain correction feedback");
        }
        if(!accepted&&feedback.isEmpty()){
            throw new IllegalArgumentException("rejected verification must contain feedback");
        }
    }

    public static ReviewVerification accept(){
        return new ReviewVerification(true,false,List.of());
    }

    public static ReviewVerification rejected(boolean correctable,List<String> feedback){
        return new ReviewVerification(false,correctable,feedback);
    }
}
