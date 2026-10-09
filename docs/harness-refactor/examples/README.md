# 示例与调用层次
`public-submit.json` 是浏览器POST `/api/repos/{r}/agent/sessions/{q}/tasks` 的body；另传`Idempotency-Key`。只含message、expectedWorklineId、expectedSessionVersion。它不是AgentCommand。
`submit-task.json` 和 fixtures/*.json 是Server发Worker的私有命令，使用schemaVersion=2与session/workline/epoch；不能直接拿给公共Task接口。
`merge-request.json`、`sync-carry.json`、`sync-discard.json` 是公共业务接口样例，字段按16和各Schema。
`runtime-config.json` 是平台受信运行配置，不允许从任务body覆盖。ID/HEAD/Digest/时间都是合成测试值，生产由平台生成/验证；fixtures测试格式不代表这些身份已授权或deadline现在有效。
`events.sse` 是v2原始私有SSE样例；公共页面必须经白名单投影。

accepted.json、worker-health.json、task-view.json是私有响应样例，各有同名用途Schema。bootstrap-manifest.json只用于字段结构校验；其摘要是合成值，不作为可解包的真实恢复档案。events.sse同样是协议fixture，不代表真实任务通过。
