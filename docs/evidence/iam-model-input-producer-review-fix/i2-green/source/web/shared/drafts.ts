// A browser draft is only an unsubmitted edit. A changed server revision must require a fresh edit.
export function matchingDraft(draft: any, revision: number) {
  return draft?.draftRev === revision ? draft.value : null;
}
export function readDraft(key?: string, revision?: number) {
  try {
    return key
      ? matchingDraft(
          JSON.parse(localStorage.getItem("draft:" + key) || "null"),
          revision!,
        )
      : null;
  } catch {
    return null;
  }
}
export function writeDraft(key: string, revision: number, value: unknown) {
  localStorage.setItem(
    "draft:" + key,
    JSON.stringify({
      draftRev: revision,
      savedAt: new Date().toISOString(),
      value,
    }),
  );
}
