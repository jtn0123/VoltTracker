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
// - The lazy chunks (map / troubleshooter / dtc data / demo data / secondary action groups,
//   injected on demand by core.ts/actions.ts) keep their emitted filenames so the existing loadDashboardScript() paths
//   resolve unchanged.
import { build } from "esbuild";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(HERE, "../app/src/main/dashboard-src/js");
const OUT = resolve(HERE, "../app/src/main/assets/dashboard/js");
const LEAFLET_SRC = resolve(HERE, "node_modules/leaflet/dist");
const LEAFLET_OUT = resolve(HERE, "../app/src/main/assets/dashboard/lib/leaflet");
const LEAFLET_ASSETS = [
  "leaflet.css",
  "leaflet.js",
  "images/layers.png",
  "images/layers-2x.png",
  "images/marker-icon.png",
  "images/marker-icon-2x.png",
  "images/marker-shadow.png",
];

function copyAssetTree(srcRoot, outRoot, files) {
  for (const file of files) {
    const src = resolve(srcRoot, file);
    if (!existsSync(src)) {
      throw new Error(`Missing managed asset ${src}`);
    }
    const out = resolve(outRoot, file);
    mkdirSync(dirname(out), { recursive: true });
    if (file.endsWith(".css") || file.endsWith(".js")) {
      writeFileSync(out, readFileSync(src, "utf8").replace(/\r\n/g, "\n"));
    } else {
      copyFileSync(src, out);
    }
  }
}

// Clean the output dir so stale artifacts (e.g. an old build's *.map files) never
// linger into the packaged APK. Only this build writes here — it's gitignored.
mkdirSync(OUT, { recursive: true });
for (const f of readdirSync(OUT)) {
  if (f.endsWith(".js") || f.endsWith(".js.map")) {
    rmSync(resolve(OUT, f));
  }
}

rmSync(LEAFLET_OUT, { recursive: true, force: true });
copyAssetTree(LEAFLET_SRC, LEAFLET_OUT, LEAFLET_ASSETS);

// Keep this order in sync with index.template.html (and script-order.test.js).
// storage-status / signals-panel are the eager pieces split from the old panels.ts
// god-module (C2). insights-panel is lazy now: it owns trips/insights rollups and
// efficiency scatter work that are not needed for the first Drive/Status paint.
const EAGER = [
  "prefs",
  "core",
  "payload-validators",
  "storage-status",
  "signals-panel",
  "scrubber",
  "drive",
  "telemetry",
  "actions",
  "connection-status",
  "connection-tools",
];

const LAZY = [
  "insights-panel",
  "map",
  "troubleshooter",
  "dtc-lookup",
  "dtc-causes",
  "demo-data",
  "actions-storage",
  "actions-signals",
  "actions-demo",
];

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
  // Keep the shipped syntax compatible with the oldest WebView we exercise in
  // local/runtime validation. Android 9's WebView 66 cannot parse modern syntax
  // such as optional chaining, even though desktop Chromium and jsdom can.
  target: "chrome66",
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
