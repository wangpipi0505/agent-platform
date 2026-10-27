# Files.isExecutable 在 Windows 11 上的行为探针（2026-10-28，PLAT-21d）

```
os=Windows 11
javaExe=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\java.exe exists=true isExecutable=true
txt=C:\Users\ADMINI~1\AppData\Local\Temp\not-executable-probe.txt isExecutable=true
missing isExecutable=false
```

结论：Windows 没有独立 exec 位，JDK 对存在的普通文件（含 .txt）一律返回 true，只有文件不存在返回 false；
Unix 上该检查能真实拦住“传了脚本但没 chmod +x”的配置错误。注册期校验据此实现并如实写入契约 §4.3。
