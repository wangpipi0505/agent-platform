/** Emit real production form-builder output; Java consumes it unchanged. No network/DB. */
const fs = require("node:fs");
const path = require("node:path");
const Module = require("node:module");
const ts = require("typescript");
const source = path.resolve(__dirname, "../shared/semantic-runtime.ts");
const compiled = ts.transpileModule(fs.readFileSync(source, "utf8"), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
}).outputText;
const loaded = new Module(source, module);
loaded.filename = source;
loaded.paths = Module._nodeModulePaths(path.dirname(source));
const originalRequire = loaded.require.bind(loaded);
loaded.require = (name) => name.endsWith("?raw")
  ? fs.readFileSync(path.resolve(path.dirname(source), name.slice(0, -4)), "utf8") : originalRequire(name);
loaded._compile(compiled, source);
const { buildGoalCriterion } = loaded.exports;
const base = { goalId: "order-B", skillCode: "verify", target: { objectType: "Order", objectId: "order-B" },
  readbackArguments: { id: "order-B" }, objectIdPath: "$.id", resultObjectIdPath: "$.data.id", predicatePath: "$.data.rows[0].value" };
const scenarios = [
  ...["CONFIRMED", false, 0, null].map(value => ({ operator: "equals", expectedValue: value, value, achieved: true })),
  { operator: "exists", value: null, achieved: true },
  { operator: "exists", missing: true, achieved: false },
  { operator: "nonEmpty", value: false, achieved: true },
  { operator: "nonEmpty", value: 0, achieved: true },
  { operator: "nonEmpty", value: null, achieved: false },
  { operator: "nonEmpty", value: [], achieved: false },
];
process.stdout.write(JSON.stringify(scenarios.map(scenario => ({
  goal: buildGoalCriterion({ ...base, ...scenario }),
  result: { data: { id: "order-B", rows: [scenario.missing ? {} : { value: scenario.value }] } },
  achieved: scenario.achieved, present: !scenario.missing, value: scenario.value,
}))));
