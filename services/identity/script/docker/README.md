# 身份服务 Docker 样例

**简体中文** | [English](README.en.md)

本目录保留若依的多服务部署样例；FunnyWork 配套服务部署见[自建服务指南](../../../../deploy/SERVER_DEPLOYMENT.md)。数据库、Redis 和对象存储的口令由部署环境提供。

## 准备凭证

从仓库根目录进入本目录，创建受限环境文件：

```sh
cd services/identity/script/docker
cp runtime.env.example runtime.env
chmod 600 runtime.env
```

在 `runtime.env` 中填写独立生成的口令。`docker-compose.yml` 需要 MySQL、Redis 和 MinIO 三项；`database.yml` 的 SQL Server、PostgreSQL 和 PostgreSQL 13 需要各自口令。`runtime.env` 已排除 Git 跟踪，文件内容保存在部署环境。

启动前按 Compose 的卷映射准备 `/docker/` 下的配置和数据目录，包括将 `redis/conf/redis.conf` 放到 `/docker/redis/conf/redis.conf`，并提供 Nginx 配置和证书。该样例使用宿主机网络；在宿主机防火墙中仅向受信任服务开放数据库、缓存和对象存储端口。已有数据库的数据目录会保留原账号口令，修改环境变量后须按数据库流程更新账号，并同步业务配置。

## 检查与启动

从本目录运行，只输出校验结果：

```sh
docker compose --env-file runtime.env -f docker-compose.yml config --quiet
docker compose --env-file runtime.env -f docker-compose.yml up -d
```

缺少或清空必需口令时 Compose 拒绝解析配置。Redis 启动命令从运行环境传入口令，独立配置文件仅绑定回环地址。SQL Server 和 MinIO 的口令需同时符合对应服务的长度及复杂度要求。旧 Oracle 镜像样例用于隔离测试；正式部署使用受维护的数据库及其账号初始化流程。

仅校验 `database.yml` 时，将上述文件参数替换为 `database.yml`。校验日志不保存完整展开配置；完整 `docker compose config` 可能包含运行时注入的凭证。保存备份和数据目录后再进行升级或清理。
