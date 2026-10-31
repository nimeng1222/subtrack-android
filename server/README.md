# SubTrack 自托管服务（v1.4 开发预览）

当前实现真实 HTTP 模板目录与**不透明操作日志服务**，不是已经验收的 Android 多设备同步产品。Android outbox、冲突合并、恢复/轮换 UI 和双设备全矩阵仍未完成，客户端同步入口保持关闭。不要用此服务互相覆盖 JSON 备份。

## 本机

Python 3.12+，运行时仅标准库，无付费服务依赖：

```sh
export SUBTRACK_SETUP_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
SUBTRACK_DEBUG_HTTP=1 python3 app.py
python3 -m unittest -v test_server.py
```

默认绑定 127.0.0.1:8080，明文仅用于隔离本机开发；模拟器 10.0.2.2:8080。初始 setup token 是管理员初始化凭据，不可放 APK，不可公开。生成凭据在终端环境，示例文件不包含秘密。数据默认 data/sync.sqlite，文件 0600，WAL 模式。

## 自托管部署

配置 SUBTRACK_HOST 为自己的真实域名，SUBTRACK_SETUP_TOKEN 为随机至少 32 字符。`docker compose up --build -d`。只暴露 Caddy 443，app 仅内部网络，TLS 证书由 Caddy 验证/更新。不要把 app 8080 公开或信任任意外部 X-Forwarded-Proto。容器非 root、只读根目录，持久卷 ledger 保存数据库，健康检查 /healthz。不提供公开部署/域名购买自动化。

升级前停止 app，完整备份持久卷 SQLite（含 WAL 或用 sqlite backup API）；schema user_version=1，未来不兼容迁移须先编写事务迁移。当前没有日志回收，游标永久有效，长离线要考虑磁盘成本，不能任意删除操作。Python/Caddy 基础镜像需维护安全更新，公网还需外围认证请求限流/监控/备份与容量规划。

## API

- GET /healthz，GET /v1/templates 为公开只读。
- POST /v1/register，头 X-Setup-Token，body {name}：创建独立租户/设备，返回 accessToken、deviceId、tenantId、recoveryCredential。恢复凭据仅用于认证，不是数据解密密钥。
- POST /v1/pairing（Bearer）：一次性 5 分钟高熵配对凭据；POST /v1/pair {credential,name} 消费它，创建独立设备 token。
- POST /v1/recover {credential,name}：凭恢复凭据重新认证。**服务端不持有数据密钥，不能恢复账本明文**。
- GET /v1/devices，POST /v1/revoke {deviceId}：仅当前租户；撤销后旧 token 每次请求都拒绝，配对凭据作废；客户端还需轮换未来数据密钥（未完成）。
- POST /v1/ops {ops:[{opId,envelope:{version:1,keyEpoch,nonce,ciphertext}}]}：AES-GCM nonce 12 字节、ciphertext 含 tag；base64。每批 1..100，单密文最大 64 KiB，整请求 1 MiB；全部事务提交后响应。重复 opId 同载荷返回原修订，不同载荷 409 并整批回滚。
- GET /v1/changes?after=0&limit=100：当前租户单调 seq 增量、hasMore、cursor。客户端必须应用成功提交后才持久化 cursor。不能把上传响应当下载 checkpoint。

设备 token 256 位随机，服务端只存 SHA-256。无默认 token、无自制口令散列。每个来源 IP 每分钟最多 120 个认证 API 请求，客户端超时/响应大小边界独立实施。无秘密或请求体日志。

模板样本覆盖 CNY/USD/EUR/JPY、月付/年付；**所有示例价格未知，未做官方现价核验**，sourceUrl 只是官网入口，不是假价格证据。静态部署 templates.json 也可，仅 HTTPS。数据编辑后增加 dataVersion，schemaVersion 不兼容客户端必须拒绝，不得破坏旧缓存。
