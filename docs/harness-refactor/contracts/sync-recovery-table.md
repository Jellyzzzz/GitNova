# 同步恢复状态表

共用输入：durable syncId/Q/oldL/H/policy/old确认条件/newL/已分配newEpoch。Session指针在最终TX-Y2之前始终指oldL；准备HTTP超时意味着需核对，不等于失败。所有状态写入匹配原syncId gate及意图摘要。

| 崩溃或失败点 | 可信事实 | 重试/恢复动作 | 何时可释放gate |
|---|---|---|---|
| 意图事务未提交 | 无sync行 | 按同requestKey重新受理 | 没有gate |
| PREPARING已提交未checkpoint | 原W仍活动、无Task | 找同sync行继续，不重复newEpoch/newL | 明确取消且未开始未知动作后 |
| checkpoint响应丢 | 可能已PARKED/有固定export | 查原commandId/exportId，拉同包/核对覆盖，不生成新快照身份 | 等checkpoint结论明确 |
| RECOVERY保存前存储失败 | 本地仍唯一副本 | 保留并续期旧实例，不STOP | 明确失败后恢复可执行或留下需核对绑定 |
| ACK丢 | 归档耐久，Worker可能PARKED | 重发同ACK，核对scope/hash/覆盖 | 已知旧状态可恢复后 |
| dirty或carry冲突 | 已有旧归档，Q仍oldL | 记CONFLICT，留W证据；旧实例已parked则下条Task恢复旧归档、新epoch | 标旧Binding不可派发后，条件清自身gate |
| 旧实例STOP/销毁结果不明 | 归档存在，旧写者可能仍活 | 查询管理状态；不挂同一卷启新实例，不假定timeout=gone | 未确定前不转可执行 |
| 新创建响应丢 | 持久createOperationId/newEpoch | 按标签查同实例，不能立即再create | 查明并安全回退后 |
| 新安装半途 | 固定bootstrapId/hash/staging/安装标记 | 对同输入完成安装；不能换H/原地改旧W | 已确定无法完成并保留oldArchive后 |
| 新Worker READY，平台未激活 | 只准备证据，token无Task/发布权 | 查询同Q/L/E/H/config，重试TX-Y2；不可把ready当SUCCEEDED | 最终激活或明确失败回退 |
| TX-Y2提交后HTTP丢 | Q已newL、sync SUCCEEDED | GET原syncId，返回同newL/H，不再次同步 | 提交事务已清自己的gate |
| 激活后清理旧资源失败 | 新线已成功、旧数据留存 | 清理后台重试，不撤销成功同步 | 不重新持有用户sync gate |
| 激活前权限撤销 | 新实例可能已准备，未提交 | 最终当前授权拒绝，归档/清理受控，Q保持oldL | 标可恢复旧绑定后 |

失败回到oldL时不复用已失效epoch；从oldArchive另分配新epoch恢复同W。若实例完全丢失且没有最新可信归档，报RECOVERY_UNAVAILABLE，不声称保住未归档修改。只有确定错误才FAILED/CONFLICT；未知副作用留原阶段errorCode供核对。后台任务通过syncId串行推进，跨Server可用行锁+记录版本做阶段CAS，但不持数据库锁等待网络。
