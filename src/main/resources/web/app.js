"use strict";

// ---- State -----------------------------------------------------------------

const state = {
	modality: "text",
	transcript: [], // [{role, content}] sent to the server each turn
	pending: false,
};

const el = {
	chat: document.getElementById("chat"),
	input: document.getElementById("input"),
	send: document.getElementById("send"),
	mic: document.getElementById("mic"),
	player: document.getElementById("player"),
	modeText: document.getElementById("modeText"),
	modeVoice: document.getElementById("modeVoice"),
	account: document.getElementById("account"),
	accountName: document.getElementById("accountName"),
	accountMeta: document.getElementById("accountMeta"),
	speakingBar: document.getElementById("speakingBar"),
	stopSpeaking: document.getElementById("stopSpeaking"),
	setupHint: document.getElementById("setupHint"),
};

const speechRecognitionSupported =
	"SpeechRecognition" in window || "webkitSpeechRecognition" in window;
const speechSynthesisSupported = "speechSynthesis" in window;

// ---- Modality --------------------------------------------------------------

function setModality(mode) {
	state.modality = mode;
	el.modeText.classList.toggle("active", mode === "text");
	el.modeVoice.classList.toggle("active", mode === "voice");
	const voice = mode === "voice";
	el.mic.hidden = !(voice && speechRecognitionSupported);
	if (voice) {
		el.setupHint.textContent = speechRecognitionSupported
			? "Voice mode: tap the mic and speak. Replies are spoken aloud and kept short."
			: "Voice mode: replies are spoken aloud. (Your browser has no speech recognition, so type to ask.)";
	} else {
		el.setupHint.textContent =
			"Text mode: type your question. Replies can include details, prices and links.";
	}
	if (!voice) stopSpeaking();
}

el.modeText.addEventListener("click", () => setModality("text"));
el.modeVoice.addEventListener("click", () => setModality("voice"));

// ---- Prefill default player from /api/health -------------------------------

fetch("/api/health")
	.then((r) => r.json())
	.then((h) => {
		if (h.defaultPlayer && !el.player.value) el.player.value = h.defaultPlayer;
	})
	.catch(() => {});

// ---- Rendering -------------------------------------------------------------

function escapeHtml(s) {
	return s
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;");
}

// Tiny, safe markdown-ish renderer for text-mode replies.
function renderMarkdown(text) {
	const lines = escapeHtml(text).split(/\r?\n/);
	let html = "";
	let inList = false;
	for (let raw of lines) {
		const line = raw.trim();
		const isBullet = /^[-*]\s+/.test(line);
		if (isBullet) {
			if (!inList) { html += "<ul>"; inList = true; }
			html += "<li>" + inline(line.replace(/^[-*]\s+/, "")) + "</li>";
		} else {
			if (inList) { html += "</ul>"; inList = false; }
			if (line) html += "<p>" + inline(line) + "</p>";
		}
	}
	if (inList) html += "</ul>";
	return html || "<p></p>";
}

function inline(s) {
	return s
		.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
		.replace(/`([^`]+)`/g, "<code>$1</code>")
		.replace(/\bhttps?:\/\/[^\s)]+/g, (u) => `<a href="${u}" target="_blank" rel="noopener">${u}</a>`);
}

function addMessage(role, text, tools) {
	const div = document.createElement("div");
	div.className = "msg " + role;
	if (role === "assistant") {
		div.innerHTML = renderMarkdown(text);
		if (tools && tools.length) div.appendChild(renderTools(tools));
	} else {
		div.textContent = text;
	}
	el.chat.appendChild(div);
	el.chat.scrollTop = el.chat.scrollHeight;
	return div;
}

function renderTools(tools) {
	const details = document.createElement("details");
	details.className = "tools";
	const summary = document.createElement("summary");
	summary.textContent = `Looked up ${tools.length} thing${tools.length > 1 ? "s" : ""}`;
	details.appendChild(summary);
	for (const t of tools) {
		const d = document.createElement("div");
		d.className = "tool";
		d.innerHTML = `<span class="tname">${escapeHtml(t.name)}</span> ${escapeHtml(t.input)}<br>${escapeHtml(t.output)}`;
		details.appendChild(d);
	}
	return details;
}

function addError(text) {
	const div = document.createElement("div");
	div.className = "msg error";
	div.textContent = text;
	el.chat.appendChild(div);
	el.chat.scrollTop = el.chat.scrollHeight;
}

let typingEl = null;
function showTyping() {
	typingEl = document.createElement("div");
	typingEl.className = "typing";
	typingEl.textContent = "Sidekick is thinking…";
	el.chat.appendChild(typingEl);
	el.chat.scrollTop = el.chat.scrollHeight;
}
function hideTyping() {
	if (typingEl) { typingEl.remove(); typingEl = null; }
}

function updateAccount(ctx) {
	if (!ctx || !ctx.username) return;
	el.account.hidden = false;
	el.accountName.textContent = ctx.username;
	const type = ctx.ironman ? prettyType(ctx.accountType) : "Main";
	el.accountMeta.textContent = `${type} · Combat ${ctx.combatLevel} · Total ${ctx.totalLevel}`;
}

function prettyType(t) {
	if (!t) return "Account";
	return t.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

// ---- Sending ---------------------------------------------------------------

async function send() {
	const text = el.input.value.trim();
	if (!text || state.pending) return;
	const player = el.player.value.trim();
	if (!player) {
		addError("Enter your OSRS username at the top first.");
		return;
	}

	stopSpeaking();
	el.input.value = "";
	autoGrow();
	addMessage("user", text);
	state.transcript.push({ role: "user", content: text });

	state.pending = true;
	el.send.disabled = true;
	showTyping();

	try {
		const res = await fetch("/api/chat", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({
				messages: state.transcript,
				modality: state.modality,
				player: player,
			}),
		});
		const data = await res.json();
		hideTyping();

		if (data.error) {
			addError(data.error);
			return;
		}
		updateAccount(data.context);
		addMessage("assistant", data.reply, data.tools);
		state.transcript.push({ role: "assistant", content: data.reply });
		if (state.modality === "voice") speak(data.reply);
	} catch (e) {
		hideTyping();
		addError("Couldn't reach the sidekick. Is the server still running?");
	} finally {
		state.pending = false;
		el.send.disabled = false;
		el.input.focus();
	}
}

el.send.addEventListener("click", send);
el.input.addEventListener("keydown", (e) => {
	if (e.key === "Enter" && !e.shiftKey) {
		e.preventDefault();
		send();
	}
});

function autoGrow() {
	el.input.style.height = "auto";
	el.input.style.height = Math.min(el.input.scrollHeight, 140) + "px";
}
el.input.addEventListener("input", autoGrow);

// ---- Voice: text-to-speech -------------------------------------------------

function speak(text) {
	if (!speechSynthesisSupported) return;
	stopSpeaking();
	const utter = new SpeechSynthesisUtterance(text);
	utter.rate = 1.02;
	utter.onend = () => { el.speakingBar.hidden = true; };
	el.speakingBar.hidden = false;
	window.speechSynthesis.speak(utter);
}

function stopSpeaking() {
	if (speechSynthesisSupported) window.speechSynthesis.cancel();
	el.speakingBar.hidden = true;
}
el.stopSpeaking.addEventListener("click", stopSpeaking);

// ---- Voice: speech recognition ---------------------------------------------

let recognition = null;
let listening = false;

function initRecognition() {
	const Ctor = window.SpeechRecognition || window.webkitSpeechRecognition;
	if (!Ctor) return null;
	const rec = new Ctor();
	rec.lang = "en-US";
	rec.interimResults = true;
	rec.continuous = false;
	rec.onresult = (event) => {
		let transcript = "";
		for (let i = event.resultIndex; i < event.results.length; i++) {
			transcript += event.results[i][0].transcript;
		}
		el.input.value = transcript;
		autoGrow();
		if (event.results[event.results.length - 1].isFinal) {
			stopListening();
			send();
		}
	};
	rec.onerror = () => stopListening();
	rec.onend = () => stopListening();
	return rec;
}

function startListening() {
	if (!recognition) recognition = initRecognition();
	if (!recognition) return;
	stopSpeaking();
	listening = true;
	el.mic.classList.add("listening");
	try { recognition.start(); } catch (e) { stopListening(); }
}

function stopListening() {
	listening = false;
	el.mic.classList.remove("listening");
	if (recognition) { try { recognition.stop(); } catch (e) {} }
}

el.mic.addEventListener("click", () => {
	if (listening) stopListening();
	else startListening();
});

setModality("text");
