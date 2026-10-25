# O2a B：解析器与必需公共纯校验的缓存寿命

root 补充正式 B brief 和预算决定；原8源码路径不扩，不改公共 Json、全局 factory、A 权限校验或部署。

作者窄读 Jackson2.19.0 实际 sources 后报告：默认 JsonFactory recycler 是线程本地，字段名 canonicalizer 在 release 时可合并缓存。跨请求或不同 Tomcat 调用线程保留的大 buffer 不能当作“工作区退出已释放”的临时区。B 自身两 factory 要关闭 USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING、CANONICALIZE_FIELD_NAMES、INTERN_FIELD_NAMES；自身 canonical/编码同样使用本地 non-recycling writer。实际配置、版本和来源须在最终代码/报告复核。

另有不可跳过的 `capture.requireMaterial → MaterialAuthorization.scopeDigest → 公共 Json.canonical` 纯权限校验，可能使用公共 serializer 的线程本地 TextBuffer。不得为了缓存控制删改这条权限校验、另造材料权限 proof、修改公共 factory 或默认验证语义。

允许在 B 自身实际退出 finally 中做最小 cache-only cleanup：

- 仅清理**当前线程**上、实际公共 Json 所用的同一2.19 recycler。先以具名源码确认实例一致及槽集合；不能猜测 API 与实例关系。
- 通过公开 API 逐槽取出并丢弃缓存 buffer；不归还大 buffer，不反射、不全局 releaseBuffers、不遍历/跟踪其他线程，不改其他线程 recycler。
- 空槽公开 API 若会分配默认小 buffer，必须证明有限并计入临时预算；所有同时存活的缓存相关表示仍纳64/8/192MiB账本。不能用清缓存代替对象表示上界。
- cleanup 不能遮蔽原权限、材料或数据库异常，也不能让已转移的 pending emission/permit 因 cleanup失败而泄漏；严格按实际调用退出安排资源释放。

新增具名 focused RED→GREEN，验证当前线程大 buffer 不在 B 实际退出后继续缓存，并确认其他线程缓存不受影响。此测试与已归档 GREEN 阶段分开；不重跑已成功旧阶段或原样本。最终受影响命令仍按正式 brief 单次执行。

如公开 API 无法窄保证同实例/有限清理，作者须报告具名源码，不得用全局或反射手段绕过。root 对范围的授权不替代独立 reviewer 对这份设计与实际实现的正确性判断。

EOF
