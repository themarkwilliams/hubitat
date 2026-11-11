# eventghost

Experiments from May 2025 towards making xps (Mark's Windows PC) usable as a trigger in Hubitat via
[EventGhost](https://eventghost.github.io/). They only got as far as reading disk details and SMART data:

- `get_info.py`: disk usage (psutil) and drive temperatures (`smartctl -A`)
- `get_info2.py`: `smartctl -x` output, re-launching itself as admin (pyuac)
- `.cmd\test.cmd`: runs `get_info2.py` from a local `.venv` (not kept; needs `psutil`, `pySMART`, `pyuac` and
  smartmontools on PATH)

Nothing is wired into EventGhost or Hubitat. Moved here from `C:\OneDrive\Code\eventghost` (homelab#189).
