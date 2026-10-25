import { it, expect } from "vitest";
import { createApi } from "../shared/api";
it("hides SYS internals while preserving trace for support", async () => {
  const api = createApi(
    () => "t",
    async () =>
      new Response(
        JSON.stringify({
          code: "SYS-DB",
          message: "jdbc password internal",
          traceId: "trace-2",
        }),
        { status: 500 },
      ),
  );
  await expect(api("/admin/apps")).rejects.toMatchObject({
    message: "系统繁忙，请稍后重试",
    traceId: "trace-2",
  });
});
