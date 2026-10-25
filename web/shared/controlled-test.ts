// Reservation is not execution: pending approval can already have a call ID.
export function completedControlledCall(state: any) {
  return state.status === "SUCCEEDED" && state.controlledCallId
    ? state.controlledCallId
    : undefined;
}
