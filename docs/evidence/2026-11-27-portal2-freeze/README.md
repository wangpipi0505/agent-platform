# E2 门户主体绑定候选部署

远端主干与标签 `r42-20261127-portal2` 均核到 `76fc32528795f9201a80cfeb9acb6544d0e0550a`。从冻结源码编译七个后端镜像并重新打包前端。切换前保存数据库与编排配置，数据库备份 787 项。生效配置比对仅包含候选镜像及网关、前端两个只读文件的挂载位置。网关新配置使用经验证签名主体的归属绑定。

[远端引用](remote.txt) · [原始备份](prebackup.txt) · [配置核对](dry-config.txt) · [八容器部署和验证](activated.txt) · [业务复验后八容器与在线摘要](final-health.txt) · [六个 Prometheus 目标](prometheus.txt) · [测试凭据、授权和宿主回收](cleanup.txt)。

复算镜像归档可用 `sha256sum -c images.sha256`，取各容器的 `docker inspect -f '{{.Image}}'` 与 [归档镜像 ID](image-ids.txt) 逐项比对。

公共鉴权模块全套 405 项，4 个断言失败、1 个错误。三个涉及类在前一候选 `ed4b3f8e` 上定向重跑，复现同五项。门户修复定向 72 项均通过。[全套计数](common-suite.txt) · [前一候选对照](common-previous.txt)。
