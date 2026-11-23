import { cp, mkdir, readdir } from "node:fs/promises";
import { join } from "node:path";

const root = new URL(".", import.meta.url);
const out = new URL("./dist/", import.meta.url);
await mkdir(out, { recursive: true });
const excluded = new Set(["dist", "package.json", "build.mjs", "vercel.json"]);
for (const name of await readdir(root)) {
  if (excluded.has(name)) continue;
  await cp(new URL("./" + name, import.meta.url), new URL("./" + name, out), { recursive: true });
}
console.log("DeepBass Web Studio static build complete.");
