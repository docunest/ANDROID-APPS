# ANDROID-APPS

One folder per app; each has its own README (and rules where needed):

- `dock/`: car head-unit dock app.
- `ponas/`: kid-tablet launcher plus HTML/JS games. **Before touching anything in `ponas/`,
  read `ponas/CLAUDE.md`.** To add or change a game, use the `ponas-game` skill
  (`.claude/skills/ponas-game/`). Game changes only touch `ponas/games/<id>/` and never need a
  new APK.

Don't change one app's files while working on another.
