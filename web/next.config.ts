import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // BigInt-heavy Prisma results are serialized manually in lib/serialize.ts
  experimental: {},
};

export default nextConfig;
