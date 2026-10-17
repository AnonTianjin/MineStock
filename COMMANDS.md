# MineStock 指令手册（COMMANDS.md）

> 适用版本：MineStock 1.2.3（Paper 1.20.1）。
> 本文只收录**玩家可用**的公开指令；管理端控制台不可执行 `/mstock`（命令限定玩家）。
> `/mstock` 与别名 `/stock` 行为完全一致，下文统一写作 `/mstock`。
>
> 游戏内等价查询：`/mstock help [页码]` —— 共 3 页，覆盖本文全部指令。

## 目录

- [总表](#总表)
- [1. 基础与股票交易](#1-基础与股票交易)
- [2. 杠杆与保证金](#2-杠杆与保证金)
- [3. 外汇](#3-外汇)
- [4. 期货](#4-期货)
- [5. 图表](#5-图表)
- [6. 模拟盘](#6-模拟盘)
- [7. 模拟盘管理（管理员）](#7-模拟盘管理管理员)
- [8. 权限一览](#8-权限一览)

## 总表

| 指令 | 作用 | 权限 |
| --- | --- | --- |
| `/mstock` · `/mstock menu` | 打开主菜单 GUI | minestock.menu |
| `/mstock help [页码]` | 分页帮助（共 3 页） | minestock.use |
| `/mstock recommended` | 今日推荐股票（`rec` / `r`） | minestock.use |
| `/mstock <代码>` | 查询股票实时行情 | minestock.use |
| `/mstock buy <代码> [数量] [杠杆]` | 买入股票（省略数量则在聊天栏补输） | minestock.use |
| `/mstock sell <代码> [数量]` | 卖出股票（省略数量则在聊天栏补输） | minestock.use |
| `/mstock portfolio` | 我的持仓（聊天框，`p`） | minestock.use |
| `/mstock positions` | 持仓总览 GUI（`pos`，含真实股票 / 模拟盘 / 保证金 / 期货，可翻页） | minestock.use |
| `/mstock pg` | 持仓分页（聊天框明细） | minestock.use |
| `/mstock reload` | 重载配置与语言文件 | minestock.admin |
| `/mstock leverage` | 查看个人杠杆倍数 | minestock.use |
| `/mstock margin` | 保证金仓位与强平价 | minestock.use |
| `/mstock forex` | 外汇板块 GUI | minestock.use |
| `/mstock futures` | 期货合约 GUI | minestock.use |
| `/mstock chart <品种> [周期] [根数]` | 聊天框走势图 | minestock.chart |
| `/mstock back` | 从 K 线回到进入时的界面（`return`） | minestock.chart |
| `/mstock simulation` | 模拟盘行情 GUI（`sim`） | minestock.simulation.use |
| `/mstock simulation admin <动作> …` | 模拟盘管理（22 个动作，含 `add` / `remove`） | minestock.simulation.admin |

> Tab 补全完整可用：一级子指令、板块名、合约方向、动作名、页码均有补全。

## 1. 基础与股票交易

| 指令 | 说明 |
| --- | --- |
| `/mstock` | 无参数 = 打开主菜单 GUI |
| `/mstock menu` | 同上 |
| `/mstock help [页码]` | 分页帮助。页码 1–3；非法页码会被明确拒绝（提示 `help-page-invalid`）而不回落第一页 |
| `/mstock recommended` | 今日推荐。别名：`rec`、`r` |
| `/mstock <代码>` | 查询单只股票实时行情，如 `/mstock AAPL`、`/mstock 600519` |
| `/mstock buy <代码> [数量] [杠杆]` | 买入股票；第 4 参 > 1 时走杠杆保证金通道，如 `/mstock buy AAPL 10 5`。省略数量则进入聊天栏补输（GUI 走这条两段式路径） |
| `/mstock sell <代码> [数量]` | 卖出股票。省略数量则进入聊天栏补输 |
| `/mstock portfolio` | 聊天框展示现金持仓与盈亏。别名：`p` |
| `/mstock positions` | 打开持仓总览 GUI（真实股票 / 股票杠杆 / **模拟盘** / 保证金 / 期货，条目超出容量时用 ◀ ▶ 翻页）。**左键**持仓 = 弹出操作菜单（卖出或平仓 / 查看K线 / 详情），**右键** = 直接看详情。别名：`pos` |
| `/mstock pg` | 聊天框分页持仓明细 |
| `/mstock reload` | 重载配置、语言文件，并重建任务调度（需 `minestock.admin`） |

## 2. 杠杆与保证金

| 指令 | 说明 |
| --- | --- |
| `/mstock leverage` | 查看当前个人杠杆倍数 |
| `/mstock leverage <倍数>` | 设置股票杠杆（1–100，受服务端配置上限约束） |
| `/mstock leverage stock <倍数>` | 单独设置股票通道的杠杆倍数 |
| `/mstock margin` | 保证金总览：仓位数、总保证金、总浮盈、保证金率、追保 / 强平阈值 |
| `/mstock margin close <仓位ID>` | 平掉指定保证金仓位（ID 见 `/mstock margin` 列表） |
| `/mstock margin closeall` | 一键平掉全部可平的保证金仓位（被暂停 / 冻结的仓位会被跳过并提示） |
| `/mstock margin topup <仓位ID> <金额>` | 追加保证金，把余额转进指定仓位，推远强平价。别名：`add` |

> 收到追保通知时优先 `/mstock margin topup`；保证金率低于强平阈值会触发系统强平。

## 3. 外汇

| 指令 | 说明 |
| --- | --- |
| `/mstock forex` | 打开外汇板块选择 GUI |
| `/mstock forex fiat` | 国家外汇行情 GUI |
| `/mstock forex crypto` | 虚拟货币行情 GUI |
| `/mstock forex list [板块] [页码]` | 聊天框行情列表；板块：`fiat` / `crypto`。别名：`ls` |
| `/mstock forex search <关键词> [板块]` | 搜索品种（进入聊天输入态） |
| `/mstock forex price <品种>` | 单品种实时报价（mid / bid / ask），如 `/mstock forex price EURUSD` |
| `/mstock forex buy <品种> <数量>` | 买入（开多），数量支持小数 |
| `/mstock forex sell <品种> <数量>` | 卖出（开空） |
| `/mstock forex chart <品种> [周期] [根数]` | 外汇走势图 |

## 4. 期货

| 指令 | 说明 |
| --- | --- |
| `/mstock futures` | 打开期货 GUI：上排选合约，中部开仓，下排持仓 |
| `/mstock futures open <品种> <long\|short> [数量]` | 开仓，如 `/mstock futures open BTC long 2`；省略数量按 1 手 |
| `/mstock futures close <仓位ID>` | 平掉指定期货仓位 |
| `/mstock futures positions` | 聊天框期货持仓。别名：`list` |
| `/mstock futures contracts` | 聊天框合约列表（到期日 / 乘数 / 保证金率） |
| `/mstock futures menu` | 重新打开期货 GUI |

## 5. 图表

| 指令 | 说明 |
| --- | --- |
| `/mstock chart <品种> [周期] [根数]` | 聊天框走势图，如 `/mstock chart AAPL 1h 40`；周期由配置 `chart.default-interval`（缺省 `1h`）填充 |
| `/mstock back` | 从 K 线回到进入时的那个界面。别名：`return` |

支持的周期：`1m` / `5m` / `15m` / `30m` / `1h` / `4h` / `1d`（与外汇品种共用同一套取值）。
**周期真实生效**：请求哪个周期就按该周期聚合，不再一律画成日线。
模拟盘标的走同一套聚合逻辑，因此 `/mstock chart SIM001 1m 60` 得到的是分钟级柱子。

打开方式决定 `/mstock back` 的落点：

- 从 GUI 点开（主菜单 / 外汇 / 期货 / 模拟盘 / 持仓总览的子菜单）→ 回到对应界面；
- 直接手打 `/mstock chart …` → 没有来源界面，`/mstock back` 明确提示无从返回。

## 6. 模拟盘

> 模拟盘与真实盘共用同一份 Vault 余额，但持仓独立分表；
> 数量取整数（GUI 内左键 +1 / 右键 -1 / Shift+左键 +10，中键回落聊天输入）。

| 指令 | 说明 |
| --- | --- |
| `/mstock simulation` | 打开模拟盘行情列表 GUI。别名：`sim` |
| `/mstock simulation list [页码]` | 打开指定页的行情列表 GUI |
| `/mstock simulation <代码>` | 查询模拟标的详情（复用真实盘渲染） |
| `/mstock simulation buy <代码> [数量] [杠杆]` | 模拟买入；杠杆大于 1 时走保证金通道。**省略数量则进入聊天栏补输** |
| `/mstock simulation sell <代码> [数量]` | 模拟卖出。**省略数量 = 清空该标的现金持仓**（持仓操作菜单的「卖出」按钮走这条） |
| `/mstock simulation portfolio` | 模拟盘现金持仓（与真实盘持仓分开展示） |
| `/mstock simulation admin` | 无动作参数 = 打开管理 GUI（需 `minestock.simulation.admin`） |

## 7. 模拟盘管理（管理员）

格式：`/mstock simulation admin <动作> <代码> [参数]`。共 22 个动作：
`add` · `remove` · `setprice` · `target` · `jump` · `pause` · `slope` · `amplitude` · `preset` · `bounds` · `limit` ·
`boundary` · `suspend` · `tradable` · `link` · `unlink` · `schedule` · `schedules` ·
`unschedule` · `seed` · `resume` · `reset`。

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `add` | `<名称> <板块> <初始价>` | 注册新模拟股票。代码须为 **3 位大写字母 + 3 位数字**（如 `SIM011`），不可与现有标的重名。落库后立即参与定价与交易，如 `/mstock simulation admin add SIM011 模拟银行 金融 12.5` |
| `remove` | — | 注销模拟股票（仅需代码）。玩家已有持仓与历史 K 线**保留**，恢复注册即可继续使用 |
| `setprice` | `<价格>` | 直接改价 |
| `target` | `<百分比>` | 设置目标价百分比（价格向目标收敛） |
| `jump` | `<幅度>` | 立即跳涨 / 跳跌指定百分比 |
| `pause` | `on/off` | 暂停 / 恢复该标的自动定价 |
| `slope` | `<百分比> <毫秒>` | 设置趋势斜率与时间窗 |
| `amplitude` | `<百分比>` | 设置波动幅度 |
| `preset` | `<预设名>` | 应用波动率预设（见 SimulationVolatilityPreset） |
| `bounds` | `<下限> <上限>` | 设置软价格带。**必须 下限 < 上限**，否则明确报错 `simulation-admin-bounds-order`（GUI 里用 ± 按钮调整，步进 0.1） |
| `limit` | `<百分比>` | 设置涨跌停百分比 |
| `boundary` | `<模式>` | 触界行为（见 SimulationBoundary） |
| `suspend` | `on/off` | 停牌 / 复牌（停牌期间禁止交易） |
| `tradable` | `on/off` | 是否可交易 |
| `link` | `<真实代码> <系数>` | 联动真实行情定价，如 `link AAPL 1.0` |
| `unlink` | `<真实代码>` | 解除联动 |
| `schedule` | `<HH:mm> <百分比> [daily]` | 定时涨跌；第 4 参给 `on`/`true` 表示每日重复 |
| `schedules` | — | 列出全部定时任务（无代码参数） |
| `unschedule` | `<任务ID>`（直接跟在 admin 后） | 删除定时任务：`/mstock simulation admin unschedule <ID>` |
| `seed` | `[种子]` | 重掷随机种子；省略种子时按时间熵取一个新种子（连续重掷必不相同） |
| `resume` | — | 恢复默认定价参数 |
| `reset` | — | 重置标的（价格 / 状态回归初始） |

> 管理动作全部有一次性确认回执；参数不足时回显用法串，不会误执行。
> GUI 途径：`/mstock simulation admin` 打开管理面板，按钮等价于上述动作；
> 每次点完按钮**面板会就地重刷**，因此反复点同一按钮会沿着同一档位继续前进
> （如 `preset` 在低 → 正常 → 高之间循环），不会停在旧文案上。
> 价格带另有四个 ± 按钮（下限 −/+、上限 −/+），无需手打指令。

## 8. 权限一览

| 权限节点 | 默认 | 管辖范围 |
| --- | --- | --- |
| `minestock.use` | 所有玩家 | 基础指令（行情 / 买卖 / 持仓 / 帮助） |
| `minestock.menu` | 所有玩家 | 主菜单 GUI（`/mstock`、`/mstock menu`） |
| `minestock.leverage.set` | 所有玩家 | 修改个人杠杆 |
| `minestock.forex.trade` | 所有玩家 | 外汇买卖 |
| `minestock.futures.trade` | 所有玩家 | 期货开平仓 |
| `minestock.chart` | 所有玩家 | 图表指令 |
| `minestock.simulation.use` | 所有玩家 | 模拟盘查询与交易 |
| `minestock.admin` | OP | `/mstock reload` |
| `minestock.simulation.admin` | OP | 全部模拟盘管理动作 |

---

*本文由代码派发表（MStockCommand.onCommand / onTabComplete / plugin.yml）整理生成；
若新增指令，请同步 `/mstock help` 的 `help-page-N` 文案与本文。*
