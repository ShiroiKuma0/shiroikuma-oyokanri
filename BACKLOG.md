# Backlog — 白い熊 応用管理

Work 白い熊 has parked rather than dropped. Newest first. Remove an item when it is done,
never when it is merely stale.

## Sweep all the sister repos for the progress-label mismatch
_Added 2026-09-09 (白い熊)._

Two sister apps have now been found sending their progress label in an extra named `text`
while 応用管理 reads it from `result` (`AppDataContract.EXTRA_RESULT`, consumed in
`AppDataClient`'s `ACTION_PROGRESS` branch) — 白い熊 地図, fixed in its 5.4.0+031, and
白い熊 辞書, fixed in its +043. Both were found by accident while chasing something else,
and in both cases the app had been emitting perfectly good phase lines that 応用管理 threw
away, which made long operations look silent and sent at least one sister chat off building
around the wrong assumption about what 応用管理 renders.

The check is one grep per repo under `~/git/shiroikuma-*`: does the app put its progress text
in `"result"`? Its terminal reply almost certainly already does — in both cases so far only
the progress path had diverged, which is why nobody noticed.

Report which apps are affected rather than fixing them here: each sister repo has its own
chat, and the fix belongs to it.
