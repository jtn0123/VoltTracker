// render-pass.ts — the dashboard's single render pass (Phase 2).
//
// THE PROBLEM THIS SOLVES
//
// Before this, every state mutation had to be followed by hand-calling the right subset of
// render functions. The same four-call cluster appeared verbatim in six places:
//
//     updateStorageUi(); renderRealV2Ui(); renderMapIfLoaded(); VD.renderInsightStats();
//
// Miss one and the UI goes stale. Call it twice and you get a double render — the flicker and
// flash-in/out class. Get the order wrong and you get a flash of the previous state. Nothing
// enforced the list, so it drifted every time a renderer was added.
//
// Here a renderer REGISTERS ONCE and the pass calls it. setState() marks the pass dirty and
// schedules a single frame; the frame runs every registered renderer in registration order.
// Callers no longer decide who repaints — they only change state.
//
// WHY EVERY RENDERER RUNS ON EVERY PASS
//
// The obvious optimisation is to let a renderer declare which state keys it depends on and
// only run it when one of those changed. That was tried and rejected: a renderer's real key
// set is transitive (it calls helpers, which call cross-chunk VD.* entry points, which read
// more state), so any hand-written key list is a LOWER BOUND. An incomplete list means a
// missed render — reintroducing the exact bug class this file exists to remove, in a form
// that is harder to spot than the old explicit call.
//
// Instead every renderer runs on every pass and early-outs on a cheap SIGNATURE.
// Correctness therefore does not depend on anyone's dependency analysis being complete; it
// depends only on the signature covering what the renderer draws, which is local, obvious,
// and testable. A signature that misses a field costs one stale repaint; a key list that
// misses a field costs a silent stale UI.
//
// A signature returns an ARRAY OF VALUES compared with Object.is, so the cheapest correct
// signature is usually the state slices themselves:
//
//     registerRenderer("storage:summary", updateStorageUi, () => [state.storage])
//
// That is sound because of Phase 1: every writer REPLACES state objects rather than
// mutating them (setState is the only writer, and architecture-ratchet.test.js pins
// aliasNestedWrites at 0). So an unchanged reference genuinely means unchanged content.
// If that invariant ever breaks, this memoisation breaks with it — which is why the ratchet
// guards it.
//
// Renderers without a signature run unconditionally — correct, just not free. Prefer adding
// a signature over adding a key filter.
//
// SCHEDULING
//
// core.ts owns the actual rAF (it holds `state.rafPending`, which is part of the ABI and is
// asserted by startup-budget.test.js / state-shape.test.js) and calls runRenderPass() inside
// the frame. This module stays free of state and DOM imports so it has no cycle with core.

/** Values compared with Object.is to decide whether a renderer needs to repaint. */
export type RenderSignature = () => readonly unknown[];

/** A registered renderer. `signature` is optional; absent means "always repaint". */
interface Renderer {
  readonly name: string;
  readonly run: () => void;
  readonly signature?: RenderSignature | undefined;
  last: readonly unknown[] | null;
}

function sameSignature(a: readonly unknown[] | null, b: readonly unknown[]): boolean {
  if (a === null || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    if (!Object.is(a[i], b[i])) return false;
  }
  return true;
}

const renderers: Renderer[] = [];
let passDepth = 0;
/** Set when a renderer writes state during a pass, so the pass runs again next frame. */
let rerunRequested = false;

/**
 * Register a renderer with the pass. Registration order is render order, so a renderer that
 * must observe another's DOM output registers after it.
 *
 * Re-registering the same name replaces the previous entry rather than adding a duplicate:
 * a lazy chunk can be evaluated more than once (the unit suite re-imports sources per test
 * while the window object persists), and a duplicate would repaint twice per frame.
 */
export function registerRenderer(name: string, run: () => void, signature?: RenderSignature): void {
  const entry: Renderer = { name, run, signature, last: null };
  const existing = renderers.findIndex((r) => r.name === name);
  if (existing >= 0) renderers[existing] = entry;
  else renderers.push(entry);
}

/** Drop a renderer. Used by teardown paths in the unit suite. */
export function unregisterRenderer(name: string): void {
  const index = renderers.findIndex((r) => r.name === name);
  if (index >= 0) renderers.splice(index, 1);
}

/** Registered renderer names, in render order. Exposed for tests and the ratchet. */
export function rendererNames(): string[] {
  return renderers.map((r) => r.name);
}

/** True while a pass is executing — core.ts uses this to defer instead of re-entering. */
export function isRendering(): boolean {
  return passDepth > 0;
}

/**
 * True if a renderer requested another pass while this one was running. core.ts clears it by
 * scheduling one more frame. Kept separate from isRendering() so the cascade is explicit and
 * boundable rather than an accidental synchronous loop.
 */
export function consumeRerunRequest(): boolean {
  const requested = rerunRequested;
  rerunRequested = false;
  return requested;
}

/** Called by core.ts when state changes during a pass. */
export function requestRerun(): void {
  rerunRequested = true;
}

/**
 * Run every registered renderer once, in registration order.
 *
 * A renderer that throws must not take the rest of the frame down with it — one broken panel
 * would otherwise blank every panel registered after it. The error is reported and the pass
 * continues, matching how the dashboard already isolates lazy-chunk failures.
 *
 * A renderer's signature is recomputed AFTER it runs, not before: renderers that derive
 * display state as they draw would otherwise stamp a signature that does not match what is
 * on screen, and the next real change would be skipped.
 */
export function runRenderPass(onError?: (name: string, error: unknown) => void): void {
  passDepth += 1;
  try {
    // Iterate a snapshot: a renderer may register another renderer (a lazy panel hydrating
    // mid-pass), and that newcomer should join the NEXT pass rather than mutate this loop.
    for (const renderer of renderers.slice()) {
      try {
        if (renderer.signature) {
          if (sameSignature(renderer.last, renderer.signature())) continue;
          renderer.run();
          renderer.last = renderer.signature();
        } else {
          renderer.run();
        }
      } catch (error) {
        renderer.last = null; // never cache a signature from a failed paint
        if (onError) onError(renderer.name, error);
      }
    }
  } finally {
    passDepth -= 1;
  }
}

/**
 * Forget every cached signature so the next pass repaints unconditionally.
 *
 * Needed whenever the DOM is replaced underneath the pass (demo start/stop swapping the
 * payloads, a restore rebuilding the panels): the state signature can be identical while the
 * nodes on screen are not, and without this the pass would correctly skip a repaint that the
 * user needs.
 */
export function invalidateRenderCache(): void {
  for (const renderer of renderers) renderer.last = null;
}

/** Drop all renderers. Test-lifecycle only. */
export function resetRenderPass(): void {
  renderers.length = 0;
  passDepth = 0;
  rerunRequested = false;
}
