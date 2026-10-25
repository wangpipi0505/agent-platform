# F1 固定 parser 与 64MiB 预收费证明

本模型对应本工程 Jackson core 2.19.0 与实际 Adoptium HotSpot JDK 21.0.12.8，不是 GC 后 heap 峰值声明。全部所述路径均来自本目录 `primary/` 保存的具名源码；没有声称完整审阅整个 Jackson/JDK。原 root primary notes 只是核查起点，不作为本地费用证明。

## 实际分支与缓存生命周期

`PortalEventsPacketDecoder.scan` 每次用私有 `factory` 创建一个 factory/parser；header、metadata、payload 顺序调用，没有并行 parser。UTF8 是 `new InputStreamReader(ByteArrayInputStream(bounded-range), UTF_8.newDecoder().REPORT/REPORT)`。因此不经过 Jackson byte bootstrap，不会自动把 UTF16/32 识别为有效输入。JDK StreamDecoder byte backing 固定8192，ReaderBasedJsonParser 输入 `char[4000]`。JDK UTF_8 decoder 对编码的 surrogate 返回 malformed；JSON `\\u` escapes 则由本地逐 UTF16 unit 检查拒绝未配对 surrogate。所有 VALUE_STRING 显式 finishToken→getTextLength→getTextCharacters；FIELD_NAME 通过 currentName 验证而不触发独立 name-copy 缓冲；不 skipChildren。

工厂禁用 `CANONICALIZE_FIELD_NAMES`、`INTERN_FIELD_NAMES`、`USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING`，启用 STRICT_DUPLICATE_DETECTION，禁用 INCLUDE_SOURCE_IN_LOCATION。JsonFactory._getRecyclerPool 在该 flag 关闭时直接返回 nonRecyclingPool；每次 new BufferRecycler，所有 AtomicReferenceArray 缓冲仅在当前 parser/context 图内。关闭可能把 currentSegment 放进该局部 recycler，也可能在 TextBuffer 留下 resultString，不能认为 close 本身清除所有引用。扫描方法实际退出后 factory/parser/Reader/IOContext/recycler 图不可再被组件访问；组件不缓存 parser，不返回 Jackson exception/cause，不从包导出该图。`scan` catch 只抛新建常量诊断 IOException，避免 Jackson exception 带 parser/ContentReference 越过阶段退账。保留的 header 为小 scalar；WINDOW map 只有14根字段的 token marker/boolean。无 payload/metadata DOM、大字符串返回或大 buffer pool。

## 最大同时可达工作集（保守费用）

设 M=8388608 wire bytes，T=262144 tokens（包括 FIELD_NAME 与 END）。一个属性至少占一个 name token 与一个 value/start token，允许 Jackson 预解析下一个值及拒绝前少量进展，取 N=ceil((T+4)/2)=131074。设 L=1048576 UTF16 units，S=L+65536=1114112。

| 项 | 保守计费字节 | 推导 |
|---|---:|---|
| 所有仍可达 name String/数组头/HashMap 节点与表 | 33554944 | 256×N；每 name String≤48B，字符串数组头及对齐≤32B，碰撞 TreeNode≤112B（含 inherited Node/LinkedHashMap Entry），新旧 table 在 resize 并存摊销≤32B/name，尚余32B/name。小集合、context 另计下面4MiB。以8B references/保守24B普通对象头/32B数组头建模，大于实际本JVM默认开销。Resize 复用 node；treeify/untreeify 的少量瞬时节点由余量承担。 |
| 全部 name 字符内容 | 16777216 | 2×M；decoded UTF16 units≤该段 wire bytes；escaped 字符消耗更多 wire，无法放大该比值。该项覆盖不压缩字符串。 |
| 当前 scalar 的分段、聚合、转换/短暂复制 | 11141120 | 10×S；TextBuffer 1.5倍增长但单段≤65536；finishCurrentSegment 在累计超L时拒绝，故活跃segments+末段≤S。字符segments≤2S，聚合char[]≤2S，当前name UTF16 byte[]≤2S，尝试Latin1≤S，剩余3S覆盖前一scalar过渡、array header/list扩容、header小String与错误诊断。数值尚未触发number1000校验前也沿finishCurrentSegment受同L+段余量保护；从不把业务numeric转double/BigDecimal。 |
| reader/parser/factory/IOContext/recycler/context/小模型/hash/UTF8/scratch余量 | 4194304 | 单reader8192B、input char[4000]、局部recycler初始char[200]、≤82层context/dup detector、小root map≤14、factory初始symbol表、digest与≤8192读块；没有per-token DTO。4MiB显著大于逐项固定分配（即使所有深度保留小HashSet表）。 |
| 总工作集费用 | 65667584 | 上述之和，<67108864；余1441280B。 |

该推导约束同时可达对象，不能用 ThreadMXBean 的累计分配或一次 GC 峰值代替。动态探针仅佐证：私有实际 production factory 的 parser 类型、实际char[4000]与1MiB聚合backing；真实readNext在首次read前已预留64MiB，且单包真实wire数组长度/charge可观察。最终XML system-out记录具体数值。

wire自身在64MiB之外另收费：prefix `24+32`，header `actualHeaderLen+32`，完整 wire `actualTotal+32`；每 Reservation token再加128B。先checked long+各段交集、后cast/new；完整wire仅一份，header/prefix临时副本在其phase token归还前清引用。32B涵盖数组头及对齐，超过brief至少24B要求。Owner基础65536B预收费覆盖decoder/owner、≤8192 header的scalar和包/引用基础；borrow wrapper每个256+128B另预收费。单Owner上限100663296B、共享201326592B、2slot/零queue；上限组合也可因F2自己的model/frame而拒绝，未声称HTTP/TLS/JDK transport缓冲在此账本内。

## 已读具名范围（行号以保存的2.19.0 source文件为准）

- JsonFactory.java：1291–1298 createParser(Reader)定位；1900–1912 _createParser(Reader)；2158–2219 _getRecyclerPool/_createContext。
- ReaderBasedJsonParser.java：140–235 constructor/input close/release；380–440 getTextCharacters/getTextLength；1538–1680 _parseNumber2（含finishCurrentSegment）；1809–1900 _parseName/_parseName2；2142–2240 _finishString（没有走_skipString）。
- ParserBase.java：390–440 close；507–533 _releaseBuffers；584–606 resetInt/resetFloat已具名定位数值长度验证。ParserMinimalBase.java：token计数的853–865具名定位；本地仍独立count全部token。
- IOContext.java：286–305 constructReadConstrainedTextBuffer；387–409 token/name alloc；442–465 release；494–506 close。
- BufferRecycler.java：90–190数组默认尺寸/分配；186–218字符分配和局部释放。JsonRecyclerPools.java：130–170 NonRecyclingPool。
- TextBuffer.java：155–210 release/reset；430–530 chars/strings/aggregation；1005–1040 bounded segment增长；1146–1245 unshare/expand/resultArray；ReadConstrainedTextBuffer.java：1–40 validateStringLength委托。
- CharsToNameCanonicalizer.java：250–310 constructor flag；387–413 release；490–520 no-canonical path validateNameLength/new String。DupDetector.java：15–111 name retention/HashSet；JsonReadContext.java：85–160 reset/context；153–208 child reuse。
- StreamReadConstraints.java：max* setter定位；490–585 document/token/number；root notes已核122–203/481–555。精确UTF16 length由本地完成token检查补足。
- JDK本地src.zip具名提取：StreamDecoder.java 300–340/369–432；String.java 270–315/4748–4770；StringUTF16.java150–230；UTF_8.java258–290/345–375；HashMap.java281–300/683–754/1960–1985；LinkedHashMap.java190–213；HashSet.java115–145。只读这些实际路径，未读JDK其余实现。

模型限制：这是本任务固定provider/无DOM代码路径的可执行保守分配费用；不是所有JDK、可替换provider、业务DOM、生产容量、socket取消SLA的普遍上界。后续若F2增加保留图、换parser或provider，必须重新证明并从同一Owner获取额外费用。
