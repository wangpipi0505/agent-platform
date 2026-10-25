The first `rg` request used a PowerShell path glob `.local/iam-n3-admission-app-client/*.mjs`; rg received the literal unsupported path and exited 1 with Windows error 123. No source or target write occurred. The exact known archive.mjs was then read successfully. This is tool lookup noise, not behavioral red.

The first parallel source/review reads exceeded the outer tool output budget. RuntimeClients was read again separately in full through the affected region; the review tail was separately read. No test or conclusion uses the truncated combined output.

Final evidence `node .local/iam-n3-admission-app-client-fix/finalize.mjs` exited 1 after completing the physical source/target freeze, actual patch check/apply/replay, test/XML audit and patch-check.json. Its upstream manifest builder incorrectly treated the archived metadata's source basename as a workspace-relative path:

```text
Error: ENOENT: no such file or directory, open 'G:\207\项目\算法重组工具\代码\agent-platform\httpclient5-5.4.4-sources.jar'
    at Object.openSync (node:fs:561:18)
    at Object.readFileSync (node:fs:445:35)
    at item (file:///G:/207/%E9%A1%B9%E7%9B%AE/%E7%AE%97%E6%B3%95%E9%87%8D%E7%BB%84%E5%B7%A5%E5%85%B7/%E4%BB%A3%E7%A0%81/agent-platform/.local/iam-n3-admission-app-client-fix/finalize.mjs:3:30)
    at file:///G:/207/%E9%A1%B9%E7%9B%AE/%E7%AE%97%E6%B3%95%E9%87%8D%E7%BB%84%E5%B7%A5%E5%85%B7/%E4%BB%A3%E7%A0%81/agent-platform/.local/iam-n3-admission-app-client-fix/finalize.mjs:15:456
    at ModuleJob.run (node:internal/modules/esm/module_job:439:25)
    at async node:internal/modules/esm/loader:633:26
    at async asyncRunEntryPointWithESMLoader (node:internal/modules/run_main:101:5) {
  errno: -4058,
  code: 'ENOENT',
  syscall: 'open',
  path: 'G:\\207\\项目\\算法重组工具\\代码\\agent-platform\\httpclient5-5.4.4-sources.jar'
}
Node.js v24.16.0
```

This is the actual returned tool diagnostic transcribed here; the successful preceding steps were not rerun. `complete-upstream-resources.mjs` continues only the two unwritten upstream/resources portions using the exact archived source path and SHA. No business code, target, test or Maven execution was repeated for this documentation error.
