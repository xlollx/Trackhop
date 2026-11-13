# Reporting a problem

1. Reproduce it once if you can, so the log is fresh.
2. *Settings → Advanced → Sync log → Report on GitHub*: it opens a new GitHub issue with the diagnostic report
   already filled in. Read it, remove anything you prefer not to share, add one or two sentences on
   what you were doing, and send it.
3. No GitHub account? *Export log*, on the same page, saves the report and the whole log as a text
   file to attach or send. With *Settings → Advanced → Show developer tools* on, *Share technical
   details* gives the report as text.

The report contains the app and Android version, which connectors are connected (never tokens or
passwords), the syncs with their source and target services, the last runs with counts, and the last
log lines. Track and playlist names may appear in the log: remove them if you like.

What makes a report useful:
- what you expected, and what happened instead;
- the sync involved and the time it ran (the log is in chronological order);
- for a wrong match: the source track, the track that was picked, and the right one if you know it.

Security issues: see [SECURITY.md](https://github.com/xlollx/Trackhop/blob/main/SECURITY.md), not a
public issue.

[All guide pages](./)
