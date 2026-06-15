// focus-trap.ts — background inert/aria-hidden state preservation (CodeRabbit PR #211).
//
// The trap inerts + aria-hides its background nodes on activate and must RESTORE each node to
// exactly what it was before — not blindly clear it. A node that was already inert / aria-hidden
// before the trap touched it must stay that way after deactivate, or pre-existing accessibility
// state gets corrupted.
import { beforeEach, describe, expect, it } from 'vitest';

import { createFocusTrap } from '../app/src/main/dashboard-src/js/focus-trap.ts';

describe('focus-trap background inert restoration', () => {
  let root;
  let pristine;
  let alreadyInert;
  let alreadyHidden;

  beforeEach(() => {
    document.body.innerHTML = '';
    root = document.createElement('div');
    root.innerHTML = '<button id="inside">ok</button>';

    pristine = document.createElement('div'); // no inert, no aria-hidden before activation
    alreadyInert = document.createElement('div');
    alreadyInert.inert = true;
    alreadyHidden = document.createElement('div');
    alreadyHidden.setAttribute('aria-hidden', 'true');

    document.body.append(root, pristine, alreadyInert, alreadyHidden);
  });

  it('clears state it added but restores state that existed before', () => {
    const trap = createFocusTrap(root, {
      backgroundNodes: () => [pristine, alreadyInert, alreadyHidden]
    });

    trap.activate();
    // While active, every background node is inert + aria-hidden.
    expect(pristine.inert).toBe(true);
    expect(pristine.getAttribute('aria-hidden')).toBe('true');
    expect(alreadyInert.inert).toBe(true);
    expect(alreadyHidden.getAttribute('aria-hidden')).toBe('true');

    trap.deactivate();

    // The pristine node is fully cleared (the trap added both flags).
    expect(pristine.inert).toBe(false);
    expect(pristine.hasAttribute('aria-hidden')).toBe(false);

    // The pre-inert node keeps its inert (the trap must not unset what it didn't set).
    expect(alreadyInert.inert).toBe(true);

    // The pre-aria-hidden node keeps its original aria-hidden value.
    expect(alreadyHidden.getAttribute('aria-hidden')).toBe('true');
  });
});
