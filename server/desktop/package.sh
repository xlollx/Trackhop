#!/usr/bin/env bash
# Trackhop Desktop (beta): packs the server build into an app with its own Java runtime.
#
#   gradle -p server installDist && server/desktop/package.sh
#
# On Linux: dist/Trackhop-Desktop-v<version>-linux.deb and -linux.tar.gz (unpack and run
# Trackhop/bin/Trackhop, any distribution). On Windows (Git Bash, WiX 3 on the PATH):
# dist/Trackhop-Desktop-v<version>-windows.msi (per-user install, no administrator) and
# -windows.zip (portable). On macOS: dist/Trackhop-Desktop-v<version>-macos-<arm64|x64>.dmg, unsigned
# (ad-hoc signed only, so it runs on Apple silicon; Gatekeeper asks once, docs/guide/desktop.md).
# Needs a JDK 17 or newer with jpackage. The entry point is
# server/src/main/kotlin/.../Desktop.kt; the guide is docs/guide/desktop.md.
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
version=$(tr -d '[:space:]' < server/VERSION)
built=server/build/install/songport-server/lib
[ -f "$built/songport-server-$version.jar" ] || { echo "Run 'gradle -p server installDist desktopLibs' first" >&2; exit 1; }
work=server/build/desktop
rm -rf "$work" && mkdir -p "$work" dist
name="Trackhop-Desktop-v$version"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) os=windows; icon=server/desktop/trackhop.ico ;;
  Linux) os=linux; icon=server/desktop/trackhop.png ;;
  Darwin) os=macos; icon="$work/trackhop.icns" ;;
  *) echo "Windows, Linux or macOS only" >&2; exit 1 ;;
esac
# The jars, and SWT for this system: Desktop's own window (DesktopWindow.kt), not in the Docker image.
lib="$work/lib"
mkdir -p "$lib" && cp "$built"/*.jar "$lib"/
# Not on macOS: SWT wants the main thread there, which the tray (AWT) holds; Desktop opens its pages
# in the browser instead, with its icon in the menu bar.
if [ "$os" != macos ]; then
  ls server/build/desktop-libs/$os/*.jar > /dev/null 2>&1 || { echo "Run 'gradle -p server desktopLibs' first" >&2; exit 1; }
  cp server/build/desktop-libs/$os/*.jar "$lib"/
fi
appVersion=$version
mac=()
if [ "$os" = macos ]; then
  # The icon as .icns, from the store icon. macOS wants the first number of a version above 0:
  # Trackhop x.y.z is (x+1).y.z there, as in the Microsoft Store's package.
  set=$work/trackhop.iconset; mkdir -p "$set"
  for s in 16 32 128 256 512; do
    sips -z $s $s docs/store/icon512.png --out "$set/icon_${s}x${s}.png" > /dev/null
    if [ $s -lt 512 ]; then sips -z $((s * 2)) $((s * 2)) docs/store/icon512.png --out "$set/icon_${s}x${s}@2x.png" > /dev/null; fi
  done
  iconutil -c icns "$set" -o "$icon"
  IFS=. read -r major minor patch <<< "$version"
  appVersion="$((major + 1)).$minor.$patch"
  mac=(--mac-package-identifier io.github.xlollx.Trackhop --mac-package-name Trackhop)
  arch=$(uname -m); [ "$arch" = x86_64 ] && arch=x64
fi

# What the jars use (jdeps --print-module-deps), plus TLS's elliptic curves, the XML the texts are
# read from, other charsets, and the formats of the app's languages.
modules=java.base,java.desktop,java.instrument,java.logging,java.net.http,java.xml,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata
locales=en,it,fr,de,es,pt,pl,ru,tr,zh,ja,ko

# The app itself: the jars, the launcher (no console window on Windows), the command line's
# launcher trackhop-cli (cli.properties) and a trimmed runtime.
jpackage --type app-image --name Trackhop --dest "$work/out" \
  --input "$lib" --main-jar "songport-server-$version.jar" --main-class com.xlollx.songport.server.DesktopKt \
  --app-version "$appVersion" --vendor xlollx --copyright "AGPL-3.0-only" "${mac[@]}" \
  --description "Trackhop Desktop (beta): keeps playlists in sync between music services" \
  --icon "$icon" --java-options "-Xmx512m" \
  --add-launcher trackhop-cli=server/desktop/cli.properties \
  --add-modules "$modules" \
  --jlink-options "--strip-debug --no-man-pages --no-header-files --include-locales=$locales"

if [ "$os" = windows ]; then
  # PowerShell wants Windows paths, not Git Bash's /d/a/….
  zip=$(cygpath -w "$root/dist/$name-windows.zip")
  (cd "$work/out" && powershell -NoProfile -Command "Compress-Archive -Path Trackhop -DestinationPath '$zip' -Force")
  # The upgrade code stays the same forever: a newer MSI replaces the installed one.
  jpackage --type msi --app-image "$work/out/Trackhop" --name Trackhop --dest "$work" \
    --app-version "$version" --vendor xlollx --description "Trackhop Desktop (beta)" \
    --win-per-user-install --win-menu --win-menu-group Trackhop --win-shortcut \
    --win-upgrade-uuid 5b8f3c1e-7a2d-4e9b-9c61-2f0d8a4b7e13 \
    --about-url https://trackhop.org/guide/desktop
  mv "$work"/Trackhop-*.msi "dist/$name-windows.msi"
elif [ "$os" = macos ]; then
  # Ad-hoc signed (no certificate): Apple silicon runs nothing unsigned. Not notarized.
  codesign --force --deep --sign - "$work/out/Trackhop.app"
  jpackage --type dmg --app-image "$work/out/Trackhop.app" --name Trackhop --dest "$work" \
    --app-version "$appVersion" --vendor xlollx --description "Trackhop Desktop (beta)" \
    --about-url https://trackhop.org/guide/desktop "${mac[@]}"
  mv "$work"/Trackhop-*.dmg "dist/$name-macos-$arch.dmg"
else
  tar -C "$work/out" -czf "dist/$name-linux.tar.gz" Trackhop
  jpackage --type deb --app-image "$work/out/Trackhop" --name Trackhop --dest "$work" \
    --app-version "$version" --vendor xlollx --description "Trackhop Desktop (beta)" \
    --linux-package-name trackhop --linux-shortcut --linux-menu-group "AudioVideo;Audio" \
    --linux-app-category sound \
    --linux-package-deps "libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37" --linux-deb-maintainer "xlollx@users.noreply.github.com" \
    --about-url https://trackhop.org/guide/desktop
  mv "$work"/trackhop_*.deb "dist/$name-linux.deb"
fi
ls -l dist
