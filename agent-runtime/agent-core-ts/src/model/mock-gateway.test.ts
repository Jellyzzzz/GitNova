import { test } from "node:test";
import assert from "node:assert/strict";

import { MockGateway } from "./mock-gateway.js";
import type { Request } from "./types.js";

const request: Request = {
    modelCallId: "test-call-1",
    model: { providerId: "mock", modelId: "mock" },
    messages: [],
    tools: [],
    maxOutputTokens: 1024,
    thinkingJson: "{}",
};

test("MockGateway: 正常返回模型响应", async () => {
    const gateway = new MockGateway();
    const controller = new AbortController();

    const response = await gateway.complete(
        request,
        controller.signal
    );

    assert.equal(response.termination.kind, "STOP");
    assert.equal(response.message.role, "assistant");

    assert.deepEqual(response.message.content, [
        {
            type: "text",
            text: "Hello from mock model",
        },
    ]);
});

test("MockGateway: 调用前取消", async () => {
    const gateway = new MockGateway();
    const controller = new AbortController();

    controller.abort();

    await assert.rejects(
        gateway.complete(request, controller.signal),
        { name: "AbortError" }
    );
});

test("MockGateway: 执行过程中取消", async () => {
    const gateway = new MockGateway();
    const controller = new AbortController();

    const pending = gateway.complete(
        request,
        controller.signal
    );

    const timer = setTimeout(() => {
        controller.abort();
    }, 20);

    try {
        await assert.rejects(
            pending,
            { name: "AbortError" }
        );
    } finally {
        clearTimeout(timer);
    }
});