type PageDragState = {
  pointerId: number;
  startX: number;
  startY: number;
  lastY: number;
  active: boolean;
};

const pageDragScrollBlockSelector = [
  "a",
  "button",
  "input",
  "select",
  "textarea",
  "summary",
  "[role='button']",
  "[data-nav]",
  "[data-action]",
  "[data-signal-stage]",
  "[data-signal-export]",
  "[data-signal-delete]",
  "[data-map-layer]",
  "[data-real-trip-id]",
  "[data-trip-map]",
  ".bottom-nav",
  ".map-card",
  ".map-frame",
  ".map-drive-chips",
  ".scrub-chart",
  ".scrub-track",
  ".route-box"
].join(",");

let pageDragScroll: PageDragState | null = null;

function canStartPageDragScroll(VD: VoltDashboard, event: PointerEvent) {
  if (event.button !== 0) return false;
  if (event.pointerType && event.pointerType !== "mouse") return false;
  const target = event.target as Element | null;
  if (target && target.closest(pageDragScrollBlockSelector)) return false;
  return typeof VD.canScrollApp === "function"
    ? VD.canScrollApp()
    : document.documentElement.scrollHeight > window.innerHeight + 2;
}

export function bindPageDragScroll(
  VD: VoltDashboard,
  opts: AddEventListenerOptions,
) {
  document.addEventListener("pointerdown", (event) => {
    if (!canStartPageDragScroll(VD, event)) return;
    pageDragScroll = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastY: event.clientY,
      active: false
    };
  }, opts);

  document.addEventListener("pointermove", (event) => {
    if (!pageDragScroll || event.pointerId !== pageDragScroll.pointerId) return;
    const totalX = Math.abs(event.clientX - pageDragScroll.startX);
    const totalY = Math.abs(event.clientY - pageDragScroll.startY);
    if (!pageDragScroll.active) {
      if (totalY < 8 || totalY <= totalX) return;
      pageDragScroll.active = true;
      document.body.classList.add("is-page-dragging");
    }
    event.preventDefault();
    if (typeof VD.scrollAppBy === "function") {
      VD.scrollAppBy(pageDragScroll.lastY - event.clientY);
    } else {
      window.scrollBy({ top: pageDragScroll.lastY - event.clientY, left: 0, behavior: "auto" });
    }
    pageDragScroll.lastY = event.clientY;
  }, { ...opts, passive: false });

  const stopDragScroll = () => {
    pageDragScroll = null;
    document.body.classList.remove("is-page-dragging");
  };
  document.addEventListener("pointerup", stopDragScroll, opts);
  document.addEventListener("pointercancel", stopDragScroll, opts);
  window.addEventListener("blur", stopDragScroll, opts);
}
