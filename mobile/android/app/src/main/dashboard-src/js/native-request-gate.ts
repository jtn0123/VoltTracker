export const NATIVE_REQUEST_TIMEOUT_MS = 5_000;

export type NativeRequestGate = {
  readonly pending: boolean;
  begin(): void;
  complete(): void;
};

/**
 * Tracks an asynchronous Android-to-WebView read until its callback arrives. A callback can be
 * lost when the WebView is recreated or evaluateJavascript races teardown; the timeout releases
 * the gate so a later view render can request fresh data instead of remaining stuck forever.
 */
export function createNativeRequestGate(
  onTimeout: () => void,
  timeoutMs = NATIVE_REQUEST_TIMEOUT_MS,
): NativeRequestGate {
  let pending = false;
  let timer: ReturnType<typeof setTimeout> | null = null;

  const complete = () => {
    pending = false;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  return {
    get pending() {
      return pending;
    },
    begin() {
      complete();
      pending = true;
      timer = setTimeout(() => {
        timer = null;
        pending = false;
        onTimeout();
      }, Math.max(1, timeoutMs));
    },
    complete,
  };
}
