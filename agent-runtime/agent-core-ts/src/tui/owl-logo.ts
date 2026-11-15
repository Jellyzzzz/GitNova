import type { Component } from "@earendil-works/pi-tui";
import { truncateToWidth, visibleWidth } from "@earendil-works/pi-tui";
import { owl } from "./theme.js";

/**
 * The Owl mark. Kept to a fixed 9-column block so it composes predictably with
 * the metadata column to its right.
 */
const LOGO: readonly (readonly [line: string, render: (text: string) => string])[] = [
    ["  /\\_/\\  ", owl.accent],
    [" ( o.o ) ", owl.accent],
    ["  > ^ <  ", owl.eye],
    [" /|   |\\ ", owl.accentDeep],
    ["(_|   |_)", owl.accentDeep],
];

const LOGO_WIDTH = 9;
const GAP = 3;
/** Below this width the metadata stacks under the mark instead of beside it. */
const SIDE_BY_SIDE_MIN = LOGO_WIDTH + GAP + 22;

export interface OwlLogoInfo {
    readonly model: string;
    readonly cwd: string;
    readonly session: string;
    readonly hint: string;
}

function metaLines(info: OwlLogoInfo, budget: number): string[] {
    const label = (text: string): string => owl.dim(text.padEnd(8));
    const value = (text: string): string =>
        truncateToWidth(text, Math.max(1, budget - 8), "…");

    const rows: string[] = [
        `${owl.accentBold("OWL")}${owl.dim(" · owl agent runtime")}`,
        "",
        `${label("model")}${owl.text(info.model)}`,
        `${label("cwd")}${owl.muted(value(info.cwd))}`,
        `${label("session")}${owl.muted(info.session)}`,
        "",
        owl.dim(info.hint),
    ];
    return rows.map((row) => truncateToWidth(row, budget, "…"));
}

function stackedLines(info: OwlLogoInfo, width: number): string[] {
    const lines: string[] = [];
    for (const [line, render] of LOGO) {
        lines.push(render(line));
    }
    lines.push("");
    return lines.concat(metaLines(info, width));
}

function sideBySideLines(info: OwlLogoInfo, width: number): string[] {
    const meta = metaLines(info, width - LOGO_WIDTH - GAP);
    const rows = Math.max(LOGO.length, meta.length);
    const lines: string[] = [];

    for (let index = 0; index < rows; index += 1) {
        const logo = LOGO[index];
        const left = logo === undefined ? " ".repeat(LOGO_WIDTH) : logo[1](logo[0]);
        const right = meta[index] ?? "";
        const padding = " ".repeat(Math.max(0, LOGO_WIDTH - visibleWidth(logo?.[0] ?? "")) + GAP);
        lines.push(truncateToWidth(`${left}${padding}${right}`, width, "…"));
    }
    return lines;
}

/**
 * Header mark for the transcript. Reads its metadata lazily so a session switch
 * only needs an `invalidate()`.
 */
export function createOwlLogo(getInfo: () => OwlLogoInfo): Component {
    let cachedWidth = -1;
    let cachedLines: string[] = [];

    return {
        render(width: number): string[] {
            if (width === cachedWidth) {
                return cachedLines;
            }
            const info = getInfo();
            cachedWidth = width;
            cachedLines =
                width < SIDE_BY_SIDE_MIN ? stackedLines(info, width) : sideBySideLines(info, width);
            return cachedLines;
        },
        invalidate(): void {
            cachedWidth = -1;
            cachedLines = [];
        },
    };
}
