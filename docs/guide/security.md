---
title: Is Trackhop safe? VirusTotal checks of every download
description: Every file of every Trackhop release, the Android app and Trackhop Desktop for Windows, macOS and Linux, is checked by the antivirus engines of VirusTotal. The results, the files' SHA-256 and how Trackhop is built.
---

# Is Trackhop safe?

Every file of every release, the Android app and Trackhop Desktop, is checked on
[VirusTotal](https://www.virustotal.com) as soon as it is published: the antivirus engines VirusTotal
runs look at it, and the results are linked below and in the release's notes on GitHub.

<div id="vt" class="vt" aria-live="polite">
  <p class="muted">Loading the latest results… If nothing appears, every release's notes on
  <a href="https://github.com/xlollx/Trackhop/releases">GitHub</a> have them too.</p>
</div>

The SHA-256 next to each file is its fingerprint: the file you downloaded is the one checked when
its own SHA-256 is the same (on Windows `certutil -hashfile <file> SHA256`, on macOS and Linux
`shasum -a 256 <file>`).

## How Trackhop is built

- **Open source.** All the code is on [GitHub](https://github.com/xlollx/Trackhop), under the GPL-3.0.
- **Built in public.** Every file of a release is built by GitHub Actions from the code of that
  release's commit, and the build logs are public. Nothing is built on a personal computer.
- **F-Droid builds it again.** F-Droid compiles the Android app from the source on its own servers
  and checks that the result matches the published APK, byte for byte.
- **The Android app's signature**, the same on GitHub, F-Droid and Obtainium (SHA-256 of the signing
  certificate):
  `21:EA:DF:26:8C:72:F8:55:65:96:1A:60:45:BF:4B:E5:AB:AF:25:6D:68:0D:1E:0D:80:8D:93:ED:22:D3:50:32`.
  Android refuses an update signed with any other key.

## Why Windows or macOS may still warn you

Trackhop Desktop is not signed with a paid Microsoft or Apple certificate yet, so Windows SmartScreen
may say it does not know the publisher, and macOS asks you to open it from *Privacy & Security* the
first time ([how](desktop#macos)). That is about who signed the file, not about what is inside it:
the VirusTotal results above are about what is inside. Now and then a single engine flags an
unsigned installer with a generic "unknown program" verdict; the report shows which engine and why,
and the badge then says "checked" instead of "clean".

**The Intel Mac file (`-macos-x64.dmg`) and Microsoft Defender.** Of all the engines, Microsoft's alone
flags this one file, as `Trojan:Script/Wacatac.C!ml`. The `!ml` ending means *machine learning*: a
guess about a program it has never seen, not a virus it recognises. Wacatac is the name it gives to
many such guesses, and new unsigned apps get it often. Every other engine reports the file clean, and
so does every engine for the same app built for Apple silicon, Windows and Linux. The code is public
and each file is built by GitHub Actions from it; the SHA-256 in the table lets you check you have that
very file. The detection is reported to Microsoft as a false positive, and the table updates itself
once it is corrected. A Mac does not run Microsoft Defender unless you installed it.

[All guide pages](./)

<script>
(function () {
  var base = 'https://raw.githubusercontent.com/xlollx/Trackhop/badges/virustotal/';
  var box = document.getElementById('vt');
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return '&#' + c.charCodeAt(0) + ';'; }); }
  // "Trackhop-Desktop-v0.6.1-windows.msi" as "Windows · .msi", the app's files by what they are.
  function short(name) {
    if (/\.apk$/.test(name)) return 'APK';
    if (/\.aab$/.test(name)) return 'Google Play bundle (.aab)';
    var m = name.match(/-v[\d.]+-([a-z]+)(?:-(arm64|x64))?\.(.+)$/);
    if (!m) return name;
    var os = { windows: 'Windows', macos: 'macOS', linux: 'Linux' }[m[1]] || m[1];
    var arch = m[2] ? (m[2] === 'arm64' ? ' Apple silicon' : ' Intel') : '';
    return os + arch + ' · .' + m[3];
  }
  function card(r, title) {
    var when = (r.checked || '').slice(0, 10);
    var rows = r.files.map(function (f) {
      var clean = f.malicious === 0 && f.suspicious === 0;
      return '<tr><td title="' + esc(f.name) + '">' + esc(short(f.name)) + '</td><td><a class="vt-pill ' + (clean ? 'ok' : 'note') + '" href="' + esc(f.link) +
        '">' + (clean ? '✓ Clean' : 'See the report') + '</a>' + (clean || !f.flagged_by ? '' : '<br><small class="muted">' +
        Object.keys(f.flagged_by).map(function (e) { return esc(e) + ': ' + esc(f.flagged_by[e]); }).join(', ') + (Object.keys(f.flagged_by).every(function (e) { return /!ml$/.test(f.flagged_by[e]); }) ? ' (a machine-learning guess)' : '') + '</small>') + '</td><td><code title="' + esc(f.sha256) + '">' +
        esc(f.sha256.slice(0, 12)) + '…</code></td></tr>';
    }).join('');
    return '<div class="vt-card"><h3>' + title + ' ' + esc(r.version) + ' <span class="vt-pill ' + (r.clean ? 'ok' : 'note') + '">' +
      (r.clean ? '✓ Clean' : 'Checked') + '</span></h3><p class="muted">Checked on ' + esc(when) + ' · <a href="' + esc(r.release) +
      '">the release</a></p><div class="vt-scroll"><table><thead><tr><th>File</th><th>VirusTotal</th><th>SHA-256</th></tr></thead><tbody>' +
      rows + '</tbody></table></div></div>';
  }
  function get(name) { return fetch(base + name + '.json', { cache: 'no-store' }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; }); }
  Promise.all([get('app'), get('desktop')]).then(function (rs) {
    var html = (rs[0] ? card(rs[0], 'Android app') : '') + (rs[1] ? card(rs[1], 'Trackhop Desktop') : '');
    if (html) box.innerHTML = html;
  });
})();
</script>
