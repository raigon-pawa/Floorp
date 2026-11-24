# Generates the archwave Floorp app icon: python3 gen.py out.svg [small]
import math, sys
out = sys.argv[1]
small = len(sys.argv) > 2
W = 512; cx, cy, r = 256, 256, 168
def wave(y0, amp, k, ph, drift):
    pts = []
    for i in range(0, 121):
        x = 40 + i * (432 / 120)
        t = (x - 40) / 432
        y = (y0 + amp * math.sin(k * t * 2 * math.pi + ph)
             + amp * 0.45 * math.sin(2.3 * k * t * 2 * math.pi + ph * 1.7)
             + drift * math.sin(t * math.pi))
        pts.append(f"{x:.1f},{y:.1f}")
    return "M" + " L".join(pts)
lines = []
n = 5 if small else 15
for i in range(n):
    y0 = cy - r + 18 + i * (2 * r - 36) / (n - 1)
    mid = 1 - abs(i - (n - 1) / 2) / ((n - 1) / 2)
    amp = 6 + 16 * mid
    op = 0.35 + 0.6 * mid
    w = (14 if i == 2 else 11) if small else (3.2 if i % 3 == 1 else 2.2)
    op = max(op, 0.75) if small else op
    lines.append(f'<path d="{wave(y0, amp, 1.15, i * 0.55, -10 * mid)}" stroke-width="{w}" stroke-opacity="{op:.2f}"/>')
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">
  <defs>
    <clipPath id="globe"><circle cx="{cx}" cy="{cy}" r="{r - 6}"/></clipPath>
    <radialGradient id="glow" cx="50%" cy="45%" r="60%">
      <stop offset="0" stop-color="#1a1a1a"/><stop offset="1" stop-color="#000"/>
    </radialGradient>
  </defs>
  <rect x="16" y="16" width="480" height="480" rx="112" fill="url(#glow)" stroke="#2c2c2c" stroke-width="4"/>
  {'' if small else '<path d="M 70 360 A 230 230 0 0 1 150 90" fill="none" stroke="#fff" stroke-opacity="0.18" stroke-width="3" stroke-linecap="round"/>'}
  {'' if small else '<path d="M 442 150 A 230 230 0 0 1 362 422" fill="none" stroke="#fff" stroke-opacity="0.18" stroke-width="3" stroke-linecap="round"/>'}
  <g clip-path="url(#globe)" fill="none" stroke="#fff" stroke-linecap="round">
    {"".join(lines)}
  </g>
  <circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="#fff" stroke-width="{24 if small else 12}"/>
</svg>'''
open(out, "w").write(svg)
