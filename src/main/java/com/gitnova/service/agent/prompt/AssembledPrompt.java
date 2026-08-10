package com.gitnova.service.agent.prompt;

public record AssembledPrompt(String version,String systemText) {
    public AssembledPrompt{
        if(version==null||version.isBlank()) throw new IllegalArgumentException("version must not be null");
        if(systemText==null||systemText.isBlank()) throw new IllegalArgumentException("systemText must not be null");
    }
}
