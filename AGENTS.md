本项目目录存放以历年复试真题和个人练习为核心的小型 OJ 项目

+ 围绕 `git` 工作流进行开发，开发新功能或者调整项目代码时需要另开新分支测试完成后再合并在 `main` 分支中
+ `git` 提交信息应符合标准的 `git` 消息：
  + <type>([optional scope]): <description>
  + type 由如下类型：
    + feat：新增功能（feature）
    + fix：修补 Bug
    + refactor：代码重构（既不是新增功能，也不是修改 Bug）
    + test：测试用例添加或修改
    + style：代码格式调整（不影响代码运行，如空格、分号等）
+ 分支名因遵循：<type>/<description>
+ type 应该为：
  + dev: 开发新功能
  + fix: 修补 Bug
  + refactor: 代码重构
  + style: 不影响代码运行的格式调整
+ postgresql 可使用本机的服务。用户为 `riversea` 该用户具有创建数据库的权限，但不是超管。密码为 `1`(仅unix socket连接需要提供)，tcp/ip 连接不需要。也可以使用docker容器启动一个新的 postgresql 容器
+ 添加适合的中文注释，为某些步骤提供注释。配置，字段，函数，类等添加全量的中文注释
+ 不要写入例如下面这样的描述性冗余文字：
  + 人: 包括男人和女人
  + 花：这是一个植物
+ gradle 使用 `/home/riversea/.sdkman/candidates/gradle/9.6.1/bin/gradle`
+ python 的包管理使用uv，已经添加进 `path`, 直接使用即可
