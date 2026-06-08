import { describe, expect, it } from 'vitest';

import { loadDashboard } from './setup/load-dashboard.js';

describe('legacy WebView compatibility', () => {
  it('boots when Android WebView lacks Element.replaceChildren', async () => {
    const descriptor = Object.getOwnPropertyDescriptor(Element.prototype, 'replaceChildren');
    delete Element.prototype.replaceChildren;

    try {
      expect(Element.prototype.replaceChildren).toBeUndefined();
      await loadDashboard();

      const node = document.createElement('div');
      node.appendChild(document.createElement('b'));
      node.replaceChildren('ready');

      expect(node.textContent).toBe('ready');
      expect(node.childNodes).toHaveLength(1);
    } finally {
      if (descriptor) {
        Object.defineProperty(Element.prototype, 'replaceChildren', descriptor);
      } else {
        delete Element.prototype.replaceChildren;
      }
    }
  });
});
