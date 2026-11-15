import type{Usage} from "../model/types.js";
export type UsageTotals={
    readonly reportedTokens:number;
    readonly responses:number;
    readonly missingTotals:number;
}
export function createUsageTotals():UsageTotals{
    return {reportedTokens:0,responses:0,missingTotals:0};
}
export function addUsageTotals(totals:UsageTotals,usage:Usage|null):UsageTotals{
    const tokens=usage?.totalTokens??null;
    return {
        reportedTokens:totals.reportedTokens+(tokens??0),
        responses:totals.responses+1,
        missingTotals:totals.missingTotals+(tokens==null?1:0),
    };
}
