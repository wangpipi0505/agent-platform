import { copyFile } from 'node:fs/promises';
// The published embed URL and standalone WebApp share one controlled deployment root.
await copyFile(new URL('../assistant.js/dist/assistant.js',import.meta.url),new URL('../webapp/dist/assistant.js',import.meta.url));
