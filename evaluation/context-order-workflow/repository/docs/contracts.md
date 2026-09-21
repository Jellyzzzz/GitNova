# 唯一业务契约

## MONEY-01
金额使用 long 分，非负；百分比为0…100。先将所有商品数量与单价聚合成 subtotal，
再对 subtotal * percent / 100 做 HALF_UP（恰好半分向上），不能逐商品先舍入。
discount + net = subtotal。中间计算必须检查溢出，不得悄悄变负或改用浮点。

## SHIPPING-01
net >= 5000 分免运费，否则600分。免运费依据折后净额，不是 subtotal。
这包含100%优惠的订单：net=0，但运费仍为600。目的地只影响请求身份，不影响运费。

## INVENTORY-01
SKU 必须存在，数量正整数，允许同一 SKU 多行并合并数量。预占要么全部成功，要么全部
可用量不变；不足或未知 SKU 不产生 Reservation。释放同一 Reservation 最多生效一次。

## CANCEL-01
只有订单所属用户能访问。PAID 不可取消，拒绝时库存、事件数、订单版本均不变。
未知订单或跨用户请求同样不能产生副作用。

## CANCEL-02
RESERVED -> CANCELLED 释放预占并记录一次 ORDER_CANCELLED，版本加一。
重复取消返回 changed=false，不能再释放库存、写事件或推进版本。

## REQUEST-01
逻辑键为 (actorId, requestId)。fingerprint 包含 destination、percent、按 SKU 排序后的
聚合数量；相同行重排或拆分视为等价。相同逻辑键与意图返回原 Receipt（replayed=true），
不重复扣库存、保存订单或写事件。相同键不同意图必须冲突且零副作用。
actorId、requestId 的合法格式由 PlaceOrder 校验；两个不同用户的相同 requestId 不冲突。
同一键的原订单后来取消，重试仍返回原成功 Receipt，不自动创建新订单。

## BATCH-01
输入行号1起算；每行都有一份 RowResult，顺序与输入一致。坏行标记 REJECTED 后继续处理。
合法重放标记 REPLAYED，新建标记 ACCEPTED。返回的数量必须等于输入行数。
batchId 只是报告标签，不提供全批幂等；每行的 requestId 才是逻辑执行身份。

## SCOPE-01
公共 API、商品价格、CSV 格式、现有测试、文档不能为了绕过断言而修改。
构建产物只放 /tmp。此实现顺序执行，不要求线程安全或真实事务恢复。
