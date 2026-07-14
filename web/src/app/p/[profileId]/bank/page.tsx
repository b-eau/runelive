import { notFound } from "next/navigation";
import { db } from "@/lib/db";
import { authorizedProfile } from "@/lib/data";
import { formatGp } from "@/lib/osrs";
import { bankSeries } from "@/lib/series";
import TrendChart from "@/components/TrendChart";
import AskSidekick from "@/components/AskSidekick";
import BankTable from "./BankTable";

export const metadata = { title: "Bank" };

export type BankRow = {
  id: number;
  name: string;
  qty: number;
  unitPrice: number;
  total: number;
};

async function containerRows(profileId: string, container: string): Promise<BankRow[]> {
  const state = await db.containerState.findUnique({
    where: { profileId_container: { profileId, container } },
  });
  if (!state) return [];
  const items = JSON.parse(state.items) as { id: number; qty: number }[];
  const prices = await db.itemPrice.findMany({ where: { itemId: { in: items.map((i) => i.id) } } });
  const byId = new Map(prices.map((p) => [p.itemId, p]));
  return items
    .map((i) => {
      const price = byId.get(i.id);
      const unitPrice = i.id === 995 ? 1 : (price?.price ?? 0);
      return {
        id: i.id,
        name: price?.name ?? `Item #${i.id}`,
        qty: i.qty,
        unitPrice,
        total: unitPrice * i.qty,
      };
    })
    .sort((a, b) => b.total - a.total);
}

export default async function BankPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const [bank, equipment, inventory, series, bankState] = await Promise.all([
    containerRows(profileId, "BANK"),
    containerRows(profileId, "EQUIPMENT"),
    containerRows(profileId, "INVENTORY"),
    bankSeries(profileId, 365),
    db.containerState.findUnique({ where: { profileId_container: { profileId, container: "BANK" } } }),
  ]);

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <header className="page-head">
        <h1>Bank</h1>
        <p className="sub">Everything you own — bank, gear, and inventory, priced live</p>
      </header>
      <div className="card">
        <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
          <h3>Bank value — last 12 months</h3>
          {bankState && <span className="pill gold">{formatGp(bankState.value)} gp</span>}
        </div>
        <p className="sub">Estimated with GE guide prices; untradeables count as 0</p>
        <TrendChart points={series} color="var(--series-3)" unit=" gp" />
        <AskSidekick profileId={profileId} context="bank" />
      </div>
      <BankTable bank={bank} equipment={equipment} inventory={inventory} />
    </div>
  );
}
