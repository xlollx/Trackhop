# Supporters on the site

The site's footer thanks the supporters by name (`docs/_data/supporters.yml`): names only, never
amounts, never anyone who gave privately. New names go straight to the site (no approval: the owner's choice).

- **Ko-fi**: Ko-fi › Settings › API › Webhooks sends each donation to a Cloudflare Worker
  (`worker.js`, free plan), which checks Ko-fi's verification token and, for a public supporter
  only, sends just the name to `.github/workflows/supporters.yml` (repository_dispatch).
  Secrets of the Worker: `KOFI_TOKEN` (Ko-fi's verification token) and `GITHUB_TOKEN` (fine-grained,
  this repository only, Contents: Read and write; made without expiry. If it ever leaks: revoke it on GitHub and paste a new one).
- **GitHub Sponsors**: the same workflow reads the public sponsors once a day.
- **By hand**: `python3 scripts/supporters.py "Name"`, or edit the file.

