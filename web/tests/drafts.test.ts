import { it, expect } from "vitest";
import { matchingDraft } from "../shared/drafts";
it("never applies an unsubmitted draft against a changed server revision", () => {
  expect(matchingDraft({ draftRev: 2, value: { a: 1 } }, 3)).toBeNull();
  expect(matchingDraft({ draftRev: 2, value: { a: 1 } }, 2)).toEqual({ a: 1 });
});
