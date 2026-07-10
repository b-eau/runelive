import Link from "next/link";
import { redirect } from "next/navigation";
import { currentUser, googleEnabled } from "@/lib/auth";
import SignInForm from "./SignInForm";

export const metadata = { title: "Sign in" };

export default async function SignInPage({
  searchParams,
}: {
  searchParams: Promise<{ error?: string; next?: string }>;
}) {
  const user = await currentUser();
  if (user) redirect("/dashboard");
  const params = await searchParams;

  return (
    <main className="shell" style={{ display: "grid", placeItems: "center", minHeight: "100dvh" }}>
      <div style={{ width: "100%", maxWidth: 400 }}>
        <div style={{ textAlign: "center", marginBottom: 24 }}>
          <Link href="/" className="brand" style={{ justifyContent: "center", fontSize: 20 }}>
            <span className="brand-mark">⚔</span> OSRS Sidekick
          </Link>
        </div>
        <div className="card">
          <h2 style={{ fontSize: 18, textAlign: "center" }}>Welcome back, adventurer</h2>
          <p className="sub" style={{ textAlign: "center" }}>
            Sign in with a magic link — no password needed.
          </p>
          <SignInForm googleEnabled={googleEnabled()} next={params.next} initialError={params.error} />
        </div>
        <p style={{ textAlign: "center", fontSize: 12.5, color: "var(--ink-3)", marginTop: 16 }}>
          Local dev: the magic link prints to the server console.
        </p>
      </div>
    </main>
  );
}
