#!/usr/bin/env bash
# 실험 결과 디렉터리를 experiment-results 브랜치에 커밋·푸시한다 (워크플로 안에서 실행, contents: write 필요).
# 결과를 git 으로 가져올 수 있게 하려는 용도: git fetch origin experiment-results
# 사용: tools/experiments/publish_results.sh <결과 디렉터리> <이름>
set -euo pipefail
SRC="$1"; NAME="$2"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
WT="$(mktemp -d)"
if git ls-remote --exit-code --heads origin experiment-results >/dev/null 2>&1; then
  git fetch origin experiment-results
  git worktree add --detach "$WT" origin/experiment-results
  (cd "$WT" && git checkout -B experiment-results)
else
  git worktree add --detach "$WT"
  (cd "$WT" && git checkout --orphan experiment-results && git rm -rf . >/dev/null 2>&1 || true)
fi
DEST="$WT/$NAME/${GITHUB_RUN_ID:-local}"
mkdir -p "$DEST"
cp -r "$SRC"/. "$DEST"/
cd "$WT"
git add -A
git commit -m "experiment results: $NAME run ${GITHUB_RUN_ID:-local} [skip ci]" || exit 0
git push origin experiment-results
