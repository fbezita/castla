import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

export interface FingerprintSource {
  path: string;
  content: string | Uint8Array;
}

const SOURCE_PATHS = [
  "src",
  "public",
  "index.html",
  "package.json",
  "pnpm-lock.yaml",
  "pnpm-workspace.yaml",
  "svelte.config.js",
  "tsconfig.json",
  "tsconfig.node.json",
  "vite.config.ts",
  "buildFingerprint.ts",
] as const;

export function fingerprintSources(sources: readonly FingerprintSource[]): string {
  const hash = createHash("sha256");
  for (const source of [...sources].sort((left, right) => left.path.localeCompare(right.path))) {
    hash.update(source.path.replace(/\\/g, "/"));
    hash.update("\0");
    hash.update(source.content);
    hash.update("\0");
  }
  return hash.digest("hex").slice(0, 12);
}

export function resolveFrontendSourceFingerprint(frontendRoot: string): string {
  const sources: FingerprintSource[] = [];

  const collect = (absolutePath: string): void => {
    const relativePath = relative(frontendRoot, absolutePath).replace(/\\/g, "/");
    if (relativePath === "src/test" || relativePath.startsWith("src/test/")) return;

    for (const entry of readdirSync(absolutePath, { withFileTypes: true })) {
      const childPath = join(absolutePath, entry.name);
      if (entry.isDirectory()) {
        collect(childPath);
      } else if (entry.isFile()) {
        sources.push({
          path: relative(frontendRoot, childPath).replace(/\\/g, "/"),
          content: readFileSync(childPath),
        });
      }
    }
  };

  for (const sourcePath of SOURCE_PATHS) {
    const absolutePath = join(frontendRoot, sourcePath);
    if (!existsSync(absolutePath)) continue;
    if (statSync(absolutePath).isDirectory()) {
      collect(absolutePath);
    } else {
      sources.push({ path: sourcePath, content: readFileSync(absolutePath) });
    }
  }

  return fingerprintSources(sources);
}
