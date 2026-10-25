// Red-stage scaffold: the first bounded tests must expose missing cleanup and ownership behavior.
export async function cleanupAlgorithmRun({ failure }) { if (failure) throw failure; }
export function assertProcessOwnership() {}
