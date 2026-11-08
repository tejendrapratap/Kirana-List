# Docs

Planning notes for Kirana List (formerly Grocery Sync), kept with the code.

| Doc | What it covers |
|---|---|
| [Feature ideas](feature-ideas.md) | 14 ranked feature ideas with evidence, effort and status, what the app already does, and what was left out and why |
| [Common Indian grocery list](indian-grocery-list.md) | 90 items graded core / common / regional from national survey data, with Hindi names and usual units; the source for the starter list and name matching |
| [Removing the setup](zero-setup.md) | How to drop the Google Sheet + Apps Script setup (recommended: Firebase with an invite code), the migration plan, and how to get the app onto phones (sideloading, Play internal testing, public release), with a fact check of every price and policy |

## Decisions so far (2026-11-08)

1. Ship the five quick wins first, as 1.4. They change only the app, so the sheet's script stays as it is.
2. Then move off Google Sheets to Firebase (Cloud Firestore in Mumbai, anonymous sign-in, invite code) as its own release. The Cloudflare Worker + D1 option is the fallback.
3. Keep sideloading the signed APK for the family for now; move to a Play Console internal testing track when it saves effort.
