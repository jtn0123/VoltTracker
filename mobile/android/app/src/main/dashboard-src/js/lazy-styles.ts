/** Loads a lazy stylesheet and recreates a failed link once before degrading gracefully. */
export function loadStylesheetWithRetry(
  href: string,
  attempts = 2,
  targetDocument: Document = document,
): Promise<void> {
  const maxAttempts = Math.max(1, attempts);
  let attempt = 0;

  return new Promise<void>((resolve) => {
    const start = () => {
      attempt += 1;
      const existing = targetDocument.querySelector<HTMLLinkElement>(`link[href="${href}"]`);
      if (existing?.dataset.voltLoaded === "true") {
        resolve();
        return;
      }
      if (existing?.sheet) {
        existing.dataset.voltLoaded = "true";
        resolve();
        return;
      }
      const link = existing || targetDocument.createElement("link");
      link.rel = "stylesheet";
      link.href = href;
      link.addEventListener(
        "load",
        () => {
          link.dataset.voltLoaded = "true";
          resolve();
        },
        { once: true },
      );
      link.addEventListener(
        "error",
        () => {
          link.remove();
          if (attempt < maxAttempts) queueMicrotask(start);
          else resolve();
        },
        { once: true },
      );
      if (!existing) targetDocument.head.appendChild(link);
    };

    start();
  });
}
