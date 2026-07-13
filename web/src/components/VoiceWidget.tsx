"use client";

// A compact, always-available voice companion — like Google Meet's picture-in-
// picture tile. It can pop out into a Document Picture-in-Picture window that
// floats over other tabs and apps (Chromium), or dock in-page otherwise.
//
// Deliberate, non-overbearing UX:
//  - The mic is manual: you tap to talk, and after each spoken reply it goes
//    idle — it never re-opens the mic on its own.
//  - Automatic endpointing: speech recognition ends the turn when you stop.
//  - Exactly one audio reply per query, and it's a short narration-optimized
//    line (the full answer is written to the chat transcript, not read aloud).
//  - A visible loading state while the model thinks, and a stop control to cut
//    off listening, thinking, or speaking at any time.

import { usePathname } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

type Phase = "idle" | "listening" | "thinking" | "speaking";

type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>; resultIndex: number }) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: { error: string }) => void) | null;
};

function getRecognizer(): SpeechRecognitionLike | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
  };
  const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
  return Ctor ? new Ctor() : null;
}

export default function VoiceWidget({ profileId, serverTts }: { profileId: string; serverTts: boolean }) {
  const pathname = usePathname();
  const onChatTab = pathname.endsWith("/chat"); // chat has its own inline voice

  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>("idle");
  const [interim, setInterim] = useState("");
  const [lastQuery, setLastQuery] = useState("");
  const [lastReply, setLastReply] = useState("");
  const [note, setNote] = useState("");
  const [pipWindow, setPipWindow] = useState<Window | null>(null);
  const [supported, setSupported] = useState(true);

  const phaseRef = useRef<Phase>("idle");
  phaseRef.current = phase;
  const recognizerRef = useRef<SpeechRecognitionLike | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const conversationIdRef = useRef<string | null>(null);

  const pipSupported = typeof window !== "undefined" && "documentPictureInPicture" in window;

  const toIdle = useCallback(() => {
    setInterim("");
    setPhase("idle");
  }, []);

  // Play exactly one spoken reply (ElevenLabs, falling back to the browser
  // voice), then return to idle — never auto-listen again.
  const speak = useCallback(
    async (text: string) => {
      setPhase("speaking");
      if (serverTts) {
        try {
          const res = await fetch("/api/tts", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text }),
            signal: AbortSignal.timeout(20_000),
          });
          if (!res.ok) throw new Error(`tts ${res.status}`);
          const url = URL.createObjectURL(await res.blob());
          const audio = new Audio(url);
          audioRef.current = audio;
          const done = () => {
            URL.revokeObjectURL(url);
            if (audioRef.current === audio) audioRef.current = null;
            if (phaseRef.current === "speaking") toIdle();
          };
          audio.onended = done;
          audio.onerror = done;
          await audio.play();
          return;
        } catch {
          /* fall back to browser speech */
        }
      }
      if ("speechSynthesis" in window) {
        const u = new SpeechSynthesisUtterance(text);
        u.rate = 1.05;
        u.onend = () => phaseRef.current === "speaking" && toIdle();
        u.onerror = () => phaseRef.current === "speaking" && toIdle();
        window.speechSynthesis.speak(u);
      } else {
        toIdle();
      }
    },
    [serverTts, toIdle],
  );

  const ask = useCallback(
    async (text: string) => {
      setLastQuery(text);
      setLastReply("");
      setNote("");
      setPhase("thinking");
      const ac = new AbortController();
      abortRef.current = ac;
      try {
        const res = await fetch("/api/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            profileId,
            message: text,
            voice: true,
            ...(conversationIdRef.current ? { conversationId: conversationIdRef.current } : {}),
          }),
          signal: ac.signal,
        });
        const body = await res.json().catch(() => ({}));
        if (ac.signal.aborted) return;
        if (body.conversationId) conversationIdRef.current = body.conversationId;
        const spoken: string = body.spoken || body.reply || "Sorry, I couldn't answer that.";
        setLastReply(spoken);
        await speak(spoken);
      } catch {
        if (ac.signal.aborted) {
          toIdle();
          return;
        }
        setNote("That didn't go through — try again.");
        toIdle();
      }
    },
    [profileId, speak, toIdle],
  );

  const startListening = useCallback(() => {
    if (phaseRef.current !== "idle") return;
    const rec = getRecognizer();
    if (!rec) {
      setSupported(false);
      setNote("Voice input isn't supported in this browser.");
      return;
    }
    recognizerRef.current = rec;
    rec.lang = "en-US";
    rec.continuous = false; // automatic endpointing on silence
    rec.interimResults = true;
    let finalText = "";
    rec.onresult = (e) => {
      let live = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r.isFinal) finalText += r[0].transcript;
        else live += r[0].transcript;
      }
      setInterim(live);
    };
    rec.onend = () => {
      setInterim("");
      if (phaseRef.current !== "listening") return;
      if (finalText.trim()) void ask(finalText.trim());
      else toIdle();
    };
    rec.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed") {
        setSupported(false);
        setNote("Microphone access was blocked.");
      }
      toIdle();
    };
    setLastQuery("");
    setLastReply("");
    setNote("");
    setPhase("listening");
    try {
      rec.start();
    } catch {
      toIdle();
    }
  }, [ask, toIdle]);

  // Cut off whatever is happening right now.
  const stop = useCallback(() => {
    if (phase === "listening") recognizerRef.current?.abort();
    else if (phase === "thinking") abortRef.current?.abort();
    else if (phase === "speaking") {
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current = null;
      }
      if ("speechSynthesis" in window) window.speechSynthesis.cancel();
    }
    toIdle();
  }, [phase, toIdle]);

  const closeAll = useCallback(() => {
    stop();
    pipWindow?.close();
    setPipWindow(null);
    setOpen(false);
  }, [stop, pipWindow]);

  const popOut = useCallback(async () => {
    if (!pipSupported) return;
    try {
      const pip = await (
        window as unknown as { documentPictureInPicture: { requestWindow: (o: object) => Promise<Window> } }
      ).documentPictureInPicture.requestWindow({ width: 300, height: 360 });
      // Carry the app's styles + theme into the PiP document.
      document.querySelectorAll('style, link[rel="stylesheet"]').forEach((n) => {
        pip.document.head.appendChild(n.cloneNode(true));
      });
      const theme = document.documentElement.getAttribute("data-theme");
      if (theme) pip.document.documentElement.setAttribute("data-theme", theme);
      pip.document.body.style.margin = "0";
      pip.document.body.style.background = getComputedStyle(document.body).backgroundColor;
      pip.addEventListener("pagehide", () => setPipWindow(null));
      setPipWindow(pip);
    } catch {
      /* user dismissed or unsupported */
    }
  }, [pipSupported]);

  useEffect(() => {
    return () => {
      recognizerRef.current?.abort();
      abortRef.current?.abort();
      if (audioRef.current) audioRef.current.pause();
      if (typeof window !== "undefined" && "speechSynthesis" in window) window.speechSynthesis.cancel();
    };
  }, []);

  const statusLabel =
    phase === "listening"
      ? "Listening…"
      : phase === "thinking"
        ? "Thinking…"
        : phase === "speaking"
          ? "Speaking…"
          : "Tap the mic to talk";

  const panel = (
    <div className="voice-panel">
      <div className="voice-panel-head">
        <span className="voice-title">
          <span aria-hidden>🎙️</span> Voice Sidekick
        </span>
        <div style={{ display: "flex", gap: 4 }}>
          {pipSupported && !pipWindow && (
            <button className="voice-icon-btn" onClick={() => void popOut()} title="Pop out" aria-label="Pop out to floating window">
              ⤢
            </button>
          )}
          <button className="voice-icon-btn" onClick={closeAll} title="Close" aria-label="Close voice">
            ✕
          </button>
        </div>
      </div>

      <div className="voice-body">
        {lastQuery && <div className="voice-you">“{lastQuery}”</div>}
        {phase === "thinking" ? (
          <div className="voice-reply thinking-dots" aria-live="polite">
            Thinking
          </div>
        ) : lastReply ? (
          <div className="voice-reply" aria-live="polite">
            {lastReply}
          </div>
        ) : interim ? (
          <div className="voice-reply" style={{ opacity: 0.6 }}>
            {interim}…
          </div>
        ) : (
          <div className="voice-reply" style={{ color: "var(--ink-3)" }}>
            {note || "Ask about training, gear, bosses, goals — anything about your account."}
          </div>
        )}
      </div>

      <div className="voice-controls">
        <div className={`voice-status voice-${phase}`} aria-live="polite">
          {statusLabel}
        </div>
        {phase === "idle" ? (
          <button
            className="voice-mic"
            onClick={startListening}
            disabled={!supported}
            aria-label="Start talking"
            title="Start talking"
          >
            🎙️
          </button>
        ) : (
          <button className="voice-mic stop" onClick={stop} aria-label="Stop" title="Stop">
            ■
          </button>
        )}
      </div>
    </div>
  );

  return (
    <>
      {!open && !onChatTab && (
        <button className="voice-fab" onClick={() => setOpen(true)} aria-label="Open voice Sidekick" title="Voice Sidekick">
          🎙️
        </button>
      )}
      {open &&
        (pipWindow
          ? createPortal(panel, pipWindow.document.body)
          : createPortal(<div className="voice-dock">{panel}</div>, document.body))}
    </>
  );
}
