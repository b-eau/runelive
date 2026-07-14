"use client";

// A deliberately quiet PWA install nudge. It only appears when:
//  - the browser reports installability (beforeinstallprompt), and
//  - this is at least the visitor's second day using the app, and
//  - it hasn't been snoozed ("Not now" hides it for 30 days).
// It also waits a few seconds after load so it never competes with the
// content the user came for.

import { useEffect, useState } from "react";

type BeforeInstallPromptEvent = Event & {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
};

const SNOOZE_KEY = "sidekick-install-snoozed-until";
const INSTALLED_KEY = "sidekick-installed";
const VISITS_KEY = "sidekick-visit-days";
const LAST_VISIT_KEY = "sidekick-last-visit-day";
const SNOOZE_DAYS = 30;
const SHOW_DELAY_MS = 4000;

function trackVisitDays(): number {
  try {
    const today = new Date().toISOString().slice(0, 10);
    let days = Number(localStorage.getItem(VISITS_KEY) ?? "0");
    if (localStorage.getItem(LAST_VISIT_KEY) !== today) {
      days += 1;
      localStorage.setItem(LAST_VISIT_KEY, today);
      localStorage.setItem(VISITS_KEY, String(days));
    }
    return days;
  } catch {
    return 0;
  }
}

export default function InstallPrompt() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const visitDays = trackVisitDays();
    let eligible = visitDays >= 2;
    try {
      if (localStorage.getItem(INSTALLED_KEY) === "1") eligible = false;
      const snoozedUntil = Number(localStorage.getItem(SNOOZE_KEY) ?? "0");
      if (snoozedUntil > Date.now()) eligible = false;
    } catch {}

    const onPrompt = (e: Event) => {
      e.preventDefault();
      if (eligible) setDeferred(e as BeforeInstallPromptEvent);
    };
    const onInstalled = () => {
      setDeferred(null);
      setVisible(false);
      try {
        localStorage.setItem(INSTALLED_KEY, "1");
      } catch {}
    };
    window.addEventListener("beforeinstallprompt", onPrompt);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onPrompt);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, []);

  // Give the page a beat before easing the toast in.
  useEffect(() => {
    if (!deferred) return;
    const t = setTimeout(() => setVisible(true), SHOW_DELAY_MS);
    return () => clearTimeout(t);
  }, [deferred]);

  function snooze() {
    setVisible(false);
    setDeferred(null);
    try {
      localStorage.setItem(SNOOZE_KEY, String(Date.now() + SNOOZE_DAYS * 24 * 60 * 60 * 1000));
    } catch {}
  }

  if (!deferred || !visible) return null;

  return (
    <div className="install-toast" role="dialog" aria-label="Install OSRS Sidekick">
      <span className="brand-mark" aria-hidden style={{ width: 32, height: 32, fontSize: 16 }}>
        ⚔
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 650, fontSize: 13.5 }}>Add Sidekick to your home screen</div>
        <div style={{ fontSize: 12, color: "var(--ink-3)" }}>Faster access, full screen, works offline.</div>
      </div>
      <button
        className="btn ghost"
        style={{ padding: "6px 10px", fontSize: 12.5, flexShrink: 0 }}
        onClick={snooze}
      >
        Not now
      </button>
      <button
        className="btn primary"
        style={{ padding: "6px 14px", fontSize: 12.5, flexShrink: 0 }}
        onClick={async () => {
          await deferred.prompt();
          const choice = await deferred.userChoice.catch(() => null);
          if (choice?.outcome === "dismissed") snooze();
          else {
            setDeferred(null);
            setVisible(false);
          }
        }}
      >
        Install
      </button>
    </div>
  );
}
