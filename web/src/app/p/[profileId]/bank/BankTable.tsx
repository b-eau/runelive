"use client";

import { useMemo, useState } from "react";
import type { BankRow } from "./page";

function gp(n: number): string {
  if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + "B";
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + "M";
  if (n >= 10_000) return (n / 1_000).toFixed(0) + "K";
  return n.toLocaleString("en-US");
}

// Inventory icons keyed by item id, from RuneLite's static cache CDN — the same
// source the item catalog already pulls names from. Native icons are 36×32.
function itemIconUrl(id: number): string {
  return `https://static.runelite.net/cache/item/icon/${id}.png`;
}

function ItemIcon({ id }: { id: number }) {
  const [broken, setBroken] = useState(false);
  return (
    <span className="item-icon" aria-hidden="true">
      {!broken && (
        <img
          src={itemIconUrl(id)}
          alt=""
          width={36}
          height={32}
          loading="lazy"
          decoding="async"
          onError={() => setBroken(true)}
        />
      )}
    </span>
  );
}

const TABS = [
  { key: "bank", label: "Bank" },
  { key: "equipment", label: "Equipped" },
  { key: "inventory", label: "Inventory" },
] as const;

export default function BankTable({
  bank,
  equipment,
  inventory,
}: {
  bank: BankRow[];
  equipment: BankRow[];
  inventory: BankRow[];
}) {
  const [tab, setTab] = useState<(typeof TABS)[number]["key"]>("bank");
  const [query, setQuery] = useState("");

  const rows = tab === "bank" ? bank : tab === "equipment" ? equipment : inventory;
  const filtered = useMemo(
    () => rows.filter((r) => r.name.toLowerCase().includes(query.toLowerCase())),
    [rows, query],
  );
  const shownValue = filtered.reduce((acc, r) => acc + r.total, 0);

  return (
    <div className="card">
      <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap", marginBottom: 12 }}>
        <div className="tabs" style={{ margin: 0 }}>
          {TABS.map((t) => (
            <a
              key={t.key}
              className={tab === t.key ? "active" : ""}
              onClick={() => setTab(t.key)}
              style={{ cursor: "pointer" }}
              role="tab"
              aria-selected={tab === t.key}
            >
              {t.label}
            </a>
          ))}
        </div>
        <input
          type="text"
          placeholder="Search items…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ maxWidth: 240 }}
          aria-label="Search items"
        />
        <div className="spacer" />
        <span className="pill">
          {filtered.length} items · {gp(shownValue)} gp
        </span>
      </div>
      {filtered.length === 0 ? (
        <div className="empty">
          {rows.length === 0
            ? "Nothing synced yet — open your bank in-game and the plugin will send a snapshot."
            : "No items match your search."}
        </div>
      ) : (
        <div className="table-scroll" style={{ maxHeight: 560, overflowY: "auto" }}>
          <table className="table">
            <thead>
              <tr>
                <th>Item</th>
                <th className="num">Qty</th>
                <th className="num">Unit price</th>
                <th className="num">Total</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="item-cell">
                      <ItemIcon id={r.id} />
                      <span>{r.name}</span>
                    </span>
                  </td>
                  <td className="num">{r.qty.toLocaleString("en-US")}</td>
                  <td className="num">{r.unitPrice ? gp(r.unitPrice) : "—"}</td>
                  <td className="num" style={{ fontWeight: 600 }}>
                    {r.total ? gp(r.total) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
