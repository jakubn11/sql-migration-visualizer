#!/usr/bin/env bash
set -euo pipefail

NEW_VERSION="${1:-}"
if [[ -z "$NEW_VERSION" ]]; then
  echo "Usage: ./bump-version.sh <new-version>"
  echo "Example: ./bump-version.sh 1.1.0"
  exit 1
fi

CURRENT_VERSION=$(grep '^pluginVersion' gradle.properties | cut -d'=' -f2 | tr -d ' ')
TODAY=$(date +%Y-%m-%d)

echo "Bumping $CURRENT_VERSION → $NEW_VERSION"

# Update gradle.properties
sed -i '' "s/^pluginVersion = .*/pluginVersion = $NEW_VERSION/" gradle.properties

# Insert new version section in CHANGELOG.md above [Unreleased]
CHANGELOG_ENTRY="## [$NEW_VERSION] - $TODAY"
COMPARE_LINE="[Unreleased]: https://github.com/jakubn11/sql-migration-visualizer/compare/v$NEW_VERSION...HEAD"
PREV_COMPARE_LINE="[$NEW_VERSION]: https://github.com/jakubn11/sql-migration-visualizer/compare/v$CURRENT_VERSION...v$NEW_VERSION"

# Add blank version section after ## [Unreleased]
sed -i '' "/^## \[Unreleased\]/a\\
\\
$CHANGELOG_ENTRY\\
\\
### Added\\
\\
### Changed\\
\\
### Fixed\\
" CHANGELOG.md

# Update comparison links at the bottom
sed -i '' "s|^\[Unreleased\]:.*|$COMPARE_LINE\n$PREV_COMPARE_LINE|" CHANGELOG.md

# Commit, tag, push
git add gradle.properties CHANGELOG.md
git commit -m "Bump version to $NEW_VERSION"
git tag "v$NEW_VERSION"
git push && git push origin "v$NEW_VERSION"

echo "Done — v$NEW_VERSION tagged and pushed."
