// Production bundler for the dashboard WebView TypeScript source.
//
// The editable source lives in app/src/main/dashboard-src/js/ (alongside the HTML
// partials). This builds it into app/src/main/assets/dashboard/js/ — the shipped
// location, which is gitignored (a build artifact, like a compiled binary).
//
// Output is a classic IIFE (NOT an ES module): the dashboard is served from
// file:///android_asset/ on-device, where `<script type=module>` is fetched with
// CORS semantics file:// can't satisfy, so module output would silently never run.
// A bundled IIFE loads identically from file:// and http:// — see
// docs/dashboard-script-contract.md.
//
// - The eager scripts (loaded up front, in dependency order by index.html) bundle
//   into a single app.js.
// - The lazy chunks (dtc-lookup / dtc-causes / demo-data, injected on demand by
//   core.ts) keep their emitted filenames so the existing loadDashboardScript() paths
//   resolve unchanged.
import { build } from "esbuild";
import { existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(HERE, "../app/src/main/dashboard-src/js");
const OUT = resolve(HERE, "../app/src/main/assets/dashboard/js");

// Clean the output dir so stale artifacts (e.g. an old build's *.map files) never
// linger into the packaged APK. Only this build writes here — it's gitignored.
mkdirSync(OUT, { recursive: true });
for (const f of readdirSync(OUT)) {
  if (f.endsWith(".js") || f.endsWith(".js.map")) {
    rmSync(resolve(OUT, f));
  }
}

// Keep this order in sync with index.template.html (and script-order.test.js).
const EAGER = [
  "core",
  "panels",
  "map",
  "scrubber",
  "drive",
  "telemetry",
  "actions",
  "troubleshooter",
  "connection-status",
  "connection-tools",
];

const LAZY = ["dtc-lookup", "dtc-causes", "demo-data"];

function sourceFor(name) {
  const file = `${SRC}/${name}.ts`;
  if (existsSync(file)) return file;
  throw new Error(`Missing dashboard TypeScript source for ${name}`);
}

const shared = {
  bundle: true,
  format: "iife",
  minify: true,
  // No source maps shipped for now: the editable source is in dashboard-src/js/ and
  // is what the dev/test flows use, so maps would only help on-device debugging while
  // adding ~2x the asset weight to the APK. Can be re-enabled (with a packaging exclude
  // for *.map) if on-device minified stack traces become a pain point.
  sourcemap: false,
  target: "es2022",
  legalComments: "none",
  logLevel: "info",
};

// Eager bundle: a synthetic entry that imports each IIFE for its side effects, in
// order. esbuild preserves side-effect import order, so runtime behaviour matches
// the old ordered <script> tags exactly.
await build({
  ...shared,
  stdin: {
    contents: EAGER.map((n) => `import "${sourceFor(n)}";`).join("\n"),
    resolveDir: SRC,
    sourcefile: "_eager-entry.js",
  },
  outfile: `${OUT}/app.js`,
});

// Lazy chunks: built individually, same filenames as before.
for (const name of LAZY) {
  await build({
    ...shared,
    entryPoints: [sourceFor(name)],
    outfile: `${OUT}/${name}.js`,
  });
}
