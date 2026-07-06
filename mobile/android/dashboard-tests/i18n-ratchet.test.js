import { beforeEach, describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { loadDashboard } from "./setup/load-dashboard.js";

const SRC = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../app/src/main/dashboard-src/js",
);

// X5 copy-system ratchet. The i18n catalog (i18n.ts EN) is the destination for
// all user-facing copy; today it holds a representative slice. This ratchet
// makes migration one-directional: the catalog may only grow, and every key in
// it must have a real t() call site (no dead keys accumulating). When you add
// keys, RAISE the floor to the new count.
const CATALOG_FLOOR = 11;

function readCatalogKeys() {
  const source = fs.readFileSync(path.join(SRC, "i18n.ts"), "utf8");
  const start = source.indexOf("export const EN = {");
  const end = source.indexOf("} as const;", start);
  expect(start, "i18n.ts should declare `export const EN = {`").toBeGreaterThan(
    -1,
  );
  const enBlock = source.slice(start, end);
  return [...enBlock.matchAll(/"([a-z0-9][a-zA-Z0-9.]+)":\s*"/g)].map(
    (m) => m[1],
  );
}

describe("i18n catalog ratchet", () => {
  it(`only grows — floor is ${CATALOG_FLOOR} keys (raise it when you add keys)`, () => {
    const keys = readCatalogKeys();
    expect(keys.length).toBeGreaterThanOrEqual(CATALOG_FLOOR);
    // No duplicate keys (a paste error would silently shadow one).
    expect(new Set(keys).size).toBe(keys.length);
  });

  it("every catalog key has a call site outside i18n.ts", () => {
    const keys = readCatalogKeys();
    const corpus = fs
      .readdirSync(SRC)
      .filter((f) => f.endsWith(".ts") && f !== "i18n.ts")
      .map((f) => fs.readFileSync(path.join(SRC, f), "utf8"))
      .join("\n");
    for (const key of keys) {
      expect(
        corpus.includes(`"${key}"`),
        `catalog key "${key}" has no call site`,
      ).toBe(true);
    }
  });
});

// Companion pin for the X5 Settings state-chip fix: row counts render compact
// ("5.7k rows") instead of overflowing the chip into "5738 r…".
describe("VoltDashboard.formatRowCount", () => {
  beforeEach(async () => {
    document.body.innerHTML = "";
    delete window.VoltDashboard;
    delete window.VoltTrackerNative;
    delete window.VoltTrackerAndroid;
    await loadDashboard();
  });

  it("compacts thousands and keeps the idle label at zero", () => {
    const f = window.VoltDashboard.formatRowCount;
    expect(f(0)).toBe("ready");
    expect(f(null)).toBe("ready");
    expect(f(512)).toBe("512 rows");
    expect(f(5738)).toBe("5.7k rows");
    expect(f(12480)).toBe("12k rows");
  });
});
