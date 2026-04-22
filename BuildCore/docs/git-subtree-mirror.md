# Git-subtree mirror procedure

BuildCore lives inside `VitalPlugins` as a subproject, and is mirrored to a
standalone private repo at `git@github.com:xgoingleftyx/BuildCore.git` (or
wherever the owner configures). This document captures the one-time setup
and the recurring push command.

## One-time setup (owner performs once)

1. Create the empty private remote on GitHub: `BuildCore`.
2. From the VitalPlugins working copy:

```bash
cd /c/Code/VitalPlugins
git remote add buildcore-mirror git@github.com:xgoingleftyx/BuildCore.git
```

3. First push seeds the mirror with the full BuildCore history:

```bash
git subtree push --prefix=BuildCore buildcore-mirror main
```

## Recurring mirror (periodic, ad-hoc)

After any merge to `main` that touches `BuildCore/`:

```bash
git subtree push --prefix=BuildCore buildcore-mirror main
```

This pushes only the BuildCore subtree's commits to the mirror. Commits
that don't touch `BuildCore/` are filtered out automatically.

## If the mirror diverges

Subtree push is fast-forward only. If the mirror repo has commits that
aren't in VitalPlugins (e.g., a direct edit on the mirror), you must
either:
1. Pull those back via `git subtree pull --prefix=BuildCore buildcore-mirror main`, OR
2. Force-push the mirror: `git push buildcore-mirror $(git subtree split --prefix=BuildCore main):main --force`

Prefer option 1 unless you know the mirror's divergent commits were a
mistake.

## Not implemented yet

- Automated periodic mirror push (cron job, GitHub Action)
- Importing BuildCore into an unrelated client repo (this is the
  eventual portability story — deferred until we need it)
