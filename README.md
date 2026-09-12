# Rokid To Do

A voice-first **Rokid Nexus** plugin for keeping a persistent To Do list from Rokid glasses and the phone-side Nexus plugin screen.

## v0.1.0 features

- Headless Nexus phone plugin; nothing extra is installed on the glasses.
- Captures **raw audio from the glasses microphone** through the Nexus `microphone` capability.
- Does **not** request or use Nexus `stt`.
- Sends the raw recording from the phone to a Gemini multimodal model, which returns a structured `add` or `list` action rather than a transcript.
- Voice can add one or several tasks, or ask to hear remaining tasks.
- Glasses views: **All**, **Today**, and **Older**.
- Pending task order is always newest first using a persistent creation sequence.
- Glasses task actions: **Mark as done**, **Delete**, **Cancel**.
- Phone plugin screen: manual add, AI setup, All/Today/Older pending sections, Completed section, Done and Delete actions.
- Completed tasks stay stored and visible on the phone; deleted tasks are removed permanently.
- "Today" is based on the phone's current local calendar date. At the next local day boundary, unfinished tasks automatically appear under Older while remaining in All.
- Uses Nexus TTS to read remaining tasks aloud when the AI detects a request such as “What do I still need to do?”

## Glasses controls

1. Open **To Do** from the Nexus launcher.
2. Swipe to move through rows.
3. Tap **+ Voice add / ask** to start recording. Speak, then tap again to finish. Back cancels.
4. Tap **View: All / Today / Older** to cycle the visible list.
5. Tap a task to open **Mark as done / Delete / Cancel**.

## AI setup

Open **Rokid Nexus → To Do** on the phone and save a Gemini API key. The model is configurable; the default in v0.1.0 is `gemini-3.8-flash`.

The API key is stored only in this plugin's private Android app storage. No key is included in the repository or APK source. Raw audio is transmitted to the configured Gemini API only when you explicitly start a voice request.

## Nexus permissions

Approve these capabilities for To Do in **Rokid Nexus → Settings → Plugin access**:

- `surfaces`
- `microphone`
- `tts`

No Android `RECORD_AUDIO` permission and no Nexus `stt` permission are requested.

## Build

JDK 17 and Android SDK 36 are used. The project consumes the published Nexus SDK from JitPack.

```bash
gradle :app:assembleDebug -PsdkVersion=sdk-v0.16.0
```

GitHub Actions builds and publishes the debug APK to the `v0.1.0` GitHub release.

## Notes

The phone/HUD list architecture and Nexus UI conventions were informed by the MIT-licensed `beyondlevi/nexus-shoplist` reference plugin and the official `Anezium/Rokid-Nexus` sample/SDK documentation. Rokid Nexus itself is Apache-2.0 licensed.
