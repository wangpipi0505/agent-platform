# PhaseA首轮RED前：合法common重建的依赖重封

2026-10-29，纯文件准备；未执行PhaseA、Java/Maven/PG/HTTP，未改源/index/JAR或旧run。原从未执行候选先按原字节复制到unexecuted-7252-e9-20261029-01：runner仍7252B913…3720EE，旧manifest仍E9F31683…1F874C。phase-a/runs不存在。

本轮唯一新来源是本仓V222真实Green回执：
.local/ex27-v222-recovery/v222-resource-green-20261029-01/green-dependency-receipt.json
SHA F4D04843CB224F86F1CB5CE7E093E9BD23F8E02C9F3917A025F2355A63770025。

回执为HEAD6ca930d8、4/4P、build/test native0、collector gate=true；source-after306条摘要 1CA4A1FF19A5A8E6141C0D92BF992354AA26D915F8BADA8FFE9BE4EC2F42E337，installed-after-test摘要 11236879CBFB1DB4FF05B5FF4986A454AC55F3C9F1A04B5B8A36657B3784CCAE。上述文件及summary/approved manifest/collector的实际摘要与回执逐项一致，installed-after六项与receipt六项一致；未从任意Git tree补造依赖来源。

仅更新PhaseA manifest的主/tests两项common context pin和两项缓存摘要：
- main：旧1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87 → Green0FBB83F6E6ADCF9E36BA27345602AA93AA4769D863CF2635FE0357EC7DA29A93。
- tests：旧1C91223A6FC2A97B0B5EF43370440BFA9C4761EF4EAC280748DE470E6F30859E → Green0CA864F064A758ED9EA72BCD8EEEDE532C771F5F7BD51F0A2E9B42818E7AB732。

主JAR原来参与skill classpath；tests未在原skill POM/Full701 classpath观察到，仍作保守context pin，没有假称参与。其余context、152源、三Test94A0/65ED/6E4A、产品75F89B/5A0C、契约194D、HEAD6ca、30身份/原25P＋新2P＋准确3F、原因及预算全部不改。runner原字节仍7252。

## ZIP逐项结果

common-jar-entry-comparison-20261029-01.json保存每个entry解压字节SHA/长度、原压缩payload SHA和元数据变化，不保存class或资源内容。

| JAR | 全entry | class / 资源 / 目录 | entry内容变化 | 压缩payload变化 | ZIP元数据变化项 |
| --- | --- | --- | --- | --- | --- |
| main | 126 | 112 / 4 / 10 | 0 | 0 | 124 |
| tests | 110 | 97 / 3 / 10 | 0 | 0 | 109 |

旧原件只用V222 RED的installed-common-before真实1B/1C快照，新原件只用Green回执指向且当前字节核同的installed JAR。entry集合、解压内容、压缩payload均逐项相同，全JAR SHA不同仅ZIP容器元数据；无代码/资源变更路径。未以源码相似或一次测试通过替代字节比对，不作超出字节相同的泛语义等价声明。

Full701实际仍使用旧1B，不回写其command/context/summary；PhaseA仅以Full701作25个旧方法身份/状态基线，不称它用了新JAR或原始依赖摘要相同。本轮只是首RED前新依赖候选，尚无PhaseA实际结果。最终manifest新SHA另行交接；仍需root静审/唯一实际窗，不能拿旧E9或替换回旧JAR过pin。