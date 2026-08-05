# 安全基线

- 正式环境只允许 HTTPS，并启用 Secure、HttpOnly、SameSite 会话 Cookie 和 CSRF 防护。
- 密码使用 Argon2id；注册、登录、验证码和找回密码按 IP/账号限流。
- Worker Token 与 go-judge Token 必须不同、至少 40 位、可撤销，数据库只保存 Worker Token 哈希。
- PostgreSQL、API 和 go-judge 均不直接暴露公网；远程 Worker 只访问租约范围的 HTTPS API。
- 导入包限制大小、展开总量、文件数和路径，拒绝绝对路径、目录穿越、重复键与压缩炸弹。
- 用户源码、隐藏输入、标准输出和实际输出不通过用户提交接口返回。公开“运行”只处理用户可编辑输入并返回该次实际输出。
- go-judge 禁用网络、强制 `-no-fallback`，限制 CPU、墙钟、内存、进程、打开文件、输出和临时文件系统。
- AI 密钥只从环境变量读取；模型输出不能直接成为标准答案，发布必须有真实沙箱差分证据。
- 定期演练 Token 撤销、Worker 中断、租约过期、备份恢复、输出洪泛、fork bomb、内存耗尽和联网尝试。
