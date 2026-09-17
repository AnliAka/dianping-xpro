# dianping-xpro

基于 Spring Boot、MyBatis-Plus、MySQL 和 Redis 的点评与优惠券秒杀项目。

## 项目标识

- Maven 坐标：`com.dianping.xpro:dianping-xpro:0.0.1-SNAPSHOT`
- Java 包名：`com.dianping.xpro`
- 启动类：`com.dianping.xpro.DianpingXproApplication`
- 默认数据库：`dianping_xpro`
- 数据库脚本：`src/main/resources/db/dianping-xpro.sql`

## 本地运行

使用 JDK 25 和 Maven 3.6.3+，准备 MySQL 8.0+ 与 Redis 5.0+（秒杀队列使用 Redis Streams）。先通过 `java -version` 和 `mvn -version` 确认 Maven 使用 JDK 25。公共配置位于 `src/main/resources/application.yml`；本机密码统一保存在项目根目录的 `config/application-local.yml`，该目录已被 Git 忽略，也不会打入 JAR。

新克隆的项目需要自行创建该本地文件，填写以下配置（占位内容需替换）：

```yaml
spring:
  datasource:
    password: "填写数据库密码"
  data:
    redis:
      password: "填写Redis密码"
```

本地文件也可以覆盖数据库地址、Redis 主机等公共配置。IDEA 的工作目录设为项目根目录，程序通过 `spring.config.import` 自动加载外部配置，无需指定 `local` profile。登录使用随机 Token + Redis 会话，未使用的 JWT 工具和依赖已移除。

新环境先创建 `dianping_xpro` 数据库，再在该库中导入 SQL 脚本。脚本包含删表语句，只用于初始化，不要对已有业务数据直接执行。

已有环境可以通过环境变量 `DB_NAME` 指定现有数据库，无需立即迁移数据。若要统一数据库名称，请先备份并将现有数据迁移到 `dianping_xpro`；项目文件改名不会自动迁移运行中的数据库。

图片上传目录位于 `SystemConstants.IMAGE_UPLOAD_DIR`，默认指向 `D:\lesson\nginx-1.18.0\html\dianping-xpro\imgs\`。请按实际图片存放位置修改此常量，并同步 Nginx 静态资源配置。

```shell
mvn clean verify
mvn spring-boot:run
```

打包运行时自动读取工作目录下的 `config/application-local.yml`：

```shell
java -jar target/dianping-xpro-0.0.1-SNAPSHOT.jar
```

以上命令在项目根目录执行。部署到其他机器时，将私有配置放在运行工作目录的 `config/application-local.yml`；如需自定义位置，可通过 `--spring.config.import=file:/实际路径/application-local.yml` 指定。

构建产物为 `target/dianping-xpro-0.0.1-SNAPSHOT.jar`，服务默认监听 `8081` 端口。

## 改名后的衔接

在 IDE 中重新打开 `dianping-xpro` 文件夹并重新加载 Maven 项目，运行配置使用新的启动类。

## 技术栈版本

| 组件 | 版本 |
| --- | --- |
| Java | 25 |
| Spring Boot | 3.5.16 |
| MyBatis-Plus | 3.5.17（Boot 3 starter + 独立分页模块） |
| Hutool | 5.8.47 |
| Redisson | 3.52.0 |
| MySQL Connector/J | 9.7.0（Boot 管理） |
| Jackson | 2.21.4（Boot BOM 管理，annotations 为 2.21） |
| Lombok | 1.18.46（Boot 管理） |

Redisson 采用与 Boot 3.5 的 Netty 4.1 匹配的 3.x 版本。Spring、Jackson、Lettuce 等统一由 Boot 管理，避免混用旧版依赖。

Servlet 和生命周期注解使用 `jakarta.*`；Redis 配置前缀为 `spring.data.redis`；MyBatis-Plus 的 Service API 使用 `com.baomidou.mybatisplus.spring.service`。

IDEA 的 Project SDK、Language level 和 Maven Runner JRE 均设为 25，然后重新加载 Maven。Lombok 注解处理器已显式配置，无需旧版临时兼容参数。生成的 JAR 需要 Java 25 或更高版本运行。

秒杀消费者在应用就绪后启动，通过延迟注入的事务代理创建订单，并在应用关闭时停止线程，无需开启全局循环依赖。

`mvn clean verify` 执行编译、回归测试和打包。测试使用 H2 的 MySQL 模式和模拟的 Redisson 连接，验证 Spring 上下文、MVC、JSON 时间序列化、分页、Redis 配置、订单事务回滚，以及重启时已存在的 Redis 消费者组处理。不连接本机 MySQL/Redis，不代表真实秒杀链路已完成联调。
