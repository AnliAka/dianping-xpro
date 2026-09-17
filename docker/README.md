# Docker 环境说明

用 `compose.yaml` 在本机拉起 MySQL 8 与 Redis 7，应用可以选择跑在容器里，也可以继续在
IDE / `java -jar` 里跑。两种方式共用同一套中间件。

## 文件一览

| 文件 | 作用 |
| --- | --- |
| `compose.yaml` | 编排 MySQL、Redis 与可选的应用容器 |
| `Dockerfile` | 应用运行镜像，多阶段构建，最终只有 JRE + jar |
| `Dockerfile.build` | 单独的编译阶段镜像，便于复用 Maven 依赖缓存 |
| `.dockerignore` | 构建上下文排除项，防止本机配置与产物进镜像 |
| `docker/README.md` | 本文件 |

## 一、只起中间件（推荐日常开发用）

应用在 IDE 里跑，中间件交给 Docker：

```bash
docker compose up -d mysql redis
docker compose ps
```

宿主机端口刻意错开了系统默认值，避免和本机已装的 MySQL / Redis 打架：

| 服务 | 宿主机端口 | 容器内端口 | 账号 / 密码 |
| --- | --- | --- | --- |
| MySQL | `3307` | `3306` | `root` / `1234` |
| Redis | `6380` | `6379` | 密码 `1` |

也就是说，本地连库要写 `localhost:3307`，连 Redis 写 `localhost:6380`。

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
docker compose up -d mysql redis
```

`-v` 会删除数据卷，本地数据一起没，确认清楚再执行。

## 二、让应用也在容器里跑

```bash
docker compose --profile app up -d --build
docker compose logs -f app
```

应用容器里通过服务名访问中间件（`mysql:3306`、`redis:6379`），这些地址由
`compose.yaml` 的环境变量注入，不需要改 `application.yml`。

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
`config/application-local.yml`，密码和本机路径就能被读到，而这个文件不入版本库、
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

## 三、Redis Stream 消费者组与重启

秒杀链路是异步的：

```
seckill.lua  →  XADD stream.orders  →  消费者线程  →  落库 tb_voucher_order
```

消费者组 `g1` 由 `VoucherOrderServiceImpl.createStreamGroup()` 在应用启动时创建
（`XGROUP CREATE ... MKSTREAM`）。

**关键点：Redis 开启 AOF 持久化后，消费者组已存在，应用再次启动会收到
`BUSYGROUP Consumer Group name already exists`。**

这个异常不能直接当失败处理，否则重启就崩。代码里的处理方式是沿异常 cause 链查找：

```java
} catch (RedisSystemException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
            return;
        }
    }
    throw e;
}
```

不能只看外层的 `e.getMessage()`。Spring Data Redis 会把原始错误包在 cause 里，
外层 `RedisSystemException` 的 message 固定是 `"Error in execution"`，
`BUSYGROUP` 只存在于 cause 链中。只判断外层的话，这个分支永远不会命中。

`compose.yaml` 里 Redis 默认开启 `appendonly yes` + `appendfsync everysec`，
重启不丢消费者的 pending 消息。

## 四、常见问题

**端口被占用**

改 `.env` 或命令行传参换端口，例如 `MYSQL_PORT=3308 docker compose up -d mysql`。

**MySQL 起来了但表不存在**

八成是数据卷已存在，初始化脚本没重复执行。`docker compose down -v` 后重来。

**应用连不上中间件**

先确认 `docker compose ps` 里两个服务都是 `healthy`。应用依赖
`condition: service_healthy`，健康检查没过就不会启动。

**改了 SQL 想重新初始化**

同样需要 `docker compose down -v`，注意这会清空本地数据。
