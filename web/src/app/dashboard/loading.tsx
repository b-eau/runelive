import { Sk } from "@/components/Skeleton";

export default function DashboardLoading() {
  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand">
            <span className="brand-mark">⚔</span> OSRS Sidekick
          </div>
        </div>
      </header>
      <main className="shell">
        <h1 style={{ fontSize: 24, margin: "28px 0 4px", letterSpacing: "-0.02em" }}>Your characters</h1>
        <p style={{ color: "var(--ink-3)", marginBottom: 20, fontSize: 13 }}>Loading your profiles…</p>
        <div className="grid cols-2">
          {[0, 1].map((i) => (
            <div key={i} className="card" style={{ display: "flex", alignItems: "center", gap: 12 }}>
              <Sk h={44} w={44} r={12} />
              <div style={{ flex: 1 }}>
                <Sk h={16} w="50%" />
                <Sk h={20} w="70%" r={999} style={{ marginTop: 8 }} />
              </div>
            </div>
          ))}
        </div>
      </main>
    </>
  );
}
