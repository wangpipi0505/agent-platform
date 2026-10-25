import Ajv2020 from "ajv/dist/2020";
export function inputFields(declaration: any): any[] {
  if (Array.isArray(declaration))
    return declaration.map((f) => ({
      ...f,
      type: String(f.type || "STRING").toLowerCase(),
      description: f.desc || f.description || "",
    }));
  if (declaration?.properties)
    return Object.entries(declaration.properties).map(
      ([name, s]: [string, any]) => ({
        ...s,
        name,
        type: s.type || "string",
        required: (declaration.required || []).includes(name),
      }),
    );
  return [];
}
export function buildDeclaredInputs(
  declaration: any,
  raw: Record<string, any>,
) {
  const fields = inputFields(declaration);
  const value: Record<string, any> = {};
  for (const field of fields) {
    let v = raw[field.name];
    if (v === undefined) v = field.default;
    if (v === undefined && field.type !== "null") continue;
    if (["object", "array"].includes(field.type) && typeof v === "string") {
      try {
        v = JSON.parse(v);
      } catch {
        throw new Error((field.description || field.name) + " 必须是有效JSON");
      }
    }
    if (field.type === "null") v = null;
    value[field.name] = v;
  }
  const schema = Array.isArray(declaration)
    ? {
        type: "object",
        properties: Object.fromEntries(
          fields.map(({ name, required, desc, description, ...s }) => [
            name,
            s,
          ]),
        ),
        required: fields.filter((f) => f.required).map((f) => f.name),
      }
    : declaration;
  const validate = new Ajv2020({ allErrors: true, strict: false }).compile(
    schema,
  );
  if (!validate(value))
    throw new Error(
      (validate.errors || [])
        .map(
          (e) =>
            (e.instancePath || "/") +
            " " +
            e.message +
            " " +
            JSON.stringify(e.params),
        )
        .join("\n"),
    );
  return value;
}
