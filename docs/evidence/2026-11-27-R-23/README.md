# R-23 宿主受理、重试与受控下载
E2 现有 Java 8 宿主报告 READY。经管理员正式签发的接入凭据提交文本测试，宿主受理、详情、事件与结果均在[回执](e2-host-download.json)留存。同 requestId 重试返回同一任务 `task-6eced383ac17442f838d`，两次均为 SUCCEEDED。
用回执中的下载路径、接入凭据和已登记的主体属性独立下载[原件](e2-downloaded-result.json)。读取 606 字节，SHA-256 为 `ae20bf70ccc7ac973b3238e50328f1f9d07a566a002c2fd6ad15cc9f992e4e78`，与 HTTP ETag 及回执 digest 一致。临时凭据已回收，测试宿主已退出。上轮缺凭据和轮询超时样本见[先前实测](../2026-11-26-R-23/README.md)。
