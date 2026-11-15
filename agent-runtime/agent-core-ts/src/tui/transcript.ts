import type { Component } from "@earendil-works/pi-tui";
import {
    Markdown,
    truncateToWidth,
    visibleWidth,
    wrapTextWithAnsi,
} from "@earendil-works/pi-tui";
import { owl, owlMarkdownTheme } from "./theme.js";

const COLLAPSED_OUTPUT_LINES = 10;
const EXPANDED_OUTPUT_LINES = 400;

export function formatTokens(tokens: number): string {
    if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`;
    if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}k`;
    return String(tokens);
}

type Tone = "dim" | "info" | "success" | "error" | "warning";

const TONE_MARK: Record<Tone, string> = {
    dim: "·",
    info: "·",
    success: "✓",
    error: "✕",
    warning: "!",
};

const TONE_STYLE: Record<Tone, (text: string) => string> = {
    dim: owl.dim,
    info: owl.info,
    success: owl.success,
    error: owl.error,
    warning: owl.warning,
};

/** A single dim/coloured line of context: notices, errors, command output. */
export class NoteView implements Component {
    constructor(
        private text: string,
        private readonly tone: Tone = "dim",
    ) {}

    render(width: number): string[] {
        const style = TONE_STYLE[this.tone];
        const body = wrapTextWithAnsi(this.text, Math.max(1, width - 2));
        const gutter = `${TONE_MARK[this.tone]} `;
        return body.map((line, index) => truncateToWidth(`${index === 0 ? style(gutter) : "  "}${style(line)}`, width, "…"));
    }

    invalidate(): void {}
}

/** A submitted user prompt, marked with the Owl chevron. */
export class UserPromptView implements Component {
    private cachedWidth = -1;
    private cachedLines: string[] = [];

    constructor(private readonly text: string) {}

    render(width: number): string[] {
        if (this.cachedWidth === width) {
            return this.cachedLines;
        }
        const body = wrapTextWithAnsi(this.text, Math.max(1, width - 2));
        this.cachedWidth = width;
        this.cachedLines = body.map((line, index) =>
            truncateToWidth(`${owl.accent("❯ ")}${owl.text(line)}`, width, "…"),
        );
        return this.cachedLines;
    }

    invalidate(): void {
        this.cachedWidth = -1;
        this.cachedLines = [];
    }
}

/** Streaming assistant answer rendered as Markdown. */
export class AssistantView implements Component {
    private readonly markdown = new Markdown("", 0, 0, owlMarkdownTheme, {
        color: owl.text,
    });
    private text = "";

    append(delta: string): void {
        this.text += delta;
        this.markdown.setText(this.text);
    }

    get hasContent(): boolean {
        return this.text.trim().length > 0;
    }

    render(width: number): string[] {
        return this.markdown.render(width);
    }

    invalidate(): void {
        this.markdown.invalidate();
    }
}

/** Streaming reasoning trace, kept dim and tail-limited so it never floods the transcript. */
export class ReasoningView implements Component {
    private text = "";
    private cachedWidth = -1;
    private cachedLines: string[] = [];

    static readonly MAX_LINES = 6;

    append(delta: string): void {
        this.text += delta;
        this.invalidate();
    }

    render(width: number): string[] {
        if (this.cachedWidth === width) {
            return this.cachedLines;
        }
        const raw = this.text.replace(/\r\n?/g, "\n").split("\n").filter((line) => line.trim().length > 0);
        const hidden = Math.max(0, raw.length - ReasoningView.MAX_LINES);
        const visible = hidden > 0 ? raw.slice(raw.length - ReasoningView.MAX_LINES) : raw;

        const lines: string[] = [truncateToWidth(`${owl.dimItalic("~ thinking")}`, width, "…")];
        if (hidden > 0) {
            lines.push(truncateToWidth(`  ${owl.dim(`│ ⋯ ${hidden} earlier lines`)}`, width, "…"));
        }
        for (const line of visible) {
            for (const wrapped of wrapTextWithAnsi(line, Math.max(1, width - 4))) {
                lines.push(truncateToWidth(`  ${owl.dim("│ ")}${owl.dimItalic(wrapped)}`, width, "…"));
            }
        }

        this.cachedWidth = width;
        this.cachedLines = lines;
        return lines;
    }

    invalidate(): void {
        this.cachedWidth = -1;
        this.cachedLines = [];
    }
}

function summarizeCall(argumentsJson: string): string {
    try {
        const args: unknown = JSON.parse(argumentsJson);
        if (typeof args === "object" && args !== null) {
            const record = args as Record<string, unknown>;
            for (const key of ["command", "path", "pattern", "query", "url"]) {
                const value = record[key];
                if (typeof value === "string" && value.trim().length > 0) {
                    return value.replace(/\s+/g, " ").trim();
                }
            }
        }
    } catch {
        // Fall through to the raw argument text.
    }
    return argumentsJson;
}

/**
 * One tool invocation: a header line plus its (streaming) output.
 * `expanded` is read on every render so a global ctrl+o toggle applies live.
 */
export class ToolView implements Component {
    private output = "";
    private state: "running" | "ok" | "error" | "incomplete" = "running";
    private readonly summary: string;
    private cachedWidth = -1;
    private cachedExpanded = false;
    private cachedLines: string[] = [];

    constructor(
        private readonly name: string,
        argumentsJson: string | undefined,
        private readonly isExpanded: () => boolean,
    ) {
        this.summary = argumentsJson === undefined ? "" : summarizeCall(argumentsJson);
    }

    setOutput(text: string): void {
        const normalized = text.replace(/\r\n?/g, "\n").replace(/\n+$/, "");
        if (normalized === this.output) return;
        this.output = normalized;
        this.invalidate();
    }

    setState(state: ToolView["state"]): void {
        if (state === this.state) return;
        this.state = state;
        this.invalidate();
    }

    render(width: number): string[] {
        const expanded = this.isExpanded();
        if (this.cachedWidth === width && this.cachedExpanded === expanded) {
            return this.cachedLines;
        }

        const glyph = {
            running: owl.warning("◍"),
            ok: owl.success("●"),
            error: owl.error("●"),
            incomplete: owl.warning("!"),
        }[this.state];
        const status = this.state === "incomplete" ? owl.warning(" · 未记录结果") : "";
        const header = truncateToWidth(
            `${glyph} ${owl.accentBold(this.name)}${status} ${owl.muted(this.summary)}`,
            width,
            "…",
        );

        const lines: string[] = [header];
        if (this.output.trim().length > 0) {
            const raw = this.output.split("\n");
            const limit = expanded ? EXPANDED_OUTPUT_LINES : COLLAPSED_OUTPUT_LINES;
            const hidden = Math.max(0, raw.length - limit);
            const visible = hidden > 0 ? raw.slice(raw.length - limit) : raw;

            if (hidden > 0 && !expanded) {
                lines.push(
                    truncateToWidth(
                        `  ${owl.dim(`│ ⋯ ${hidden} earlier lines`)} ${owl.dimItalic("(ctrl+o)")}`,
                        width,
                        "…",
                    ),
                );
            }

            const bodyStyle = this.state === "error" ? owl.muted : owl.dim;
            for (const line of visible) {
                lines.push(truncateToWidth(`  ${bodyStyle("│ ")}${bodyStyle(line)}`, width, "…"));
            }

            if (hidden > 0 && expanded && raw.length > EXPANDED_OUTPUT_LINES) {
                lines.push(truncateToWidth(`  ${owl.dim(`│ ⋯ ${raw.length - EXPANDED_OUTPUT_LINES} more lines`)}`, width, "…"));
            }
        }

        this.cachedWidth = width;
        this.cachedExpanded = expanded;
        this.cachedLines = lines;
        return lines;
    }

    invalidate(): void {
        this.cachedWidth = -1;
        this.cachedLines = [];
    }
}

export interface StatusState {
    readonly model: string;
    readonly contextTokens: number | null;
    readonly contextWindow: number;
    readonly sessionTokens: number;
    readonly busy: boolean;
    readonly queued: number;
}

/** One-line status footer under the editor. */
export class StatusBar implements Component {
    constructor(private readonly read: () => StatusState) {}

    render(width: number): string[] {
        const state = this.read();

        const dot = state.busy ? owl.warning("◍") : owl.accent("●");
        const left = `${dot} ${owl.accentBold("owl")} ${owl.dim("·")} ${owl.muted(state.model)}${
            state.queued > 0 ? ` ${owl.warning(`+${state.queued} queued`)}` : ""
        }`;

        const context =
            state.contextTokens === null
                ? owl.dim(`ctx —/${formatTokens(state.contextWindow)}`)
                : owl.dim(
                      `ctx ${formatTokens(state.contextTokens)}/${formatTokens(state.contextWindow)} ${(
                          (state.contextTokens / state.contextWindow) *
                          100
                      ).toFixed(1)}%`,
                  );

        const right = `${context} ${owl.dim("·")} ${owl.dim(`↑${formatTokens(state.sessionTokens)}`)}`;
        const padding = " ".repeat(Math.max(1, width - visibleWidth(left) - visibleWidth(right)));
        return [truncateToWidth(`${left}${padding}${right}`, width, "…")];
    }

    invalidate(): void {}
}

/** A dim horizontal separator between transcript sections. */
export class Rule implements Component {
    constructor(private readonly label?: string) {}

    render(width: number): string[] {
        if (this.label === undefined || width < 8) {
            return [owl.dim("─".repeat(Math.max(1, width)))];
        }
        const text = owl.dimItalic(` ${this.label} `);
        const remaining = Math.max(0, width - visibleWidth(text));
        return [owl.dim("─".repeat(remaining)) + text];
    }

    invalidate(): void {}
}
