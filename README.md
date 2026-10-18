# MineStock

MineStock 是一个 Minecraft 股票模拟交易插件，让玩家在游戏里用服务器经济货币交易真实市场的 A 股、港股、美股，以及外汇、期货与本地生成的模拟盘。

## 功能概览

- **实时行情** — A 股、港股、美股接入公开行情源，查询价格、涨跌幅与日内走势
- **K 线图表** — 在聊天框内渲染块状 K 线图，逐格 hover 可查看当日 OHLC
- **今日推荐** — 管理员股票池或全自动市场筛选，点击代码直达详情页
- **买入 / 卖出** — 聊天框或 GUI 下单，带价格确认与手续费计算
- **做空 / 回补** — 股票、外汇、期货、模拟盘四个交易面都能开空。空头与杠杆多头共用同一套保证金、追保与强平规则；平仓只动对应方向，`/mstock sell` 不会碰空单，`/mstock cover` 不会碰多单
- **持仓总览** — 股票、保证金、期货集中在一个界面，支持分页、批量取价与一键平仓
- **杠杆交易** — 玩家自选 1x ~ 100x，保证金率实时监控，追保通知与强制平仓
- **外汇交易** — 接入 [biquote](https://biquote.io/docs/) 行情（外汇 / 贵金属 / 加密 / 指数 CFD），买卖复用杠杆引擎
- **期货合约** — 开多 / 开空、每日盯市结算、到期自动平仓
- **统一主菜单 GUI** — `/mstock` 无参打开，所有操作都有等价的图形入口
- **Vault 经济集成** — 交易直接扣减 / 增加服务器金币余额
- **模拟盘** — 本地生成的虚拟股票（`SIM001`~`SIM010`），零风险练手；行情、K 线、图表、杠杆、强平全部复用真实盘的同一套代码路径，与真实盘共用余额但持仓分表

## 依赖

| 依赖 | 版本 |
|------|------|
| Paper / Spigot | 1.20.1+ |
| Vault | 1.7+ |
| Java | 21+ |

**行情数据源与 API Key**：

| 市场 | 数据源 | 需要 Key |
|------|--------|----------|
| A 股 | 新浪财经（腾讯财经兜底） | 否 |
| 港股 | 新浪 / 腾讯 | 否 |
| 外汇 / 贵金属 / 指数 / 虚拟货币 | biquote | 否 |
| **美股** | Finnhub / Twelve Data | **是（需自备）** |

`config.yml` 的 `us-stock-apis[].apikeys` 默认为**空列表**。不填 Key 时美股会报"获取行情失败"，A 股 / 港股 / 外汇 / 虚拟货币不受影响。

## 指令列表

所有功能都挂在 `/mstock` 下。`/stock` 与 `/st` 是等价的别名（`/st` 在命令名未被占用时动态注册）。

### 股票

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock` | 打开统一主菜单 GUI | `minestock.use` |
| `/mstock help [页码]` | 输出指令列表 | `minestock.use` |
| `/mstock menu` | 打开主菜单 | `minestock.menu` |
| `/mstock positions` | 打开持仓总览界面（股票 / 保证金 / 期货） | `minestock.use` |
| `/mstock <代码>` | 查看股票实时行情与 K 线 | `minestock.use` |
| `/mstock recommended` | 今日推荐列表 | `minestock.use` |
| `/mstock buy <代码> <数量>` | 买入股票 | `minestock.use` |
| `/mstock sell <代码> <数量>` | 卖出股票 | `minestock.use` |
| `/mstock short <代码> [数量] [杠杆]` | 做空股票（保证金）；数量省略时在聊天框补输，杠杆省略时取个人股票杠杆 | `minestock.use` |
| `/mstock cover <代码> [数量]` | 回补股票空头；数量省略时在聊天框补输 | `minestock.use` |
| `/mstock portfolio` | 查看我的持仓（文字） | `minestock.use` |
| `/mstock pg` | 查看我的持仓（GUI） | `minestock.use` |
| `/mstock reload` | 重载配置文件 | `minestock.admin` |

### 杠杆与保证金

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock leverage` | 查看当前杠杆与可调范围 | `minestock.use` |
| `/mstock leverage <倍数>` | 设置个人杠杆（1 ~ 100） | `minestock.leverage.set` |
| `/mstock leverage stock` | 查看 / 设置股票杠杆 | `minestock.leverage.set` |
| `/mstock margin` | 查看保证金、未实现盈亏、强平价 | `minestock.use` |
| `/mstock margin close <仓位ID>` | 平掉指定保证金仓位 | `minestock.use` |
| `/mstock margin closeall` | 平掉全部保证金仓位 | `minestock.use` |
| `/mstock margin topup <仓位ID> <金额>` | 补交保证金 | `minestock.use` |

`/mstock margin close` 按仓位 ID 结算，与方向无关 —— 平多、平空都是这一条命令。

### 外汇

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock forex` | 打开板块选择 GUI | `minestock.use` |
| `/mstock forex fiat` | 打开国家外汇板块 | `minestock.use` |
| `/mstock forex crypto` | 打开虚拟货币板块 | `minestock.use` |
| `/mstock forex list [板块] [页码]` | 列出品种 | `minestock.use` |
| `/mstock forex search <关键词> [板块]` | 模糊搜索品种 | `minestock.use` |
| `/mstock forex price <品种>` | 查询实时报价（取 `mid`） | `minestock.use` |
| `/mstock forex buy <品种> <数量>` | 买入（开多） | `minestock.forex.trade` |
| `/mstock forex sell <品种> <数量>` | 卖出（开空） | `minestock.forex.trade` |
| `/mstock forex chart <品种> [周期] [根数]` | 聊天框走势图 | `minestock.chart` |

外汇开仓只需要 `buy` / `sell` 两条；平仓在交易页用「平多 / 平空」按钮，或按仓位 ID 走 `/mstock margin close`。

### 期货

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock futures` | 打开期货 GUI | `minestock.use` |
| `/mstock futures open <品种> <long\|short> [数量]` | 开仓，数量省略时为 1 | `minestock.futures.trade` |
| `/mstock futures close <持仓ID>` | 平掉指定持仓 | `minestock.futures.trade` |
| `/mstock futures close <品种> <long\|short>` | 按合约与方向结清该方向全部持仓 | `minestock.futures.trade` |
| `/mstock futures positions` | 查看期货持仓 | `minestock.use` |
| `/mstock futures contracts` | 查看可用合约与参数 | `minestock.use` |

### 图表

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock chart <品种> [周期] [根数]` | 聊天框 K 线走势图 | `minestock.chart` |

默认 `周期=1h`、`根数=20`，最大 `30`，每玩家 3 秒冷却。可用周期：`1m` `5m` `15m` `30m` `1h` `4h` `1d`。

数据源选择：股票代码优先走股票行情源，股票不支持或取数失败时回退到 biquote。因此 `/mstock chart AAPL` 与 `/mstock chart EURUSD` 都能用。

### 模拟盘

模拟盘提供一组**虚拟股票**（代码 `SIM001` ~ `SIM010`，数量由 `simulation.auto-fill-count` 决定），价格由本地随机模型实时生成，**不依赖任何外部行情接口**。行情、K 线、图表、杠杆、强平、追保通知全部复用真实盘的同一套代码路径。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/mstock simulation` | 打开模拟盘行情列表 GUI | `minestock.simulation.use` |
| `/mstock simulation list [页码]` | 打开行情列表 GUI（指定页） | `minestock.simulation.use` |
| `/mstock simulation <SIMXXX>` | 查看该模拟股详情（含 K 线 / 图表入口） | `minestock.simulation.use` |
| `/mstock simulation buy <代码> <数量> [杠杆]` | 买入；杠杆 > 1 走保证金路径 | `minestock.simulation.use` |
| `/mstock simulation sell <代码> <数量>` | 卖出（先平杠杆多头仓，余量走现金持仓） | `minestock.simulation.use` |
| `/mstock simulation short <代码> [数量] [杠杆]` | 做空（落 `market=SIMULATION` 的保证金空头，杠杆可写 1）；省略数量时聊天框补输 | `minestock.simulation.use` |
| `/mstock simulation cover <代码> [数量]` | 平空；省略数量 = 清空该标的全部空头 | `minestock.simulation.use` |
| `/mstock simulation portfolio` | 查看模拟盘**现金**持仓与浮动盈亏 | `minestock.simulation.use` |
| `/mstock simulation admin [动作 ...]` | 打开管理控制台 / 执行管理动作 | `minestock.simulation.admin` |

`/mstock sim ...` 是 `simulation` 的等价别名。`/mstock chart SIM001` 同样可用。

**资金语义**：模拟盘与真实盘共用同一份 Vault 余额，但**持仓分表**：

| 持仓类型 | 存放位置 |
|----------|----------|
| 模拟现金持仓（杠杆 ≤ 1） | `simulation_holdings` |
| 模拟杠杆 / 空头仓位 | `margin_positions`，`market = 'SIMULATION'` |

因此模拟盘的买卖会真实增减玩家的金币余额，但不会写进真实盘的 `holdings` 表，也不会产生 `market=EQUITY` 的仓位。反过来，真实盘持仓也不会出现在 `/mstock simulation portfolio` 里。

`market = 'SIMULATION'` 的保证金仓位自动纳入强平巡检、追保通知、`/mstock margin`、部分平仓与补交保证金。持仓总览界面把它们显示在"保证金 / 期货区"（点击走 `/mstock margin`），与股票现货区分显示。

**管理动作**（`/mstock simulation admin <动作> <代码> [参数]`，全部需要 `minestock.simulation.admin`）：

| 动作 | 参数 | 说明 |
|------|------|------|
| `setprice` | `<代码> <价格>` | 定值覆盖（与 `target` 互斥写） |
| `target` | `<代码> <涨幅%>` | 相对昨收的目标涨幅覆盖（与 `setprice` 互斥写） |
| `jump` | `<代码> <涨跌%>` | 一次性价格冲击（只改自动价） |
| `pause` | `<代码> <on\|off>` | 暂停推进（价格冻结，仍可交易） |
| `suspend` | `<代码> <on\|off>` | 停牌（价格冻结 + 禁止买卖 + **强平跳过**） |
| `tradable` | `<代码> <on\|off>` | 禁售开关（价格照常推进，只禁止买卖） |
| `slope` | `<代码> <每毫秒价格> <持续毫秒>` | 附加线性斜率，到期自动失效 |
| `amplitude` | `<代码> <倍数>` | 对数收益乘数 |
| `preset` | `<代码> <low\|normal\|high>` | 波动预设 |
| `bounds` | `<代码> <下限> <上限>` | 绝对上下限（始终硬截断） |
| `limit` | `<代码> <±%>` | 涨跌停幅度（百分数，`10` = ±10%） |
| `boundary` | `<代码> <hard\|bounce\|soft>` | 触及涨跌停带时的策略 |
| `link` | `<代码A> <代码B> <相关系数>` | 建立 / 更新联动 |
| `unlink` | `<代码A> <代码B>` | 删除联动 |
| `schedule` | `<代码> <HH:mm> <涨跌%> [daily]` | 定时价格冲击（不写 `daily` 即一次性） |
| `schedules` | — | 列出全部定时操作 |
| `unschedule` | `<序号>` | 按序号删除定时操作 |
| `seed` | `<代码> [种子]` | 重设随机种子（同种子 ⇒ 同一条价格路径） |
| `resume` | `<代码>` | 清除定值 / 目标涨幅 / 斜率与暂停（**不**复活停牌与禁售） |
| `reset` | `<代码>` | 回到初始价并按同一种子重放（**不删** `simulation_klines` 历史） |

管理控制台 GUI 把常用开关做成了按钮，点击后等价于执行上面的指令；参数复杂的指令（定值、目标涨幅、联动、定时）在"指令速查"页以文本形式列出供复制。GUI 与手输指令调用完全相同的 Service 方法，权限与校验行为一致。

## GUI 入口

`/mstock` 无参调用即打开主菜单。指令是权威入口，GUI 是等价操作的图形化映射：同一个操作，指令与 GUI 调用同一个 Service 方法，权限校验、冷却与异步行为一致。

主菜单（54 格）只做入口，不堆持仓明细：

| 槽位 | 功能 | 等价指令 |
|------|------|----------|
| 20 | 杠杆设置 → 杠杆界面（外汇 / 股票页签） | `/mstock leverage` |
| 22 | **持仓总览** → 独立持仓界面（居中，最常用） | `/mstock positions` |
| 24 | 外汇交易 → 外汇板块页 | `/mstock forex` |
| 29 | 期货合约 → 期货界面 | `/mstock futures` |
| 31 | 指令帮助 | `/mstock help` |
| 33 | **模拟盘** → 模拟盘行情列表（所有人可见） | `/mstock simulation` |
| 35 | 模拟盘管理 → 管理控制台（**仅 op 可见**，无权限时该槽为玻璃板） | `/mstock simulation admin` |
| 49 | 关闭 | — |

**持仓总览界面**（`/mstock positions`，54 格）：持仓集中在这里，不再塞进主菜单。

| 槽位 | 区域 |
|------|------|
| 0–26 | 股票持仓 |
| 27–44 | 保证金 / 期货持仓 |
| 45 | 汇总条（市值、保证金率、未实现盈亏） |
| 47 / 48 / 51 | 上一页 / 刷新 / 下一页 |
| 49 | 返回主菜单 |

每类持仓显示的内容：

- **股票**：代码、股数、买入均价、当前价格、持仓市值、浮动盈亏（含百分比）
- **保证金 / 外汇 / 股票空头**：方向、数量、开仓价、杠杆、保证金、**强平价**、未实现盈亏
- **期货**：方向、数量、开仓价、保证金、已盯市盈亏、到期日

点击任意条目跳到对应详情：股票走 `/mstock <代码>`，保证金走 `/mstock margin`，期货走 `/mstock futures positions`。股票与保证金 / 期货区各自独立分页，股票多不会挤掉期货。

汇总条把股市与保证金的数字分开列，不合并成单一"总盈亏"：股票按买入均价算浮动盈亏，保证金按未实现盈亏算，两者口径不同，相加会误导玩家。保证金率按其所在区间着色（正常绿 / 追保黄 / 强平红）。

数据加载期间显示"加载中…"占位，持仓与行情并行获取、到齐后一次性刷新；股票现价走批量请求，全程不阻塞服务器 tick。

各界面细节：

- **杠杆界面**（54 格）：45 / 47 两个页签切换 **外汇** 与 **股票** 两套独立杠杆制度（等价于 `/mstock leverage` 与 `/mstock leverage stock`）。顶行只读展示当前制度 / 当前杠杆 / 可用范围 / 默认值 / 总保证金率 / 持仓明细；中间一排 `-10 -5 -1 +1 +5 +10` 按钮，点击直接调整当前页签那一套杠杆并原地刷新；槽位 13 为"重置为当前制度的默认值"；49 为返回主菜单。`leverage.stock.enabled: false` 时股票页签被锁定并给出提示。
- **外汇界面**：分三级。
  - **板块页**：把**国家外汇**与**虚拟货币**做成两个独立入口（20 / 22 号槽），另有"全部板块"（24）与"模糊搜索"（30），每个板块显示当前活跃品种数量。
  - **行情页**：只在当前板块内展示品种，每品种一个图标，悬停显示 `mid` 价格与日内涨跌；支持翻页（45 个/页）、刷新、搜索、清空搜索与返回板块页。标题与信息栏显示当前板块、关键词与命中数量。
  - **交易页**：19 平多 / 20 做多 / 22 设置数量 / 24 做空 / 25 平空 / 40 查看 K 线 / 45 返回板块页 / 49 返回主菜单。"返回行情"会回到来源板块、关键词与页码，而不是重置到默认板块。K 线发到聊天框，GUI 不内嵌图表。开仓与平仓成对并排，**共用同一个数量格**：做空数量与买入数量完全一致。
- **期货界面**：顶部 0–8 为未到期合约（点击选中，槽位 13 显示选中合约的到期日 / 合约乘数 / 最小变动 / 默认杠杆）；中部 19 平多 / 20 开多 / 22 设置数量 / 24 开空 / 25 平空；31 为查看 K 线；底部 36–44 为持仓列表，点击某项即平仓；49 返回主菜单。期货没有部分平仓，所以「平多 / 平空」按方向结清该合约的**全部**持仓，数量格不参与。
- **模拟盘界面**：分两级。
  - **行情列表页**（槽位 33 进入）：品种区 `0..44`（45 只/页），固定按钮全部落在 `45..49`（45 上一页 / 46 刷新 / 47 下一页 / 48 信息 / 49 返回主菜单）。条目显示代码 / 名称 / 板块 / 现价 / 涨跌幅，停牌置灰并标注。
  - **交易页**：4 信息 / 19 卖出（平多）/ 20 买入 / 22 设置数量 / 24 做空 / 25 回补（平空）/ 40 查看 K 线 / 45 返回行情（回到来源页码）/ 49 返回主菜单。"设置数量"复用与外汇交易页完全相同的聊天输入通道（`gui-quantity-prompt`，输入 `cancel` 取消），做空与回补也用同一个数量格。
- **模拟盘管理台**（槽位 35 进入，仅 op）：选股区 `0..8`（9 只/页），9–12 为下限 / 上限的步进按钮，13 为选中股信息，19 / 21 定值 ±10%，23 暂停推进，25 停牌，28 可交易，30 边界策略循环，32 波动预设循环，34 恢复自动，38 重置，40 重设随机种子，42 指令速查，45 / 46 / 53 翻页与刷新。每个按钮的等价指令都写在 lore 里，点击后由监听器路由到指令，与手输走同一条 Service 路径。
- **持仓操作菜单**（点击持仓总览里的条目进入）：4 持仓明细、10 卖出 / 平多 / 平空、12 查看 K 线、14 查看详情、22 返回。按钮文案随持仓类型与方向变化 —— 现金持仓显示"卖出"，多头保证金仓位显示"平多"，空头保证金仓位显示"平空"。

GUI 打开时若数据未加载完成会显示"加载中…"占位，数据到达后在玩家线程刷新，不阻塞服务器 tick。

## 杠杆 / 外汇 / 期货 / 图表 使用说明

### 杠杆

每个玩家有独立的杠杆倍数，登录加载、退出保存。未设置过时的默认值分制度：**外汇 / CFD 取 `forex.default-leverage`（默认 30）**，**股票取 `leverage.stock.default-leverage`（默认 1）**。

**杠杆只影响新开仓位**。已有仓位永远保留开仓时记录的杠杆与保证金，玩家之后调整个人杠杆不会让既有仓位被动态重算。

保证金率定义：

```
marginRatio = (margin + unrealizedPnl) / margin
```

开仓瞬间恒为 `1.0`，与杠杆倍数解耦。若改用"权益 / 名义价值"，默认 10 倍杠杆开仓即为 `0.1`，会立刻触碰强平线。

- `marginRatio ≤ 0.5`（`leverage.margin-call-threshold`）→ 发送追保通知
- `marginRatio ≤ 0.1`（`leverage.liquidation-threshold`）→ 强制平仓

强平价由强平阈值推导，多头在下方、空头在上方：

```
多头: price = entry × (1 - (1 - liquidationThreshold) / leverage)
空头: price = entry × (1 + (1 - liquidationThreshold) / leverage)
```

按默认 `liquidation-threshold: 0.1`，10 倍多头强平价为开仓价的 91%，10 倍空头为 109%；杠杆越低强平线越远。价格触及该价时保证金率恰好等于强平阈值，与巡检判定完全一致。

强制平仓发生在后台巡检任务中（默认每 30 秒），使用该仓位**开仓时**记录的杠杆与保证金，不随后续操作重算。

### 补交保证金（追保自救）

收到追保通知后，玩家可以补交保证金自救，而不必被动等强平：

```
/mstock margin topup <仓位ID> <金额>
```

补交通知本身就是可点击的：巡检发现仓位进入追保区时，消息附带

```
[ 一键补交 <建议金额> ]   [ 平仓止损 ]
```

两个按钮，分别执行补交与平仓（等价于手输对应指令）。

**补交的数学**：补交金额按"拉回 80% 保证金率"计算（`(margin + topUp + uPnL) / (margin + topUp) = 0.8`）。补到 80% 而不是 100%，是因为补满等于完全去杠杆、失去杠杆意义；80% 留有余量但已远离强平线。

**补交的效果**：名义价值不变、保证金变多 —— 这本质上就是去杠杆：

- 保证金率立即回升
- 有效杠杆 = 名义价值 / 新保证金（向下取整，最低 1x），相应降低
- **强平价被推远**：入场价 10 的 10 倍多头强平价为 9.10，补交后若有效杠杆降到 4 倍，强平价变为 7.75
- 补到保证金超过名义价值时，杠杆停在 1x，多余的钱只是垫高安全垫

**并发安全**：先扣款、再写库；若写库时仓位已被并发强平，这笔钱原额退回。

**防打扰**：追保通知对同一仓位 5 分钟内不重复发送（巡检周期 30 秒，不节流会刷屏）。

### 外汇

行情来自 biquote，无需 API Key。

- **价格一律取 `mid` 字段**。外汇 / CFD 数据源的 `last` 与 `volume` 恒为 `0`，不可使用。
- 活跃品种过滤使用 `symbols({ liveOnly: true })`，只返回当前正在报价的品种（目录约 1700 个，活跃约 280 个）。
- K 线来自 `ohlc(symbol, { interval, limit })`。biquote 返回的 `bars` 是最新在前，插件会反转成最早在前再截取最后 `limit` 根。
- 外汇默认杠杆为 `forex.default-leverage`（默认 30），复用同一套杠杆引擎。玩家从未设置过外汇杠杆时取该值；该键缺失时回落到 `leverage.default-leverage`。
- `forex.enabled: false` 只关闭**下单**（`forex buy` / `forex sell` 与交易页的做多 / 做空 / 平多 / 平空按钮），行情页、K 线、搜索与报价查询全部照常可用。

#### 两个独立板块

外汇界面内按资产属性划分为两个并列的独立板块（`ForexCategory`）：

| 板块 | id | 内容 | 活跃品种数 |
|------|----|------|-----------|
| 国家外汇 | `fiat` | 法定货币对、贵金属、能源、指数 CFD | 约 273 |
| 虚拟货币 | `crypto` | 加密资产 | 约 95 |

板块归属以 biquote 的 `type` 元数据为权威判据，符号名启发式只在元数据为 `Forex` 时才兜底：

- biquote 上有约 **186 个股票 CFD** 带 `USDT` 后缀（`MSFTUSDT`、`SPYUSDT`、`GDXUSDT` …），若只看后缀会把微软、标普 500 ETF 当成虚拟货币。
- 大宗商品同样带 `USDT` 后缀（`XAUUSDT`、`CLUSDT`、`COPPERUSDT`），它们是贵金属与能源，属于传统一侧。

因此：`type=Stock` 的品种不属于这两个板块（展示为"股票 CFD"），只在"全部板块"页可见；`type=Commodity` / `Index` 一律归入国家外汇。

#### 模糊搜索

按品种代码或中文 / 英文描述搜索，支持四种模式：

| 输入 | 匹配方式 | 示例 |
|------|----------|------|
| `btc` | 代码前缀 / 包含 | `BTCUSD`、`BTCUSDT` |
| `gold` | 描述包含 | `XAUUSD`（Gold vs US Dollar） |
| `euu` | **子序列模糊** | `EURUSD`（字符按顺序出现即可） |
| `euro yen` | 多词，全部需命中 | `EURJPY` |

结果按相关性排序：精确相等 > 代码前缀 > 代码包含 > 描述包含 > 子序列；同档次内按代码字典序，保证结果稳定可复现。

对应指令：

| 命令 | 说明 |
|------|------|
| `/mstock forex fiat` | 打开国家外汇板块 |
| `/mstock forex crypto` | 打开虚拟货币板块 |
| `/mstock forex list [板块] [页码]` | 列出品种 |
| `/mstock forex search <关键词> [板块]` | 模糊搜索 |

### 期货

合约来源按优先级：`config.yml → futures.contracts` 显式声明 → 从 biquote 品种元数据自动派生 → 内置兜底表。自动派生时 `tickSize` / `contractSize` 取自 `/api/symbols`，到期日取下一个季末。

期货默认杠杆由 `initialMarginRate` 推算（`0.1` → 10 倍）。

- 开仓保证金 = `price × contractSize × quantity × initialMarginRate`
- 每日盯市：结算价取 `ohlc(symbol, "1d", 2)` 中最后一根**已收盘**的日线（`limit=1` 会拿到 `isOpen: true` 的当日未完成 bar，不适合做结算价）。盈亏 = `(结算价 - 上次结算价) × contractSize × quantity × direction`
- 到期自动平仓：到期日当天（含）视为已到期，自动按最后结算价平仓并释放保证金
- 期货没有部分平仓数学，因此平仓以"合约 + 方向"为单位整笔结清

配置示例：

```yaml
futures:
  enabled: true
  settle-check-seconds: 60
  default-initial-margin-rate: 0.1
  contracts:
    - symbol: EURUSD
      expiry-date: "2026-12-31"
      contract-size: 100000
      tick-size: 0.00001
      initial-margin-rate: 0.01
```

### 股票杠杆

股票系统有独立于外汇的杠杆制度，复用同一套保证金引擎（追保通知、强制平仓巡检、`/mstock margin`、持仓总览全部自动覆盖）。

```yaml
leverage:
  stock:
    enabled: true          # 关闭后股票只能全额现金买入，且拒绝做空
    max-leverage: 10       # 股票允许的最大杠杆（比外汇的 100 保守）
    default-leverage: 1    # 默认 1 = 全额现金买入
```

**使用方式**：

| 命令 | 说明 |
|------|------|
| `/mstock leverage stock` | 查看当前股票杠杆与可调范围 |
| `/mstock leverage stock <倍数>` | 设置股票杠杆（1 ~ max，权限 `minestock.leverage.set`） |
| `/mstock buy <代码> <数量> [杠杆]` | 买入；杠杆 > 1 时开保证金仓位 |
| `/mstock sell <代码> <数量>` | 卖出；**先平杠杆多头仓位**（先开先平，支持部分平仓），余量走现金持仓 |
| `/mstock short <代码> [数量] [杠杆]` | 做空；开 `direction=SHORT` 的保证金仓位，`1` 倍合法（全额保证金，没有现金做空通道） |
| `/mstock cover <代码> [数量]` | 回补；只平该标的的**空头**仓位，完全不碰多头。数量省略时聊天框补输，可一次平掉指定数量 |

**关键设计**：

- **杠杆 = 1 表示"不借钱"**：走现金买入路径（`holdings` 表），不动用保证金机制。
- **杠杆 > 1 才开保证金仓位**：冻结名义价值 / 杠杆作为保证金，有强平价与追保线；仓位落在 `margin_positions` 表（`market=EQUITY`）。
- **做空没有现金通道**：空头必须借券，因此无论杠杆写几倍都开保证金仓位，`1` 倍即全额保证金。`direction=SHORT` 只存在于 `margin_positions`。
- **杠杆上限按制度校验**：`/mstock buy <代码> <数量> <杠杆>` 的杠杆参数以**股票**上限（`leverage.stock.max-leverage`，默认 10）为准，不沿用外汇的 100。
- **制度由市场推导，不能从外汇页绕过**：biquote 上约 186 个股票 CFD（`MSFTUSDT`、`SPYUSDT` …，元数据 `type=Stock`）在外汇界面成交时以 `market=EQUITY` 落库，它们按**股票**制度校验（上限 10、受 `leverage.stock.enabled` 约束）。因此 `/mstock buy MSFT` 与在外汇页买 `MSFTUSDT` 的上限一致。
- **`leverage.stock.enabled: false`**：拒绝任何杠杆 > 1 的股票买入、**拒绝全部股票做空**（含 1 倍），并锁定股票杠杆设置（含 GUI 页签、含股票 CFD）。不会静默改走现金路径 —— 现金买入要花的是保证金的 `leverage` 倍，静默替换会让玩家花掉远超预期的余额。
- **价格按市场路由**：股票仓位取股票行情现价（含 `price-ratio`），外汇仓位取 biquote 的 `mid`。两者没有可靠的命名分界（`AAPL` 既是美股代码也可能是 biquote 股票 CFD），因此路由按仓位的 `market` 字段而非代码猜测。
- **持仓估值的跨源回落有元数据门控**：真实股票（A 股 / 港股 / 美股）取不到价时按取价失败处理，不改用 biquote 报价 —— 否则开仓价来自股票源、平仓估值来自 biquote，价差会凭空制造盈亏并触发错误的强平。仅当 biquote 自己把该品种归为 `EQUITY`（即股票 CFD）时才采用它的报价。
- **部分平仓按比例结算**：平掉一半数量即释放一半保证金；强平价与杠杆不变（由开仓时入场价与杠杆决定）。
- **手续费**：沿用全局 `transaction-fee-percent`，按名义价值收取（开仓、平仓各一次，与现金交易一致）。该配置项的单位是**百分数**：`0.5` 表示 0.5%（费率 0.005），`1.0` 表示 1%，`0` 表示免手续费；负值按 0 处理。真实股票 / 股票杠杆 / 外汇 / 期货 / 模拟盘共用同一个值，没有独立开关。
- **卖出顺序**：先平杠杆多头仓位（先开先平），余下的数量才减现金持仓。杠杆仓位有强平风险，优先处理更安全。
- **多空互不干扰**：`/mstock sell` 只平多头、`/mstock cover` 只平空头 —— 平仓查询带 `direction` 谓词，同一标的的多空仓位不会互相吞掉。空头的盈亏与强平价按 `direction.sign()` 取反（价格跌赚、涨亏；强平价落在开仓价**上方**）。
- **两套杠杆互不影响**：外汇杠杆（`/mstock leverage`）与股票杠杆（`/mstock leverage stock`）分别存储在 `player_leverage` 与 `player_stock_leverage` 两张表，玩家可以只调其中一个。

**持仓总览里的展示**：现金持仓与杠杆仓位在股票区并列显示；杠杆仓位用镐图标、黄色标题，并显示"股票 · 杠杆 Nx"、冻结保证金与**强平价**，与现金持仓明确区分。

### 图表

`util/KLineRenderer` 负责柱状图绘制：底部升起的 `█` 实体、深灰 `█` 占位、左侧 `│`/`↑` 与底部 `└─…─→` 坐标轴，每个单元格都带 `<hover>` OHLC 明细。两个重载：

```java
public static List<String> render(List<KLinePoint> points)                          // 纯柱状图
public static List<String> render(String symbol, String interval, List<KLinePoint>) // 附带标题与最新价摘要
```

第二个重载只是在柱状图外包裹标题行与最新价摘要行，绘制逻辑完全复用。任何能转成 `KLinePoint` 模型（open / high / low / close / date）的 OHLC 数据都能用同一套渲染，因此外汇、期货、模拟盘共用这个渲染器。

`chart/ChatChartRenderer` 只负责输入校验、调用 `KLineRenderer` 并返回消息行，不含绘图代码。数据不足（少于 2 根）或接口失败时只发一条简短错误消息，不输出空图表。

## 行情告示牌

在告示牌前三行输入以下内容，可显示今日第 1 支推荐股票；数字可换成任意大于或等于 1 的推荐序号：

```text
[minestock]
recommend
1
```

查询指定股票时输入：

```text
[minestock]
code
600519
```

创建后，告示牌会显示代码、名称、当前价格和涨跌幅，并按 `sign-refresh-seconds` 自动刷新。记录保存在插件数据目录的 `signs.yml`；告示牌被编辑、挖掉、炸掉或因依附方块消失而脱落时，相应记录会自动删除。

`recommended-pool` 支持两种配置。填写 YAML 列表时，插件每天从管理员指定的股票中择优选出 `recommended-count` 支；填写 `auto` 时，插件每天从东方财富沪深 A 股行情候选中，过滤 ST、退市、停牌和极端涨跌股票，再按涨幅、成交额、换手率和量比综合评分：

```yaml
recommended-pool: auto
recommended-count: 3
auto-candidate-count: 100
sign-refresh-seconds: 300
```

## 支持的股票代码

- **A 股** — 6 位数字代码，如 `600519`（贵州茅台）、`000001`（平安银行）
- **港股** — 英文字母 + 数字，如 `00700`
- **美股** — 英文代码，如 `AAPL`、`TSLA`、`NVDA`
- **模拟盘** — `SIM` + 3 位数字，如 `SIM001` ~ `SIM010`（与上述三种正则均不冲突）

## 数据存储

使用 H2 嵌入式数据库本地持久化，无需额外数据库服务（也可在 `database.yml` 切换到 MySQL）。共十张业务表：

| 表 | 用途 |
|----|------|
| `holdings` | 股票现货持仓 |
| `player_leverage` | 每个玩家的个人杠杆倍数 |
| `player_stock_leverage` | 每个玩家的股票杠杆倍数 |
| `margin_positions` | 保证金仓位（股票杠杆 / 股票空头 / 外汇 / **模拟盘 `market=SIMULATION`**） |
| `futures_positions` | 期货持仓 |
| `simulation_stocks` | 模拟股价格状态（现价 / 自动价 / 昨收 / 参数 / 指令覆盖 / 开关） |
| `simulation_holdings` | 模拟盘**现金**持仓（`UNIQUE (player_uuid, code)`） |
| `simulation_links` | 模拟股两两联动系数（`UNIQUE (code_a, code_b)`） |
| `simulation_schedules` | 模拟盘定时价格冲击 |
| `simulation_klines` | 模拟盘 1 分钟 OHLC（复合主键 `(code, bucket_start)`） |

另有一张内部的 `schema_version` 表记录结构版本，启动时用于自动补齐缺失的列与索引。

保证金仓位没有复用 `holdings` 表：`holdings` 上有 `UNIQUE (player_uuid, stock_code)`，会把同一品种的多笔仓位合并，从而违反"每笔仓位保留开仓时的杠杆与保证金"。因此保证金仓位独立建表，同一品种可以有多行（含多空两个方向），各自独立结算与被强平。

## 权限节点

| 节点 | 默认 | 说明 |
|------|------|------|
| `minestock.use` | true | 使用 MineStock 股票市场 |
| `minestock.admin` | op | 重载配置 |
| `minestock.menu` | true | 打开统一主菜单（无参 `/mstock`、`/mstock menu`）|
| `minestock.leverage.set` | true | 调整个人杠杆倍数 |
| `minestock.forex.trade` | true | 外汇买入 / 卖出 / 平仓 |
| `minestock.futures.trade` | true | 期货开仓 / 平仓 |
| `minestock.chart` | true | 聊天框 K 线图 |
| `minestock.simulation.use` | true | 使用模拟盘行情、交易与持仓 |
| `minestock.simulation.admin` | op | 管理模拟盘（价格 / 停牌 / 联动 / 定时） |

## 配置文件

`config.yml` 的主要配置节：

```yaml
leverage:
  max-leverage: 100
  # 仅作兜底：外汇实际默认值看 forex.default-leverage
  default-leverage: 10
  margin-call-threshold: 0.5
  liquidation-threshold: 0.1
  # 兼容保留项，不参与强平价计算
  maintenance-margin-rate: 0.005
  liquidation-sweep-seconds: 30
  stock:
    enabled: true          # false = 只允许 1x（全额现金），拒绝做空，并锁定股票杠杆设置
    max-leverage: 10
    default-leverage: 1

forex:
  enabled: true            # false = 只关闭下单，行情 / K 线 / 搜索仍可用
  base-url: "https://biquote.io"
  default-leverage: 30     # 玩家未设置过外汇杠杆时的默认值
  symbol-cache-ms: 60000
  symbols-per-page: 45     # 行情页每页品种数（上限 45）

futures:
  enabled: true
  settle-check-seconds: 60
  default-initial-margin-rate: 0.1
  contracts: []

chart:
  cooldown-ms: 3000
  default-interval: "1h"
  default-limit: 20
  max-limit: 30

simulation:
  enabled: true                      # false = 不注册行情源 / 不建定时任务 / 指令一律报"已关闭"
  tick-seconds: 1.0                  # 价格推进周期（秒），单线程后台任务
  auto-fill-count: 10                # 首次启用时自动补满的股票数量（1~200）
  seed-base: 20260101                # 第 i 只股票的种子 = seed-base + i
  default-limit-percent: 10.0        # 默认涨跌停幅度（百分数，夹取 [0.0001, 10.0]）
  default-preset: normal             # low | normal | high
  default-boundary: soft             # hard | bounce | soft
  min-price: 0.01                    # 管理员绝对下限（始终硬截断）
  max-price: 1000000.0               # 管理员绝对上限
  kline-capacity: 512                # 1 分钟 OHLC 环形缓冲容量
  link:
    group-weight: 1.0                # 联动共同因子权重
```

**模拟盘杠杆与股票杠杆是耦合的**：`Policy.of(SIMULATION) = STOCK`，所以 `leverage.stock.enabled: false`（或 `leverage.stock.max-leverage: 1`）会连带把模拟盘杠杆也压到 1x。启动时若检测到这种组合，控制台会打一条 INFO 日志说明。

## 已知限制

- 周末外汇市场休市时 biquote 只返回当前正在报价的品种（`liveOnly=true`），外汇行情页可能接近为空。GUI 会显示"当前无活跃报价"。
- 休市时 biquote 返回最后已知价并带 `marketState: "closed"`。此时显示"休市 · 最后价"，不显示日内涨跌（该涨跌属于已结束的交易日）。
- GUI 图标的观感、聊天框字符对齐与 Folia 实机行为取决于客户端与服务端实现，未经实机验证。GUI 槽位之间存在互斥约束，同一槽不会被两个按钮同时占用。

## PlugManX 热加载

MineStock 支持通过 PlugManX 禁用、卸载并重新加载。卸载时会注销动态 `/st` 命令、取消行情请求、清理聊天会话，停止强平巡检、期货盯市与模拟盘价格推进任务，把内存中的玩家杠杆与模拟股状态落库，并等待最多 10 秒让已修改余额的交易完成持仓写入。模拟盘状态在关闭数据库与停止异步任务之前落库。

若其他插件使用 `MineStockAPI` 或监听 MineStock 自定义事件，应先卸载这些依赖插件，并在 MineStock 重新加载后再加载它们。旧类加载器创建的 API 对象不能跨热重载继续使用。

## 构建

```bash
mvn clean package
```

产物位于 `target/MineStock-1.2.4.jar`，放入服务器 `plugins/` 目录后即可使用。

运行测试：

```bash
mvn test
```

## 作者

oneLiLi
