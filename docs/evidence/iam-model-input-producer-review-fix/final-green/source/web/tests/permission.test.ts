import { it, expect } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useAuth } from "../shared/api";
it("presentation permissions default deny without identity and required roles", () => {
  setActivePinia(createPinia());
  const auth = useAuth();
  expect(auth.can("edit")).toBe(false);
  auth.token = "t";
  auth.roles = ["READER"];
  expect(auth.can("edit")).toBe(false);
  auth.roles = ["APPROVER"];
  expect(auth.can("approve")).toBe(true);
  expect(auth.can("edit")).toBe(false);
  auth.roles = ["ADMIN"];
  expect(auth.can("edit")).toBe(true);
});
