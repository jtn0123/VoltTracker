/**
 * VoltTracker - Vendor Dependencies
 *
 * Imports critical CDN dependencies from npm packages so Vite can bundle them.
 * This eliminates external CDN dependency for core functionality.
 * CDN links in HTML templates serve as fallback only (with onerror handlers).
 */

// Socket.IO client
import { io as socketIO } from 'socket.io-client';
(window as any).io = socketIO;

// Flatpickr date picker
import flatpickrLib from 'flatpickr';
(window as any).flatpickr = flatpickrLib;

// Import flatpickr CSS (bundled by Vite)
import 'flatpickr/dist/flatpickr.min.css';
import 'flatpickr/dist/themes/dark.css';

// Chart.js — loaded on demand via dynamic import in charts.ts
// We re-export for convenience but don't eagerly load
export { Chart } from 'chart.js/auto';

// Leaflet — loaded on demand for map page
// Not imported here since it's only used on map.html
