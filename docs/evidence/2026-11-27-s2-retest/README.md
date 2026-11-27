# S-2 双角色 E2 复验

候选 `cb378202`，真实 ADMIN 与受限 USER，1440、1024、768 三种屏宽共 **48/48** 通过。全矩阵无 HTTP、浏览器控制台或页面脚本错误，无文档横向溢出。USER 管理入口不可见，直链被拒，导航和角标与平台授权投影一致。

| 接口核对 | 结果 |
| --- | --- |
| ADMIN manifest 头与版本、规则列表 | 200 / 200 / 200 |
| USER 直调 manifest 和规则列表 | 403 PLATFORM_CAPABILITY_DENIED，两条 |
| USER 在应用范围内读取分类 | 200，仅投影授权应用的业务分类 |
| USER 工作区 | 概览可用，未发起构建目录、manifest 或规则读取 |
| 清理 | 临时 USER 授权已回收，两张门户票据撤销返回 200，user-mgmt 临时密钥已撤销 |

[双角色原始矩阵](page-matrix.json) · [接口回执](interface-matrix.json) · [授权回收](cleanup.json) · [部署自检](../2026-11-27-s2-retest-freeze/selfcheck.txt)
