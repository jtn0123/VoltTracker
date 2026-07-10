import { describe, expect, it } from 'vitest';

import {
  numberSeriesSignature,
  staticRouteDrawSignature,
  storageRollupSignature,
  tripGeometrySignature,
} from '../app/src/main/dashboard-src/js/render-signatures.ts';

describe('render signatures', () => {
  it('detects middle-sample changes even when array endpoints match', () => {
    expect(numberSeriesSignature([1, 2, 3], 320)).not.toBe(
      numberSeriesSignature([1, 9, 3], 320),
    );
  });

  it('includes units in the static map draw signature', () => {
    expect(staticRouteDrawSignature('r1', 'eff', true, 2, 'imperial')).not.toBe(
      staticRouteDrawSignature('r1', 'eff', true, 2, 'metric'),
    );
  });

  it('detects route metadata and geometry revisions', () => {
    const baseline = [{ id: 'r1', pointCount: 4, distanceMeters: 100, label: 'Home', favorite: false }];
    expect(tripGeometrySignature(baseline)).not.toBe(
      tripGeometrySignature([{ ...baseline[0], distanceMeters: 150 }]),
    );
    expect(tripGeometrySignature(baseline)).not.toBe(
      tripGeometrySignature([{ ...baseline[0], label: 'Work', favorite: true }]),
    );
  });

  it('ignores live sample churn but detects route labels and favorites', () => {
    const baseline = {
      sessionCount: 1,
      sampleCount: 10,
      locationSampleCount: 5,
      tripSegmentCount: 1,
      recentRoutes: [{ session: { id: 'r1', label: 'Home', favorite: false }, pointCount: 4 }],
    };
    expect(storageRollupSignature(baseline)).toBe(
      storageRollupSignature({ ...baseline, sampleCount: 11, locationSampleCount: 6 }),
    );
    expect(storageRollupSignature(baseline)).not.toBe(
      storageRollupSignature({
        ...baseline,
        recentRoutes: [{ session: { id: 'r1', label: 'Work', favorite: true }, pointCount: 4 }],
      }),
    );
  });
});
