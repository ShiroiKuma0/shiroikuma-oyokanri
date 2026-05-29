The user has approved pushing the current changes. Run:

1. `git add -A` (include any new files).
2. Show `git status` so the staged set is visible.
3. `git commit -m "<concise imperative title>" -m "<multi-line body>"` — the body should explain *what* changed and *why*, not just restate the diff. If the change covers several related things, group them under short bullets.
4. `git push origin custom`. **Never** force-push.
5. Report the resulting commit hash and a one-line summary.

If anything looks wrong before the commit (e.g. unexpected files staged, the diff doesn't match what was just discussed), stop and ask the user first.
