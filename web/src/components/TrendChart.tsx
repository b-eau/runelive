"use client";

// Single-series time trend chart (SVG): 2px line, soft area fill, recessive
// grid, crosshair + tooltip on hover/touch. Single series ⇒ no legend; the
// card title names it (dataviz method).

import { useMemo, useRef, useState } from "react";

export type TrendPoint = { date: string; value: number; delta?: number };

function fmt(n: number): string {
  const abs = Math.abs(n);
  if (abs >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + "B";
  if (abs >= 1_000_000) return (n / 1_000_000).toFixed(abs >= 10_000_000 ? 1 : 2) + "M";
  if (abs >= 10_000) return (n / 1_000).toFixed(0) + "K";
  if (abs >= 1_000) return (n / 1_000).toFixed(1) + "K";
  return n.toLocaleString("en-US");
}

function fmtDate(iso: string): string {
  const d = new Date(iso + "T00:00:00Z");
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
}

export default function TrendChart({
  points,
  color = "var(--series-1)",
  height = 220,
  unit = "",
  showDelta = true,
}: {
  points: TrendPoint[];
  color?: string;
  height?: number;
  unit?: string;
  showDelta?: boolean;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const W = 720;
  const H = height;
  const PAD = { top: 14, right: 12, bottom: 26, left: 46 };

  const model = useMemo(() => {
    if (points.length === 0) return null;
    const xs = points.map((p) => +new Date(p.date + "T00:00:00Z"));
    const ys = points.map((p) => p.value);
    const xMin = Math.min(...xs);
    const xMax = Math.max(...xs);
    let yMin = Math.min(...ys);
    let yMax = Math.max(...ys);
    if (yMin === yMax) {
      yMin -= 1;
      yMax += 1;
    }
    const yPad = (yMax - yMin) * 0.08;
    yMin = Math.max(0, yMin - yPad);
    yMax += yPad;
    const x = (t: number) =>
      PAD.left + ((t - xMin) / Math.max(1, xMax - xMin)) * (W - PAD.left - PAD.right);
    const y = (v: number) => PAD.top + (1 - (v - yMin) / (yMax - yMin)) * (H - PAD.top - PAD.bottom);
    const coords = points.map((p, i) => ({ px: x(xs[i]), py: y(ys[i]), p }));
    const line = coords.map((c, i) => `${i === 0 ? "M" : "L"}${c.px.toFixed(1)},${c.py.toFixed(1)}`).join("");
    const area = `${line}L${coords[coords.length - 1].px.toFixed(1)},${H - PAD.bottom}L${coords[0].px.toFixed(1)},${H - PAD.bottom}Z`;
    // ~4 y gridlines
    const ticks: { v: number; py: number }[] = [];
    for (let i = 0; i <= 3; i++) {
      const v = yMin + ((yMax - yMin) * i) / 3;
      ticks.push({ v, py: y(v) });
    }
    // x labels: first, middle, last
    const xLabels = [0, Math.floor(points.length / 2), points.length - 1]
      .filter((v, i, arr) => arr.indexOf(v) === i)
      .map((i) => ({ px: coords[i].px, label: fmtDate(points[i].date) }));
    return { coords, line, area, ticks, xLabels };
  }, [points, H]);

  if (!model || points.length < 2) {
    return <div className="empty">Not enough data yet — trends appear after a couple of syncs.</div>;
  }

  const onMove = (clientX: number) => {
    const svg = svgRef.current;
    if (!svg) return;
    const rect = svg.getBoundingClientRect();
    const px = ((clientX - rect.left) / rect.width) * W;
    let best = 0;
    let bestDist = Infinity;
    model.coords.forEach((c, i) => {
      const d = Math.abs(c.px - px);
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    });
    setHover(best);
  };

  const h = hover !== null ? model.coords[hover] : null;
  const tooltipLeft = h ? Math.min(Math.max((h.px / W) * 100, 12), 84) : 0;

  return (
    <div style={{ position: "relative" }}>
      <svg
        ref={svgRef}
        viewBox={`0 0 ${W} ${H}`}
        style={{ width: "100%", height: "auto", display: "block", touchAction: "pan-y" }}
        onMouseMove={(e) => onMove(e.clientX)}
        onMouseLeave={() => setHover(null)}
        onTouchStart={(e) => onMove(e.touches[0].clientX)}
        onTouchMove={(e) => onMove(e.touches[0].clientX)}
        role="img"
        aria-label="Trend chart"
      >
        {model.ticks.map((t, i) => (
          <g key={i}>
            <line x1={PAD.left} x2={W - PAD.right} y1={t.py} y2={t.py} stroke="var(--grid)" strokeWidth="1" />
            <text x={PAD.left - 8} y={t.py + 4} textAnchor="end" fontSize="11" fill="var(--ink-3)" style={{ fontVariantNumeric: "tabular-nums" }}>
              {fmt(t.v)}
            </text>
          </g>
        ))}
        {model.xLabels.map((l, i) => (
          <text
            key={i}
            x={l.px}
            y={H - 8}
            textAnchor={i === 0 ? "start" : i === model.xLabels.length - 1 ? "end" : "middle"}
            fontSize="11"
            fill="var(--ink-3)"
          >
            {l.label}
          </text>
        ))}
        <path d={model.area} fill={color} opacity="0.10" />
        <path d={model.line} fill="none" stroke={color} strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        {h && (
          <g>
            <line x1={h.px} x2={h.px} y1={PAD.top} y2={H - PAD.bottom} stroke="var(--baseline)" strokeWidth="1" />
            <circle cx={h.px} cy={h.py} r="4.5" fill={color} stroke="var(--surface-1)" strokeWidth="2" />
          </g>
        )}
      </svg>
      {h && (
        <div
          style={{
            position: "absolute",
            top: 0,
            left: `${tooltipLeft}%`,
            transform: "translateX(-50%)",
            background: "var(--surface-3)",
            border: "1px solid var(--border-strong)",
            borderRadius: 8,
            padding: "6px 10px",
            fontSize: 12,
            pointerEvents: "none",
            whiteSpace: "nowrap",
            boxShadow: "var(--shadow)",
          }}
        >
          <div style={{ color: "var(--ink-3)" }}>{fmtDate(h.p.date)}</div>
          <div style={{ fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
            {fmt(h.p.value)}
            {unit}
          </div>
          {showDelta && h.p.delta !== undefined && h.p.delta !== 0 && (
            <div style={{ color: h.p.delta > 0 ? "var(--good)" : "var(--critical)", fontVariantNumeric: "tabular-nums" }}>
              {h.p.delta > 0 ? "+" : ""}
              {fmt(h.p.delta)}
              {unit}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
