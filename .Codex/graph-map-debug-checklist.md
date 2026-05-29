# Graph and Map Debug Checklist

Scope: dashboard graph, route, and map-like UI surfaces in the current Android
WebView dashboard. Keep interactive DOM/SVG renderers unless a surface is truly
too dense or too hot for DOM updates.

## Rendering Rule

- [ ] Keep Map details/scrubber charts as DOM/SVG so tracks can stay dynamic,
      selectable, inspectable, and easy to style.
- [ ] Keep Insights scatter as DOM/SVG unless real data proves it has too many
      points for smooth WebView rendering.
- [x] Keep Trips route previews as lightweight DOM/SVG thumbnails, not full
      Leaflet maps per row.
- [ ] Use canvas only for high-frequency live traces where repainting many DOM
      nodes every sample would be wasteful.

## Drive Tab

- [ ] Verify `Power last 60s` renders again with demo data and live samples.
- [ ] Verify `SOC this session` renders again with demo data and live samples.
- [ ] Confirm empty states do not look like broken text jammed into the chart.
- [ ] Confirm optional live cells collapsing does not collapse the chart card
      or hide a valid chart container.
- [ ] Add/adjust dashboard tests for power/SOC chart SVG output and empty-state
      text.

## Map Tab

- [ ] Verify Tiles defaults on and the map loads a real basemap in local
      preview.
- [ ] Verify tapping Tiles off removes only remote basemap tiles, not route,
      stops, efficiency, or scrubber data.
- [ ] Verify `Details` expands the scrubber stack and renders speed, elevation,
      battery, and efficiency tracks when each source exists.
- [ ] Verify Details can be toggled before and after switching map layers.
- [ ] Verify route tap/click updates the scrubber cursor and marker.
- [ ] Confirm Stop layer hit targets and count are reliable.

## Trips Tab

- [x] Optimize real trip rows so route-bearing trips have a compact visual route
      affordance instead of only a text `route` badge.
- [x] Keep Trips route previews cheap: no per-row Leaflet instances.
- [x] Make tapping a route-bearing trip select a Trips preview, with a map
      action for the full Leaflet route.
- [x] Add/adjust dashboard tests for trip row route affordance and the selected
      Trips preview.

## Insights Tab

- [ ] Verify efficiency scatter renders with sample/demo routes after map data
      loads.
- [ ] Confirm chart dimensions are non-zero after tab switches and resize.
- [ ] Confirm the scatter hides only when there truly are not enough efficiency
      points.

## Validation

- [ ] Browser preview screenshot: Drive tab with demo/live content.
- [ ] Browser preview screenshot: Map tab with Tiles on and Details expanded.
- [x] Browser preview screenshot: Trips tab route rows.
- [x] Run `./gradlew --no-daemon generateDashboardHtml`.
- [ ] Run `./gradlew --no-daemon :app:spotlessCheck dashboardLint dashboardTest verifyGeneratedDashboardClean`.
- [ ] Run `git diff --check`.
