import {test} from "node:test";
import assert from "node:assert/strict";
import {stripVTControlCharacters} from "node:util";
import {StatusBar} from "./transcript.js";

test("status bar shows estimated context, current run and total usage",()=>{
    const status=new StatusBar(()=>({
        model:"deepseek-flash",contextTokens:26700,contextWindow:1000000,
        runUsage:{reportedTokens:3200,responses:2,missingTotals:0},
        sessionUsage:{reportedTokens:85900,responses:7,missingTotals:1},busy:false,queued:0,
    }));
    const wide=stripVTControlCharacters(status.render(100)[0]);
    assert.match(wide,/ctx≈26\.7k\/1\.0M/);
    assert.match(wide,/本轮 \+3\.2k/);
    assert.match(wide,/会话累计 85\.9k\?/);
    const narrow=stripVTControlCharacters(status.render(48)[0]);
    assert.match(narrow,/轮\+3\.2k/);
    assert.match(narrow,/会85\.9k\?/);
});
