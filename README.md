# hud-navi

Lightweight HUD (Head-Up Display) navigation app for Android. Uses Canvas 2D rendering to project road networks over a windshield for nighttime driving.

## Features

- Real-time GPS positioning with road snapping (HMM map matching)
- Dynamic zoom based on vehicle speed
- Mirror mode for windshield HUD projection
- Road network fetched from OpenStreetMap via Overpass API
- Speed-colored vehicle marker (green → yellow → red)
- Top fade gradient for clean speedometer area
- Disk-cached road data for instant loading
- Smooth snap/unsnap blending to eliminate map jitter

## Road Data Attribution

Road data is provided by [OpenStreetMap](https://www.openstreetmap.org), licensed under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/).

**© OpenStreetMap contributors**

## License

This project is licensed under the GNU General Public License v3.0.
