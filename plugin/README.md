# OSRS Sidekick Sync (RuneLite plugin)

A lightweight syncing plugin that feeds account updates to your
[OSRS Sidekick](../README.md) dashboard.

## What it syncs

| Domain | When |
|---|---|
| Skills (all XP + levels) | On login, on level-up, and every 15 min if total XP changed |
| Quest log | On login (a few ticks after the world loads) |
| Bank | When the bank container changes |
| Inventory / equipment | When they change (rate-limited to every 30 s) |
| Boss kill counts | When a kill-count chat message appears |
| Profile kind + account type | Detected each login (main / leagues / deadman; ironman variants) |

Syncing is **off by default** — enable it in the plugin settings (you'll see
the standard third-party-server warning).

## Linking your account

1. Log into the game.
2. Open the **OSRS Sidekick** side panel and click **Link account**.
3. Your browser opens the Sidekick site with a one-time code pre-filled; sign
   in (magic link or Google) and click **Link this account**.
4. The plugin picks up the credential within a few seconds and starts syncing.

No manual copy/pasting is needed; if the browser doesn't open, visit
`<server>/link` and type the 8-character code shown in the panel.

## Development

```bash
./gradlew run    # launches a dev RuneLite client with the plugin loaded
```

Point the plugin at a local backend by setting **Sidekick server URL** to
`http://localhost:3000` (the default).
