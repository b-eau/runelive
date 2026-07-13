"use client";

// Shows a subtle "Install app" button when the browser reports the PWA is
// installable (Chrome/Edge/Android fire beforeinstallprompt). Dismissed or
// installed state is remembered so it doesn't nag.

import { useEffect, useState } from "react";

type BeforeInstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
};

const DISMISS_KEY = "sidekick-install-dismissed";

export default function InstallPrompt() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);

  useEffect(() => {
    if (localStorage.getItem(DISMISS_KEY) === "1") return;
    const onPrompt = (e: Event) => {
      e.preventDefault();
      setDeferred(e as BeforeInstallPromptEvent);
    };
    const onInstalled = () => {
      setDeferred(null);
      localStorage.setItem(DISMISS_KEY, "1");
    };
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onPrompt);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, []);

  if (!deferred) return null;

  return (
    <div className="install-prompt" role="dialog" aria-label="Install OSRS Sidekick">
      <span className="brand-mark" aria-hidden style={{ width: 30, height: 30, fontSize: 15 }}>
        ⚔
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 650, fontSize: 13.5 }}>Install Sidekick</div>
        <div style={{ fontSize: 12, color: "var(--ink-3)" }}>Full-screen, home-screen, offline-ready.</div>
      </div>
      <button
        className="btn primary"
        style={{ padding: "6px 12px", fontSize: 12.5 }}
        onClick={async () => {
          await deferred.prompt();
          await deferred.userChoice.catch(() => {});
          setDeferred(null);
        }}
      >
        Install
      </button>
      <button
        className="btn ghost"
        style={{ padding: "6px 8px", fontSize: 12.5 }}
        aria-label="Dismiss"
        onClick={() => {
          localStorage.setItem(DISMISS_KEY, "1");
          setDeferred(null);
        }}
      >
        ✕
      </button>
    </div>
  );
}
