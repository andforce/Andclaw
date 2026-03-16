# ShrimpMenubar

macOS menubar app for a **multi-relay** shrimp workflow:

```text
configure one or more remote OpenClaw WSS targets
-> derive team Feishu login URL from each WSS hostname
-> open Feishu login in the relay browser profile
-> verify login against derived /af/openclaw/overview
-> start one or more local ws:// relays on different ports
```

## What this version does

- stores config in `~/Library/Application Support/ShrimpMenubar/config.json`
- supports **multiple relay configs** in one menubar app
- each relay can listen on its **own local port**
- derives the Feishu team login URL from the WSS hostname
  - example:
    - WSS: `wss://gcnxj886r06f-app_4jqhmcy1aypwy-1859632864877715.aiforce.run/af/openclaw`
    - team id: `gcnxj886r06f`
    - login URL: `https://gcnxj886r06f.feishu.cn`
- keeps using the existing persistent browser profile + relay scripts
- starts the local relay using `shrimp_ws_bridge.mjs bridge`
- exposes per-relay logs + quick links in the menubar menu

## Important scope change

Mission Control has been removed from the app surface.

This tool is now focused on:
- Feishu login / cookie preparation
- local ws relay startup
- multi-port relay management

It is **not** a Mission Control launcher anymore.

## Run

```bash
cd /Users/ai/clawd/workspaces/Engineering/shrimp-menubar
swift run ShrimpMenubar
```

## First-time setup

On first launch the app creates:

```text
~/Library/Application Support/ShrimpMenubar/config.json
```

The new format is:

```json
{
  "relays": [
    {
      "id": "relay-1",
      "name": "shrimp-3",
      "listenMode": "localhost",
      "bridgePort": 18790,
      "remoteGatewayWSS": "wss://.../af/openclaw",
      "gatewayToken": "SET_ME",
      "browserProfileDir": "...",
      "browserChannel": "chromium",
      "relayScriptPath": ".../shrimp_relay.mjs",
      "bridgeScriptPath": ".../shrimp_ws_bridge.mjs",
      "nodeBinary": "node",
      "loginLogPath": "...",
      "bridgeLogPath": "..."
    }
  ]
}
```

Minimum fields to review for each relay:
- `name`
- `bridgePort`
- `remoteGatewayWSS`
- `gatewayToken`
- `relayScriptPath`
- `bridgeScriptPath`
- `nodeBinary`

## Validation behavior

The app surfaces problems directly in the menu before startup:
- missing placeholder token
- invalid / missing remote WSS
- failure to derive the team Feishu URL from WSS
- missing relay / bridge scripts
- missing `node`
- malformed, incomplete, or legacy config (it rewrites/migrates config and shows a warning)

## Main actions per relay

1. **Check Login**
   - verifies login state using the derived OpenClaw overview URL for that WSS target

2. **Open Feishu Login**
   - opens the derived team Feishu URL in the persistent relay browser profile
   - intended for Feishu / Miaoda auth preparation

3. **Start Relay**
   - starts a local WebSocket relay on the configured local port

4. **Stop Relay**
   - stops that relay only

5. **One-click Start**
   - checks login first
   - if logged in: starts relay
   - otherwise: opens Feishu login flow

## Settings window

The settings window is a standalone native macOS window, not a menu sheet.

It supports:
- adding multiple relay configs
- deleting relay configs
- editing per-relay ports / WSS / tokens / profile dirs / log paths

## Logs

Each relay has separate logs:
- login log
- bridge log

Use the per-relay log buttons in the menu to inspect them.
