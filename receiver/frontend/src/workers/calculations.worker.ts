/**
 * VoltTracker - Calculations Web Worker
 * Offloads heavy chart data processing from the main thread.
 *
 * Message protocol:
 *   Request  → { id: string, type: WorkerTaskType, payload: ... }
 *   Response → { id: string, type: WorkerTaskType, result: ... } | { id: string, error: string }
 */

// ── Types ────────────────────────────────────────────────────────────────────

export type WorkerTaskType = 'aggregate' | 'average' | 'trend' | 'downsample';

export interface WorkerRequest {
  id: string;
  type: WorkerTaskType;
  payload: unknown;
}

export interface WorkerResponse {
  id: string;
  type: WorkerTaskType;
  result?: unknown;
  error?: string;
}

interface DataPoint {
  timestamp: string;
  value: number | null;
  [key: string]: unknown;
}

interface AggregatePayload {
  data: DataPoint[];
  field: string;
  bucketMs: number; // e.g. 86400000 for daily
  method: 'sum' | 'avg' | 'min' | 'max' | 'count';
}

interface AveragePayload {
  data: DataPoint[];
  field: string;
  windowSize: number;
}

interface TrendPayload {
  data: DataPoint[];
  field: string;
}

interface DownsamplePayload {
  data: DataPoint[];
  field: string;
  targetPoints: number;
}

// ── Handlers ─────────────────────────────────────────────────────────────────

function aggregate(p: AggregatePayload): { timestamp: number; value: number }[] {
  const buckets = new Map<number, number[]>();
  for (const d of p.data) {
    const v = (d as any)[p.field];
    if (v == null || isNaN(v)) continue;
    const key = Math.floor(new Date(d.timestamp).getTime() / p.bucketMs) * p.bucketMs;
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key)!.push(v);
  }
  const results: { timestamp: number; value: number }[] = [];
  for (const [ts, vals] of buckets) {
    let value: number;
    switch (p.method) {
      case 'sum':
        value = vals.reduce((a, b) => a + b, 0);
        break;
      case 'avg':
        value = vals.reduce((a, b) => a + b, 0) / vals.length;
        break;
      case 'min':
        value = Math.min(...vals);
        break;
      case 'max':
        value = Math.max(...vals);
        break;
      case 'count':
        value = vals.length;
        break;
    }
    results.push({ timestamp: ts, value });
  }
  return results.sort((a, b) => a.timestamp - b.timestamp);
}

function movingAverage(p: AveragePayload): { timestamp: string; value: number }[] {
  const values: { timestamp: string; value: number }[] = [];
  for (const d of p.data) {
    const v = (d as any)[p.field];
    if (v != null && !isNaN(v)) values.push({ timestamp: d.timestamp, value: v });
  }
  const result: { timestamp: string; value: number }[] = [];
  for (let i = 0; i < values.length; i++) {
    const start = Math.max(0, i - p.windowSize + 1);
    const window = values.slice(start, i + 1);
    const avg = window.reduce((s, v) => s + v.value, 0) / window.length;
    result.push({ timestamp: values[i].timestamp, value: avg });
  }
  return result;
}

function linearTrend(p: TrendPayload): {
  slope: number;
  intercept: number;
  r2: number;
  trendLine: { timestamp: string; value: number }[];
} {
  const pts: { x: number; y: number; ts: string }[] = [];
  const t0 = p.data.length > 0 ? new Date(p.data[0].timestamp).getTime() : 0;
  for (const d of p.data) {
    const v = (d as any)[p.field];
    if (v != null && !isNaN(v))
      pts.push({ x: (new Date(d.timestamp).getTime() - t0) / 86400000, y: v, ts: d.timestamp });
  }
  if (pts.length < 2) return { slope: 0, intercept: 0, r2: 0, trendLine: [] };

  const n = pts.length;
  const sx = pts.reduce((s, p) => s + p.x, 0);
  const sy = pts.reduce((s, p) => s + p.y, 0);
  const sxy = pts.reduce((s, p) => s + p.x * p.y, 0);
  const sxx = pts.reduce((s, p) => s + p.x * p.x, 0);
  const slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
  const intercept = (sy - slope * sx) / n;

  const mean = sy / n;
  const ssTot = pts.reduce((s, p) => s + (p.y - mean) ** 2, 0);
  const ssRes = pts.reduce((s, p) => s + (p.y - (slope * p.x + intercept)) ** 2, 0);
  const r2 = ssTot > 0 ? 1 - ssRes / ssTot : 0;

  const trendLine = pts.map((p) => ({ timestamp: p.ts, value: slope * p.x + intercept }));
  return { slope, intercept, r2, trendLine };
}

function downsample(p: DownsamplePayload): DataPoint[] {
  const values: DataPoint[] = p.data.filter((d) => {
    const v = (d as any)[p.field];
    return v != null && !isNaN(v);
  });
  if (values.length <= p.targetPoints) return values;

  // Largest-triangle-three-buckets (LTTB)
  const sampled: DataPoint[] = [values[0]];
  const bucketSize = (values.length - 2) / (p.targetPoints - 2);

  let prevIndex = 0;
  for (let i = 1; i < p.targetPoints - 1; i++) {
    const bucketStart = Math.floor((i - 1) * bucketSize) + 1;
    const bucketEnd = Math.min(Math.floor(i * bucketSize) + 1, values.length - 1);
    const nextBucketStart = Math.min(Math.floor(i * bucketSize) + 1, values.length - 1);
    const nextBucketEnd = Math.min(Math.floor((i + 1) * bucketSize) + 1, values.length);

    // Average of next bucket
    let avgX = 0,
      avgY = 0,
      count = 0;
    for (let j = nextBucketStart; j < nextBucketEnd; j++) {
      avgX += j;
      avgY += (values[j] as any)[p.field];
      count++;
    }
    avgX /= count;
    avgY /= count;

    // Find point in current bucket with max triangle area
    let maxArea = -1,
      maxIndex = bucketStart;
    const prevY = (values[prevIndex] as any)[p.field];
    for (let j = bucketStart; j < bucketEnd; j++) {
      const area = Math.abs(
        (prevIndex - avgX) * ((values[j] as any)[p.field] - prevY) - (prevIndex - j) * (avgY - prevY),
      );
      if (area > maxArea) {
        maxArea = area;
        maxIndex = j;
      }
    }
    sampled.push(values[maxIndex]);
    prevIndex = maxIndex;
  }
  sampled.push(values[values.length - 1]);
  return sampled;
}

// ── Message handler ──────────────────────────────────────────────────────────

self.onmessage = (e: MessageEvent<WorkerRequest>) => {
  const { id, type, payload } = e.data;
  try {
    let result: unknown;
    switch (type) {
      case 'aggregate':
        result = aggregate(payload as AggregatePayload);
        break;
      case 'average':
        result = movingAverage(payload as AveragePayload);
        break;
      case 'trend':
        result = linearTrend(payload as TrendPayload);
        break;
      case 'downsample':
        result = downsample(payload as DownsamplePayload);
        break;
      default:
        throw new Error(`Unknown task type: ${type}`);
    }
    self.postMessage({ id, type, result } as WorkerResponse);
  } catch (err) {
    self.postMessage({ id, type, error: (err as Error).message } as WorkerResponse);
  }
};
