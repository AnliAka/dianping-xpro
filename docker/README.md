# Docker 环境说明

用 `compose.yaml` 在本机拉起 MySQL 8、Redis 7 与 RocketMQ，应用可以选择跑在容器里，也可以继续在
IDE / `java -jar` 里跑。两种方式共用同一套中间件。

## 文件一览

| 文件 | 作用 |
| --- | --- |
| `compose.yaml` | 编排 MySQL、Redis、RocketMQ、OpenResty 与可选的应用容器 |
| `Dockerfile` | 应用运行镜像，多阶段构建，最终只有 JRE + jar |
| `Dockerfile.build` | 单独的编译阶段镜像，便于复用 Maven 依赖缓存 |
| `docker/rocketmq/broker.conf` | RocketMQ broker 配置，含事务回查参数 |
| `docker/openresty/nginx.conf` | OpenResty 网关配置：秒杀路径限流、其余转发 |
| `docker/openresty/lua/token_bucket.lua` | 共享状态原子令牌桶（`lua_shared_dict` + 自旋锁） |
| `docker/openresty/lua/seckill_gate.lua` | 秒杀入口门卫：取令牌、计数、429 拒绝 |
| `docker/openresty/lua/gw_stats.lua` | 网关计数查询（`GET /gateway/stats`） |
| `.dockerignore` | 构建上下文排除项，防止本机配置与产物进镜像 |
| `docker/README.md` | 本文件 |

## 一、只起中间件（推荐日常开发用）

应用在 IDE 里跑，中间件交给 Docker：

```bash
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose ps
```

宿主机端口刻意错开了系统默认值，避免和本机已装的中间件打架：

| 服务 | 宿主机端口 | 容器内端口 | 账号 / 密码 |
| --- | --- | --- | --- |
| MySQL | `3307` | `3306` | `root` / `1234` |
| Redis | `6380` | `6379` | 密码 `1` |
| RocketMQ namesrv | `9876` | `9876` | 无 |
| RocketMQ broker | `10911` / `10909` | 同左 | 无 |
| OpenResty 网关 | `8080` | `80` | 无 |

也就是说，本地连库要写 `localhost:3307`，连 Redis 写 `localhost:6380`，连 RocketMQ 写
`localhost:9876`。

端口和密码都可以用环境变量覆盖，例如：

```bash
MYSQL_PORT=3306 REDIS_PORT=6379 docker compose up -d mysql redis
```

### 首次启动会自动建表

`src/main/resources/db/dianping-xpro.sql` 被挂载到 MySQL 的
`/docker-entrypoint-initdb.d/`。**只在数据卷为空时执行一次**。

如果脚本改了要重跑，得先清掉数据卷：

```bash
docker compose down -v
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker
```

`-v` 会删除数据卷，本地数据一起没，确认清楚再执行。

**已有数据卷时新增的约束不会自动生效**，例如本轮给 `tb_voucher_order` 加的
`uk_user_voucher (user_id, voucher_id)`，需要手工执行：

```sql
ALTER TABLE tb_voucher_order
  ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

## 二、让应用也在容器里跑

```bash
docker compose --profile app up -d --build
docker compose logs -f app
```

应用容器里通过服务名访问中间件（`mysql:3306`、`redis:6379`、`rocketmq-namesrv:9876`），
这些地址由 `compose.yaml` 的环境变量注入，不需要改 `application.yml`。

### 命令行单独起应用（`java -jar`）

如果不想用容器跑应用，本地打包后直接运行：

```bash
mvn clean package -DskipTests
java -jar target/dianping-xpro-0.0.1-SNAPSHOT.jar
```

`application.yml` 里已经声明了：

```yaml
spring:
  config:
    import: optional:file:./config/application-local.yml
```

`optional:` 前缀保证文件不存在时不报错，回落到默认值。因此只要在**运行目录**下放一个
`config/application-local.yml`，密码和本机地址就能被读到，而这个文件不入版本库、
也不打进 jar：

```
<运行目录>/
├── app.jar
└── config/
    └── application-local.yml
```

注意 Spring Boot 的配置搜索路径是「运行目录」和「运行目录下的 `config/`」，所以
**必须在项目根目录（或 jar 所在目录）执行命令**，换目录会读不到。

容器场景下 `compose.yaml` 已把宿主机的 `./config` 挂到 `/app/config`，同一份文件两边通用。

## 三、RocketMQ 事务消息与入口预占

秒杀入口是同步事务消息：

```
生成本次请求唯一的 orderId
  → 发送半消息（携带 orderId / userId / voucherId）
  → 本地事务执行 seckill.lua：幂等检查 → 校验库存与一人一单 → 扣减 + 记录购买关系 → 写 SUCCESS
  → 预占成功 COMMIT，明确业务拒绝 ROLLBACK，结果未知 UNKNOWN
  → 消费者按 orderId 幂等落单 tb_voucher_order
```

半消息在 COMMIT 之前对消费者不可见，因此"预占成功但消息没发出去"由 Broker 回查兜住。
回查只读 `seckill:tx:{orderId}` 解释结果，不重新执行预占，也不依赖尚未创建的订单。

### Redis 键约定

| 键 | 类型 | 含义 |
| --- | --- | --- |
| `seckill:stock:{voucherId}` | string | 剩余库存 |
| `seckill:order:{voucherId}` | hash | userId → orderId，用于一人一单 |
| `seckill:tx:{orderId}` | string | `SUCCESS` / `REJECTED`，TTL 24 小时 |

`seckill:tx:{orderId}` 的 TTL 必须大于「事务回查窗口」与「半消息发送重试窗口」的较大值。
键一旦过期，回查会读到无记录并返回 UNKNOWN，已预占成功的请求将随回查耗尽被丢弃，且不留痕迹。

### broker.conf 中的回查参数

```
transactionTimeOut = 6000         # 超过该时间未收到决议即发起回查
transactionCheckMax = 15          # 最多回查次数，耗尽后半消息被丢弃
transactionCheckInterval = 60000  # 回查扫描间隔
```

开发环境 broker 用 `ASYNC_FLUSH`，若要验证"半消息已落盘则重启不丢"，需改为 `SYNC_FLUSH`。

### 从 Redis Stream 迁移过来的注意点

旧版本用 `XADD stream.orders` + 消费者组 `g1` 落单，现已整体移除。迁移时有三处旧状态要清：

```bash
redis-cli -a 1 DEL seckill:order:10    # 旧值是 SET，新脚本按 HASH 读取会报 WRONGTYPE
redis-cli -a 1 DEL stream.orders       # 旧消息流，已无代码读写
redis-cli -a 1 DEL seckill:tx:10       # 如调试期间残留了旧的 tx 标记
```

不清 `seckill:order:{voucherId}` 的话，预占脚本会在 `HEXISTS` 处直接报 `WRONGTYPE`，
表现为所有请求都失败。

## 四、OpenResty 秒杀网关（双层限流第一层）

```bash
docker compose up -d openresty
```

请求改走 `http://localhost:8080`（原直连 8081 的方式不变，两者并存）。
仅 `POST /voucher-order/seckill/{id}` 受全局令牌桶约束，其余路径仅转发。
令牌桶状态放 `lua_shared_dict`（跨 worker 共享），自旋锁保证「读-补-扣-写」原子性。

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `SECKILL_GATEWAY_ENABLED` | `true` | `false` 时网关仅转发（T3 对照组 A） |
| `SECKILL_TOKEN_RATE` | `500` | 令牌产生速率（个/秒），占位值，待容量实验固定 |
| `SECKILL_TOKEN_BURST` | `1000` | 桶容量（突发请求数），占位值，待容量实验固定 |
| `APP_UPSTREAM` | `host.docker.internal:8081` | 应用地址；应用跑容器时改为 `app:8081` |

第二层（用户／活动滑动窗口）在应用内，参数见 `application.yml` 的
`app.seckill.rate-limit.*`；`SECKILL_RATE_LIMIT_ENABLED=false` 即「仅令牌桶」的对照组 B。

观测口径：

- 网关拒绝：HTTP 429 + 访问日志 `gw_reject=1`；计数经 `GET /gateway/stats`
  读取 `{"total":..,"passed":..,"rejected":..}`（进程内存级，网关重启清零）。
- 用户拒绝 / 业务失败 / 系统异常：应用日志里 `SECKILL_METRICS outcome=USER_REJECTED |
  BUSINESS_REJECTED | SYSTEM_EXCEPTION`，逐请求可追溯。

## 五、常见问题

**端口被占用**

改 `.env` 或命令行传参换端口，例如 `MYSQL_PORT=3308 docker compose up -d mysql`。

**MySQL 起来了但表不存在**

八成是数据卷已存在，初始化脚本没重复执行。`docker compose down -v` 后重来。

**应用连不上中间件**

先确认 `docker compose ps` 里服务都是 `healthy`。应用依赖 `condition: service_healthy`，
健康检查没过就不会启动。

**应用连不上 broker**

RocketMQ 客户端会先连 namesrv 取 broker 地址，再直连 broker。应用在宿主机运行时，
broker 必须上报宿主机可达的地址——`docker/rocketmq/broker.conf` 里的 `brokerIP1 = 127.0.0.1`
就是为此设置，同时 compose 里映射了 `10911` 端口。缺任一项都会连不上。

**改了 SQL 想重新初始化**

需要 `docker compose down -v`，注意这会清空本地数据。
