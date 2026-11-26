# E2 冻结与切换

远端分支及标签见 [冻结记录](freeze.txt)。七个后端 JAR 来自冻结提交的独立源码归档，以 JDK 21 和项目本地依赖库打包。前端使用 portal 模式，从同一提交构建三套产物，沿用 E2 已验证的 Nginx 运行时，挂载新静态目录。

[切换前数据库备份摘要及原容器](prebackup.txt) · [归档 SHA-256](images.sha256) · [八镜像配置摘要](image-ids.txt) · [切换回执](cutover.txt) · [运行 ID、健康及线上静态文件摘要](verify-final.txt)。镜像 ID 取自实际传输归档内的配置对象。
