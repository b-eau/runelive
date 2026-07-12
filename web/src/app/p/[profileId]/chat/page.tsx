import { notFound } from "next/navigation";
import { authorizedProfile } from "@/lib/data";
import { llmEnabled } from "@/lib/sidekick";
import ChatPanel from "./ChatPanel";

export const metadata = { title: "Sidekick" };

export default async function ChatPage({
  params,
  searchParams,
}: {
  params: Promise<{ profileId: string }>;
  searchParams: Promise<{ ask?: string }>;
}) {
  const { profileId } = await params;
  const { ask } = await searchParams;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  return (
    <ChatPanel
      profileId={profileId}
      displayName={profile.account.displayName}
      demoMode={!llmEnabled()}
      serverTts={!!process.env.ELEVENLABS_API_KEY}
      initialPrompt={ask ? ask.slice(0, 300) : undefined}
    />
  );
}
