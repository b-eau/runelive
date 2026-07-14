import { ChartCardSkeleton, ListCardSkeleton, PageHeadSkeleton } from "@/components/Skeleton";

export default function BankLoading() {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
      <PageHeadSkeleton title="Bank" sub="Everything you own — bank, gear, and inventory, priced live" />
      <ChartCardSkeleton />
      <ListCardSkeleton rows={10} />
    </div>
  );
}
