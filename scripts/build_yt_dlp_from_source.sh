#!/usr/bin/env bash
set -euo pipefail

# yt-dlp's Makefile uses Info-ZIP. Normalize the process environment so the
# embedded zipimport archive does not depend on the builder's timezone, umask,
# UID, or GID.
umask 022
export TZ=UTC
export ZIPOPT=-X

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
source_dir="$repo_root/third_party/yt-dlp"
ejs_source_dir="$repo_root/third_party/yt-dlp-ejs"
build_dir="$repo_root/build/yt-dlp-source"
ejs_build_dir="$build_dir/ejs-source"
output_file="$build_dir/yt-dlp"

if [[ ! -f "$source_dir/yt_dlp/version.py" ]] || ! git -C "$source_dir" rev-parse --verify HEAD >/dev/null 2>&1; then
  echo "Initialize the pinned yt-dlp source with: git submodule update --init" >&2
  exit 1
fi

if [[ ! -f "$ejs_source_dir/package-lock.json" ]]; then
  echo "Initialize the pinned JavaScript solver with: git submodule update --init" >&2
  exit 1
fi

pinned_version="$(sed -nE 's/^ytDlpEngine = "([^"]+)"$/\1/p' "$repo_root/gradle/libs.versions.toml")"
source_version="$(sed -nE "s/^__version__ = '([^']+)'$/\1/p" "$source_dir/yt_dlp/version.py")"
if [[ -z "$pinned_version" ]] || [[ "$source_version" != "$pinned_version" ]]; then
  echo "Pinned yt-dlp version $pinned_version does not match submodule version $source_version" >&2
  exit 1
fi

ejs_version="$(sed -nE 's/^ytDlpEjs = "([^"]+)"$/\1/p' "$repo_root/gradle/libs.versions.toml")"
required_ejs_version="$(sed -nE 's/^EJS_VERSION = (.*)$/\1/p' "$source_dir/Makefile")"
if [[ ! "$ejs_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || [[ "$ejs_version" != "$required_ejs_version" ]]; then
  echo "Pinned EJS version $ejs_version does not match yt-dlp's requirement $required_ejs_version" >&2
  exit 1
fi

case "$build_dir" in
  "$repo_root"/build/yt-dlp-source) ;;
  *) echo "Refusing unsafe build directory: $build_dir" >&2; exit 1 ;;
esac
rm -rf -- "$build_dir"
mkdir -p "$build_dir"
git -C "$source_dir" archive --format=tar HEAD | tar -xf - -C "$build_dir"
# Build the solver from pinned source and locked npm dependencies. The plain
# yt-dlp target omits it, even though the official release includes it.
mkdir -p "$ejs_build_dir"
git -C "$ejs_source_dir" archive --format=tar HEAD | tar -xf - -C "$ejs_build_dir"
npm --prefix "$ejs_build_dir" ci --ignore-scripts --no-audit --no-fund
npm --prefix "$ejs_build_dir" run bundle
cp -R "$ejs_build_dir/yt_dlp_ejs" "$build_dir/yt_dlp_ejs"
printf "__version__ = version = '%s'\n" "$ejs_version" > "$build_dir/yt_dlp_ejs/_version.py"
cp "$ejs_build_dir/dist/yt.solver.core.min.js" "$build_dir/yt_dlp_ejs/yt/solver/core.min.js"
cp "$ejs_build_dir/dist/yt.solver.lib.min.js" "$build_dir/yt_dlp_ejs/yt/solver/lib.min.js"
# GNU tar preserves the archive's group-write bit when run as root, while
# non-root extraction applies the umask. Normalize the files that are packed by
# yt-dlp's Makefile so both environments emit the same central-directory modes.
find "$build_dir/yt_dlp" "$build_dir/yt_dlp_ejs" -type f -exec chmod 0644 {} +
# The staged package should satisfy current-ejs-version. Its import failures are
# ignored upstream, so reject any fallback to the prebuilt PyPI wheel.
make -C "$build_dir" lazy-extractors yt-dlp-extra
if [[ -e "$build_dir/.ejs-requirements.txt" ]]; then
  echo "make fetched the upstream EJS wheel; refusing to ship it as a source build" >&2
  exit 1
fi

# --version alone cannot detect the missing-solver release regression.
python3 -I - "$output_file" "$ejs_version" <<'PY'
import importlib.resources
import sys

sys.path.insert(0, sys.argv[1])
import yt_dlp_ejs

assert yt_dlp_ejs.version == sys.argv[2], "Bundled EJS version mismatch"
solver = importlib.resources.files("yt_dlp_ejs.yt.solver")
for name in ("core.min.js", "lib.min.js"):
    assert solver.joinpath(name).read_bytes(), f"Missing EJS script: {name}"
PY

actual_version="$("$output_file" --ignore-config --version)"
if [[ "$actual_version" != "$pinned_version" ]]; then
  echo "Built yt-dlp version $actual_version does not match $pinned_version" >&2
  exit 1
fi

printf 'Built yt-dlp %s from source\n' "$actual_version"
sha256sum "$output_file"
