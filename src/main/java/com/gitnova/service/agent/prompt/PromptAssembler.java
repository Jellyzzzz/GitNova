package com.gitnova.service.agent.prompt;

import com.gitnova.service.agent.runtime.AgentRunContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PromptAssembler {
    /**
     * Identifies the server-controlled instruction template, not a repository or revision.
     * Change it whenever a prompt policy change intentionally alters agent behavior.
     */
    public static final String PROMPT_VERSION = "cloud-agent-system-baseline-2026-08-24";

    private final List<PromptSection> sections;

    public PromptAssembler(List<PromptSection> sections) {
        Objects.requireNonNull(sections, "sections must not be null");
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("sections must not be empty");
        }

        List<PromptSection> copied = new ArrayList<>(sections.size());
        Set<String> keys = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (PromptSection section : sections) {
            Objects.requireNonNull(section, "section must not be null");
            String key = Objects.requireNonNull(section.key(), "section key must not be null");
            if (key.isBlank()) {
                throw new IllegalArgumentException("section key must not be blank");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("duplicate prompt section key: " + key);
            }
            if (!orders.add(section.order())) {
                throw new IllegalArgumentException("duplicate prompt section order: " + section.order());
            }
            copied.add(section);
        }
        copied.sort(Comparator.comparingInt(PromptSection::order));
        this.sections = List.copyOf(copied);
    }

    public AssembledPrompt assemble(AgentRunContext context) {
        Objects.requireNonNull(context, "context must not be null");
        String text = sections.stream()
                .map(section -> render(section, context))
                .collect(Collectors.joining("\n\n"));
        return new AssembledPrompt(PROMPT_VERSION, text);
    }

    private String render(PromptSection section, AgentRunContext context) {
        String fragment = Objects.requireNonNull(
                section.render(context),
                "prompt section '" + section.key() + "' returned null"
        ).strip();
        if (fragment.isBlank()) {
            throw new IllegalArgumentException(
                    "prompt section '" + section.key() + "' must not render blank text"
            );
        }
        return fragment;
    }
}
