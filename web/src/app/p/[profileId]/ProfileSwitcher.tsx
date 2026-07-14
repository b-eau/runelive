"use client";

import { useRouter } from "next/navigation";

export default function ProfileSwitcher({
  current,
  options,
  compact = false,
}: {
  current: string;
  options: { id: string; label: string }[];
  compact?: boolean;
}) {
  const router = useRouter();
  if (options.length <= 1) return null;
  return (
    <select
      value={current}
      onChange={(e) => router.push(`/p/${e.target.value}`)}
      style={
        compact
          ? { width: "auto", maxWidth: 150, padding: "5px 8px", fontSize: 12, borderRadius: 8 }
          : { width: "100%", padding: "7px 10px", fontSize: 12.5 }
      }
      aria-label="Switch profile"
    >
      {options.map((o) => (
        <option key={o.id} value={o.id}>
          {o.label}
        </option>
      ))}
    </select>
  );
}
