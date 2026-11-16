import {
    Box,
    Container,
    Editor,
    ProcessTerminal,
    SelectList,
    Spacer,
    Text,
    TuiMainScreen,
    matchesKey,
    type Component,
    type SelectItem,
    type TUI,
} from "@earendil-works/pi-tui";
import type { AgentEvent } from "../agent/types.js";
import type { UsageTotals } from "../agent/usage.js";
import type { ContentBlock, Message } from "../model/types.js";
import { readToolResult } from "../model/tool-result.js";
import { createOwlLogo } from "./owl-logo.js";
import { owl, owlEditorTheme, owlSelectTheme } from "./theme.js";
import {
    AssistantView,
    NoteView,
    ReasoningView,
    StatusBar,
    ToolView,
    UserPromptView,
    formatTokens,
} from "./transcript.js";

const DEFAULT_SELECT_VISIBLE = 10;

export interface OwlTuiHooks {
    readonly cwd: string;
    readonly modelId: string;
    readonly contextWindow: number;
    readonly runUsage: () => UsageTotals;
    readonly sessionUsage: () => UsageTotals;
    /** Runs one submitted prompt/command. Rejects on failure. */
    readonly onSubmit: (text: string) => Promise<void>;
    /** Abort the running agent. Returns false when nothing was running. */
    readonly onInterrupt: () => boolean;
    /** Called when the UI is closing so the host can persist and exit. */
    readonly onExit: () => void;
}

function textFrom(content: readonly ContentBlock[]): string {
    const parts: string[] = [];
    for (const block of content) {
        switch (block.type) {
            case "text":
                parts.push(block.text);
                break;
            case "image":
                parts.push(`[image ${block.mimeType}]`);
                break;
            case "tool_result":
                if (block.content.length > 0) parts.push(block.content);
                break;
            default:
                break;
        }
    }
    return parts.join("\n");
}

/**
 * Owl's terminal interface, built on `@earendil-works/pi-tui`.
 *
 * The transcript is append-only: every user prompt, model turn, reasoning trace,
 * and tool invocation becomes a component that renders itself for the current
 * width. Rendering is differential, so only changed lines are rewritten.
 */
export class OwlTui {
    private readonly terminal = new ProcessTerminal();
    private readonly tui: TUI = new TuiMainScreen(this.terminal);
    private readonly transcript = new Container();
    private readonly editor: Editor;
    private readonly status: StatusBar;
    private readonly logo: Component;

    private readonly queue: string[] = [];
    private busy = false;
    private expanded = false;
    private contextTokens: number | null = null;
    private sessionLabel = "新会话";

    private currentAssistant?: AssistantView;
    private currentReasoning?: ReasoningView;
    private compactionNote?: NoteView;
    private readonly toolViews = new Map<string, ToolView>();
    private overlayCancel?: () => void;
    private stopped = false;

    constructor(private readonly hooks: OwlTuiHooks) {
        this.logo = createOwlLogo(() => ({
            model: this.hooks.modelId,
            cwd: this.hooks.cwd,
            session: this.sessionLabel,
            hint: "输入消息开始 · /help 查看命令 · ctrl+o 展开工具输出",
        }));

        this.status = new StatusBar(() => ({
            model: this.hooks.modelId,
            contextTokens: this.contextTokens,
            contextWindow: this.hooks.contextWindow,
            runUsage: this.hooks.runUsage(),
            sessionUsage: this.hooks.sessionUsage(),
            busy: this.busy,
            queued: this.queue.length,
        }));

        this.editor = new Editor(this.tui, owlEditorTheme);
        this.editor.onSubmit = (text: string): void => {
            this.editor.addToHistory(text);
            void this.dispatch(text);
        };

        this.tui.addChild(this.logo);
        this.tui.addChild(this.transcript);
        this.tui.addChild(new Spacer(1));
        this.tui.addChild(this.editor);
        this.tui.addChild(this.status);

        this.tui.addInputListener((data: string) => {
            if (matchesKey(data, "ctrl+o")) {
                this.expanded = !this.expanded;
                this.requestRender();
                return { consume: true };
            }
            if (!matchesKey(data, "ctrl+c")) {
                return undefined;
            }
            if (this.overlayCancel !== undefined) {
                this.overlayCancel();
                return { consume: true };
            }
            if (this.busy) {
                if (this.hooks.onInterrupt()) {
                    this.note("已中断当前任务", "warning");
                }
                return { consume: true };
            }
            if (this.editor.getText().length > 0) {
                this.editor.setText("");
                this.requestRender();
                return { consume: true };
            }
            this.stop();
            this.hooks.onExit();
            return { consume: true };
        });
    }

    async start(initialSubmission?: string): Promise<void> {
        this.tui.start();
        this.tui.setFocus(this.editor);
        this.requestRender();
        if (initialSubmission !== undefined) await this.dispatch(initialSubmission);
    }

    stop(): void {
        if (this.stopped) return;
        this.stopped = true;
        this.queue.length = 0;
        this.overlayCancel?.();
        this.tui.stop();
    }

    requestRender(): void {
        if (!this.stopped) this.tui.requestRender();
    }

    /** Replace the transcript with a new session's history. */
    reset(sessionLabel: string, history: readonly Message[], hasCompaction = false, contextTokens: number | null = null): void {
        this.sessionLabel = sessionLabel;
        this.transcript.clear();
        this.contextTokens = contextTokens;
        this.currentAssistant = undefined;
        this.currentReasoning = undefined;
        this.compactionNote = undefined;
        this.toolViews.clear();

        for (const message of history) {
            switch (message.role) {
                case "user":
                    this.toolViews.clear();
                    this.addUserPrompt(message);
                    break;
                case "assistant":
                    this.toolViews.clear();
                    for (const block of message.content) {
                        if (block.type === "reasoning" && block.content.trim()) {
                            const view = new ReasoningView();
                            view.append(block.content);
                            this.transcript.addChild(view);
                        } else if (block.type === "text" && block.text.trim()) {
                            const view = new AssistantView();
                            view.append(block.text);
                            this.transcript.addChild(view);
                        } else if (block.type === "tool_call") {
                            const view = this.addToolView(block.name, block.argumentsJson);
                            view.setState("incomplete");
                            this.toolViews.set(block.callId, view);
                        }
                    }
                    break;
                case "tool":
                    this.showToolResult(message);
                    break;
            }
        }
        if (hasCompaction) {
            this.transcript.addChild(new NoteView("已恢复压缩后的模型上下文"));
        }

        this.logo.invalidate();
        this.requestRender();
    }

    note(text: string, tone: "dim" | "info" | "success" | "error" | "warning" = "dim"): void {
        this.transcript.addChild(new NoteView(text, tone));
        this.requestRender();
    }

    /** Ask the user to pick one item. Resolves to undefined when cancelled. */
    select(title: string, items: SelectItem[], maxVisible = DEFAULT_SELECT_VISIBLE): Promise<SelectItem | undefined> {
        if (this.stopped) return Promise.resolve(undefined);
        return new Promise((resolve) => {
            const list = new SelectList(items, maxVisible, owlSelectTheme);
            const box = Object.assign(new Box(1, 0), {
                handleInput: (data: string) => list.handleInput(data),
            });
            box.addChild(new Text(owl.accentBold(title), 0, 1));
            box.addChild(list);
            box.addChild(new Text(owl.dim("↑↓ 选择 · enter 确认 · esc 取消"), 0, 1));

            const handle = this.tui.showOverlay(box, {
                width: "70%",
                minWidth: 40,
                maxHeight: "70%",
                anchor: "center",
            });

            let settled = false;
            const finish = (item?: SelectItem): void => {
                if (settled) return;
                settled = true;
                this.overlayCancel = undefined;
                handle.hide();
                this.tui.setFocus(this.editor);
                this.requestRender();
                resolve(item);
            };

            list.onSelect = (item: SelectItem): void => finish(item);
            list.onCancel = (): void => finish(undefined);
            this.overlayCancel = (): void => finish(undefined);
            this.requestRender();
        });
    }

    /** Bridge one agent event into the transcript. */
    handleAgentEvent(event: AgentEvent): void {
        switch (event.type) {
            case "context_compaction_start":
                this.clearCompactionNote();
                this.contextTokens = event.estimatedTokensBefore;
                this.compactionNote = new NoteView(
                    event.reason === "overflow" ? "模型报告上下文溢出，正在压缩…" : "正在压缩上下文…",
                    "info",
                );
                this.transcript.addChild(this.compactionNote);
                break;

            case "context_compaction":
                this.clearCompactionNote();
                this.contextTokens = event.contextBudget.estimatedInputTokens;
                this.note(
                    `上下文已压缩 · 预计输入 ${formatTokens(event.estimatedTokensBefore)} → ${formatTokens(this.contextTokens)} tokens`,
                    "success",
                );
                break;

            case "turn_start":
                this.currentAssistant = undefined;
                this.currentReasoning = undefined;
                this.contextTokens = event.contextBudget.estimatedInputTokens;
                break;

            case "context_update":
                this.contextTokens = event.contextBudget.estimatedInputTokens;
                break;

            case "model_event": {
                const modelEvent = event.event;
                if (modelEvent.type === "reasoning_delta") {
                    if (this.currentReasoning === undefined) {
                        this.currentReasoning = new ReasoningView();
                        this.transcript.addChild(this.currentReasoning);
                    }
                    this.currentReasoning.append(modelEvent.delta);
                } else if (modelEvent.type === "text_delta") {
                    if (this.currentAssistant === undefined) {
                        this.currentAssistant = new AssistantView();
                        this.transcript.addChild(this.currentAssistant);
                    }
                    this.currentAssistant.append(modelEvent.delta);
                } else {
                    const { termination } = modelEvent.response;
                    if (termination.kind === "ERROR" && termination.failure.code === "CONTEXT_OVERFLOW") {
                        if (this.currentAssistant) this.transcript.removeChild(this.currentAssistant);
                        if (this.currentReasoning) this.transcript.removeChild(this.currentReasoning);
                        this.currentAssistant = undefined;
                        this.currentReasoning = undefined;
                    }
                }
                break;
            }

            case "tool_start": {
                const view = this.addToolView(event.call.name, event.call.argumentsJson);
                this.toolViews.set(event.call.callId, view);
                break;
            }

            case "tool_update": {
                this.toolViews.get(event.callId)?.setOutput(textFrom(event.result.content));
                break;
            }

            case "tool_end":
                this.showToolResult(event.result.message);
                break;

            case "message_end":
                if (event.message.role === "user") {
                    this.toolViews.clear();
                    this.addUserPrompt(event.message);
                } else if (event.message.role === "tool") {
                    this.showToolResult(event.message, false);
                }
                break;

            case "run_end": {
                this.clearCompactionNote();
                const { stopReason, errMessage, modelCalls } = event.result;
                if (stopReason === "stop") {
                    break;
                }
                if (stopReason === "cancelled") {
                    this.note("已取消", "warning");
                    break;
                }
                const label = stopReason === "context_limit" ? "上下文超限" : stopReason;
                this.note(
                    `${label}${errMessage === undefined ? "" : ` · ${errMessage}`}（${modelCalls} 次模型调用）`,
                    "error",
                );
                break;
            }

            default:
                break;
        }
        this.requestRender();
    }

    private addUserPrompt(message: Message): void {
        const text = textFrom(message.content);
        if (text.trim()) this.transcript.addChild(new UserPromptView(text));
    }

    private addToolView(name: string, argumentsJson?: string): ToolView {
        const view = new ToolView(name, argumentsJson, () => this.expanded);
        this.transcript.addChild(view);
        return view;
    }

    private showToolResult(message: Message, createView = true): void {
        const { result, content } = readToolResult(message);
        const pending = this.toolViews.get(result.callId);
        if (pending === undefined && !createView) return;
        const view = pending ?? this.addToolView(result.name);
        this.toolViews.delete(result.callId);
        view.setOutput(textFrom(content.filter(block => block.type !== "text" || block.text !== "")));
        view.setState(result.isError ? "error" : "ok");
    }

    private clearCompactionNote(): void {
        if (this.compactionNote === undefined) return;
        this.transcript.removeChild(this.compactionNote);
        this.compactionNote = undefined;
    }

    private async dispatch(text: string): Promise<void> {
        const trimmed = text.trim();
        if (this.stopped || trimmed.length === 0) return;

        if (this.busy) {
            this.queue.push(trimmed);
            this.requestRender();
            return;
        }

        this.busy = true;
        this.currentAssistant = undefined;
        this.currentReasoning = undefined;
        this.requestRender();

        try {
            await this.hooks.onSubmit(trimmed);
        } catch (error: unknown) {
            if (!(error instanceof Error) || error.name !== "AbortError") {
                this.note(error instanceof Error ? error.message : String(error), "error");
            }
        } finally {
            this.busy = false;
            this.clearCompactionNote();
            this.currentAssistant = undefined;
            this.currentReasoning = undefined;
            this.requestRender();
        }

        if (this.stopped) return;
        const next = this.queue.shift();
        if (next !== undefined) {
            void this.dispatch(next);
        }
    }
}
