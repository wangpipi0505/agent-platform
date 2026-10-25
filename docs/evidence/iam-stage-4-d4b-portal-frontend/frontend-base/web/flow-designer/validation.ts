import Ajv2020 from "ajv/dist/2020";
import schema from "../../contracts/graph-definition.schema.json";
// Shared schema is the structural authority. Node IDs only annotate errors for navigation.
const validate = new Ajv2020({ allErrors: true, strict: false }).compile(
  schema,
);
export function definitionIssues(
  value: any,
): { nodeId?: string; message: string; path: string }[] {
  if (validate(value)) return [];
  return (validate.errors || []).map((e) => {
    const index = e.instancePath.match(/^\/nodes\/(\d+)/)?.[1];
    return {
      nodeId:
        index === undefined ? undefined : value?.nodes?.[Number(index)]?.id,
      path: e.instancePath,
      message: `${e.instancePath || "/"} ${e.message} ${JSON.stringify(e.params)}`,
    };
  });
}
export function validateDefinition(value: unknown) {
  return definitionIssues(value)
    .map((e) => e.message)
    .join("\n");
}
