# ANDROID-APPS

One folder per app; each has its own README (and rules where needed):

- `dock/`: car head-unit dock app.
- `reelbox/`: Xtream video player. Web app (GitHub Pages), native app for Expo Go in
  `reelbox/native/` (shares `reelbox/src/lib`; see `reelbox/native/AGENTS.md`), Capacitor Android,
  Samsung Tizen. Its backend is `apps/api` in `docunest/monorepo`. See `reelbox/README.md`.
- `ponas/`: kid-tablet launcher plus HTML/JS games. **Before touching anything in `ponas/`,
  read `ponas/CLAUDE.md`.** To add or change a game, use the `ponas-game` skill
  (`.claude/skills/ponas-game/`). Game changes only touch `ponas/games/<id>/` and never need a
  new APK.

Don't change one app's files while working on another.
