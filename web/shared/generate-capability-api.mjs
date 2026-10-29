import { mkdir, writeFile } from "node:fs/promises";
import openapiTS, { astToString } from "openapi-typescript";

// 独立源码契约生成；不调用服务、不覆盖实时网关生成物，也不修改冻结声明 schema。
const source = new URL("../../contracts/capability-package.openapi.json", import.meta.url);
const ast = await openapiTS(source);
await mkdir(new URL("./generated/", import.meta.url), { recursive: true });
await writeFile(new URL("./generated/capability-package.d.ts", import.meta.url), astToString(ast));
console.log("Generated shared/generated/capability-package.d.ts from the EX-10/11 source contract (not live OpenAPI)");
