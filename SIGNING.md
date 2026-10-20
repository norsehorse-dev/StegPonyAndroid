# Signing

Commits and tags on `main` are signed with the maintainer's SSH signing key on the norsehorse-dev GitHub account. The author identity is the repo-local one:

```
git config user.name "norsehorse-dev"
git config user.email "norsehorse-dev@users.noreply.github.com"
```

## Commits made elsewhere

Some commits are made in a working session that has no access to the signing key, so they arrive unsigned. They are re-signed on the maintainer's machine before anything is pushed, and an unsigned commit is never pushed.

The maintainer's machine has signing set up globally (`gpg.format ssh`, `user.signingkey` pointing at the signing key, `gpg.program` unset or plain `gpg`). To re-sign every unpushed commit on the current branch:

```
git rebase --exec 'git commit --amend --no-edit -S' origin/main
```

For a repository with no remote yet, from the first commit:

```
git rebase --root --exec 'git commit --amend --no-edit -S'
```

Check before pushing:

```
git log --show-signature -n 5
```

## Tags

Release tags are signed tags:

```
git tag -s v1.0.0 -m "StegPonyAndroid 1.0.0"
git tag -v v1.0.0
```

## Release files

Release artifacts get their own detached signatures and checksums, published next to the files. Those are separate from commit and tag signing.
