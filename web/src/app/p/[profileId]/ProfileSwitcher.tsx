"use client";

import { useRouter } from "next/navigation";

export default function ProfileSwitcher({
  current,
  options,
}: {
  current: string;
  options: { id: string; label: string }[];
}) {
  const router = useRouter();
  if (options.length <= 1) return null;
  return (
    <select
      value={current}
      onChange={(e) => router.push(`/p/${e.target.value}`)}
      style={{ width: "auto", maxWidth: 260, padding: "7px 10px", fontSize: 13 }}
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
