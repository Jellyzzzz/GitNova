import {
    backgroundAnsi,
    foregroundAnsi,
    getTerminalColorMode,
    rgbColor,
    type EditorTheme,
    type MarkdownTheme,
    type SelectListTheme,
} from "@earendil-works/pi-tui";

const RESET = "\x1b[0m";
const colorMode = getTerminalColorMode();

type Attribute = "bold" | "dim" | "italic" | "underline" | "strikethrough";

const ATTRIBUTE_CODES: Record<Attribute, string> = {
    bold: "\x1b[1m",
    dim: "\x1b[2m",
    italic: "\x1b[3m",
    underline: "\x1b[4m",
    strikethrough: "\x1b[9m",
};

function toRgb(hex: string): [number, number, number] {
    const value = hex.startsWith("#") ? hex.slice(1) : hex;
    if (value.length !== 6) {
        throw new Error(`Invalid hex color: ${hex}`);
    }
    return [
        Number.parseInt(value.slice(0, 2), 16),
        Number.parseInt(value.slice(2, 4), 16),
        Number.parseInt(value.slice(4, 6), 16),
    ];
}

/** Owl palette: nocturnal amber and ash, with muted moss/sky accents. */
export const palette = {
    feather: "#E4A55C",
    featherDeep: "#B9834A",
    featherSoft: "#8C6537",
    eye: "#F6D08A",
    text: "#E8E2D6",
    muted: "#948E80",
    ash: "#6A655B",
    ember: "#D9787E",
    moss: "#8CBE96",
    amber: "#E3C179",
    sky: "#86AEDA",
    code: "#A6C48A",
    userBg: "#262320",
    toolBg: "#1F1D1A",
} as const;

export function fg(hex: string, ...attributes: Attribute[]): (text: string) => string {
    const prefix =
        foregroundAnsi(rgbColor(...toRgb(hex)), colorMode) +
        attributes.map((attribute) => ATTRIBUTE_CODES[attribute]).join("");
    return (text: string): string => (text.length === 0 ? "" : prefix + text + RESET);
}

export function bg(hex: string, ...attributes: Attribute[]): (text: string) => string {
    const prefix =
        backgroundAnsi(rgbColor(...toRgb(hex)), colorMode) +
        attributes.map((attribute) => ATTRIBUTE_CODES[attribute]).join("");
    return (text: string): string => (text.length === 0 ? "" : prefix + text + RESET);
}

/** Semantic styles shared by every Owl TUI component. */
export const owl = {
    accent: fg(palette.feather),
    accentBold: fg(palette.feather, "bold"),
    accentSoft: fg(palette.featherSoft),
    accentDeep: fg(palette.featherDeep),
    eye: fg(palette.eye, "bold"),
    text: fg(palette.text),
    muted: fg(palette.muted),
    dim: fg(palette.ash),
    dimItalic: fg(palette.ash, "italic"),
    success: fg(palette.moss),
    error: fg(palette.ember),
    warning: fg(palette.amber),
    info: fg(palette.sky),
    code: fg(palette.code),
    bold: (text: string): string => `\x1b[1m${text}${RESET}`,
    italic: (text: string): string => `\x1b[3m${text}${RESET}`,
    underline: (text: string): string => `\x1b[4m${text}${RESET}`,
    strikethrough: (text: string): string => `\x1b[9m${text}${RESET}`,
    userBg: bg(palette.userBg),
    toolBg: bg(palette.toolBg),
} as const;

/** Markdown styling for model output. */
export const owlMarkdownTheme: MarkdownTheme = {
    heading: owl.accent,
    link: fg(palette.sky, "underline"),
    linkUrl: owl.dim,
    code: owl.code,
    codeBlock: owl.text,
    codeBlockBorder: owl.dim,
    quote: owl.muted,
    quoteBorder: owl.accentSoft,
    hr: owl.dim,
    listBullet: owl.accent,
    bold: owl.bold,
    italic: owl.italic,
    strikethrough: owl.strikethrough,
    underline: owl.underline,
    codeBlockIndent: "  ",
};

/** Selection list styling used by the editor autocomplete and dialogs. */
export const owlSelectTheme: SelectListTheme = {
    selectedPrefix: fg(palette.feather, "bold"),
    selectedText: fg(palette.feather, "bold"),
    description: owl.muted,
    scrollInfo: owl.dim,
    noMatch: owl.dimItalic,
};

/** Editor styling: an amber rule marks the input area. */
export const owlEditorTheme: EditorTheme = {
    borderColor: owl.accentSoft,
    selectList: owlSelectTheme,
};
