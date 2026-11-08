# Feature ideas

Researched on 2026-11-08 from other shared-list apps (Bring!, AnyList, OurGroceries, Listonic, Google Keep, and the list features in BigBasket, Blinkit, Zepto, JioMart and Instamart), from how Indian households shop, and from the current code. Ideas were merged, checked against what the app already does, and ranked by value to a family sharing one list against effort.

**Status** is kept up to date as features ship.

| # | Feature | Effort | Needs a sheet/script change? | Status |
|---|---|---|---|---|
| 1 | [Share the To buy list as text (WhatsApp / copy)](#1-share-the-to-buy-list-as-text-whatsapp-copy) | Small | No | Done in 1.4 |
| 2 | [Starter list of common Indian items + built-in sets (puja, monthly kirana, vrat)](#2-starter-list-of-common-indian-items-built-in-sets-puja-monthly-kirana-vrat) | Medium | No | Done in 1.4 |
| 3 | [Hindi / Hinglish / English name matching in search, plus a 'similar item' warning](#3-hindi-hinglish-english-name-matching-in-search-plus-a-similar-item-warning) | Medium | No | Done in 1.4 |
| 4 | [Paste a list / share a WhatsApp message into the app (bulk add)](#4-paste-a-list-share-a-whatsapp-message-into-the-app-bulk-add) | Medium | No | Done in 1.4 |
| 5 | [Group To buy by shop section (automatic categories)](#5-group-to-buy-by-shop-section-automatic-categories) | Medium | No | Done in 1.4 |
| 6 | [Join the family list without setup (invite link, QR or short code)](#6-join-the-family-list-without-setup-invite-link-qr-or-short-code) | Large | Replaces both | Later |
| 7 | [Notification when someone else changes the list, and an 'I'm going shopping' alert](#7-notification-when-someone-else-changes-the-list-and-an-im-going-shopping-alert) | Medium | Yes | Later |
| 8 | [More Undo (-/+, Need switch, rename, type) and a 'Ticked today' strip](#8-more-undo---need-switch-rename-type-and-a-ticked-today-strip) | Small | No | Later |
| 9 | [Voice add in Hindi / Hinglish](#9-voice-add-in-hindi-hinglish) | Medium | No | Later |
| 10 | ['Buy again' suggestions from purchase history](#10-buy-again-suggestions-from-purchase-history) | Medium | Yes | Later |
| 11 | [Note per item (brand, size)](#11-note-per-item-brand-size) | Large | Yes | Later |
| 12 | [Hindi UI and a simple mode for elders](#12-hindi-ui-and-a-simple-mode-for-elders) | Medium | No | Later |
| 13 | [Home-screen widget (display only)](#13-home-screen-widget-display-only) | Medium | No | Later |
| 14 | [Multiple lists (Kirana, Sabzi, Medical, Diwali)](#14-multiple-lists-kirana-sabzi-medical-diwali) | Large | Yes | Later |

The top five ("quick wins") change only the app: no sheet column, no Code.gs redeploy, no database upgrade. Several of them share one built-in item dictionary (names in English and Hindi, a shop section and a usual unit), built from [the common Indian grocery list](indian-grocery-list.md).

Features marked "Much easier after the backend move" depend on [removing the setup](zero-setup.md).

## 1. Share the To buy list as text (WhatsApp / copy)

**What:** A Share button that turns the To buy list into a clean message ('Atta 10 kg / Dahi / Oil 1 L') and opens the Android share sheet (WhatsApp, SMS), with a Copy option.

**Why:** Indian families still send lists to the kirana or a family member on WhatsApp. One tap replaces retyping, and the same text works for pasting into Instamart, Blinkit or Zepto. A ready 'Going shopping, add anything now' message also gives a free version of the shopping alert.

**Evidence:** Kiranas hold about 91% of grocery sales (Redseer, Jan 2026): https://www.tribuneindia.com/news/business/indias-mass-grocery-continues-to-rely-on-kiranas-report/ ; Instamart built pasting lists from WhatsApp/Notes: https://www.afaqs.com/news/mktg/swiggy-instamart-launches-upload-your-shopping-list-feature-7367790 ; Listonic shares as plain text/WhatsApp: https://help.listonic.com/en/6163646-How-do-I-share-a-list- . The claim that kiranas take WhatsApp orders comes only from a vendor blog that now returns 404.

**What has to change:** App only: ACTION_SEND text/plain with a chooser, plus the clipboard. Uses ListUiState.toBuy and Quantity.displayText(). No Sheet, script or DB change. A later 'Ordered from <shop>' state would need a synced column.

- Effort: Small
- India-specific: yes
- Much easier after the backend move: no
- Status: Done in 1.4 (Share list in the app bar; the message is grouped by shop section like To buy)

## 2. Starter list of common Indian items + built-in sets (puja, monthly kirana, vrat)

**What:** From the empty list or a menu, tick items from a built-in list of common Indian staples, each with a sensible type and unit (Atta kg, Doodh L, Dahi yes/no), plus fixed sets such as 'Diwali puja' and 'Monthly kirana'. Items are added as not needed, and items already on the list are skipped.

**Why:** This builds the family's usual list in a minute instead of typing 80 items. It reuses the answer to question 2. Shared with features 3, 4 and 5, one built-in item dictionary (names, Hindi/English alternatives, category, default unit) powers all four.

**Evidence:** Listonic says about 70% of a list is recurring: https://apps.apple.com/in/app/id331302745 ; Indian guides split monthly staples from fresh items: https://simpleindianrecipes.com/Home/Indian-Monthly-Grocery-Shopping-List.aspx ; Navratri vrat staples: https://slurrp.com/article/8-essential-pantry-items-to-stock-up-for-a-hassle-free-navratri-1728566529054 . The empty-page hint already says 'Tap + to add the things you usually buy'.

**What has to change:** App only: the list ships in the APK, and GroceryRepository.add already refuses duplicates. Effort is S for one fixed starter list and M with several sets and a tick-list screen. Family-editable synced templates with remembered amounts ('Diya 51 pcs') would need new storage, so wait for the backend decision.

- Effort: Medium
- India-specific: yes
- Much easier after the backend move: no
- Status: Done in 1.4 (Add common items, in the ⋮ menu and on the empty list: sets Everyday, Monthly kirana, Diwali puja, Vrat / fasting and All, added to All items as not needed, with Undo; meat, fish and eggs start unticked; offered once the family's list has loaded from the sheet)

## 3. Hindi / Hinglish / English name matching in search, plus a 'similar item' warning

**What:** Searching 'curd' finds Dahi, 'raisins' finds Kishmish and 'pyaaz' or 'प्याज' finds Onion, and adding 'Curd' warns 'Similar: Dahi is on the list' (a warning, not a block).

**Why:** The family already mixes Hindi and English names, so today a search for the other name finds nothing and near-duplicates (Curd vs Dahi) slip past the unique-name rule.

**Evidence:** About 21% of Flipkart searches are code-mixed Hinglish, and misspellings are a named problem: https://ar5iv.labs.arxiv.org/html/2208.03713 ; 98% of Indian internet users consume Indic-language content (IAMAI-Kantar 2024): https://www.business-standard.com/india-news/india-s-internet-users-to-exceed-900-mn-in-2025-driven-by-indic-languages-125011600835_1.html . Code check: ItemSearch has no synonyms or transliteration.

**What has to change:** App only: a built-in table of alternative names used by ItemSearch and the Add dialog's duplicate check. S for search only, M with the warning. Match alternatives at word starts only ('dal' must not hit 'Dalchini'), and keep the server's exact-key unique rule unchanged.

- Effort: Medium
- India-specific: yes
- Much easier after the backend move: no
- Status: Done in 1.4 (search on both pages also matches other names at word starts, and the Add and Edit dialogs warn 'Similar: "Dahi" is already on the list' without blocking Save)

## 4. Paste a list / share a WhatsApp message into the app (bulk add)

**What:** 'Paste list', or Share > Kirana List from WhatsApp, reads lines like 'Rice 2 kg', 'Dahi', 'Maggi 4 pack', then shows a preview of new items, items already on the list that will be marked needed, and lines it could not read. Confirming adds them all as needed.

**Why:** Lists arrive as WhatsApp messages from a parent or the cook, or as a note. This turns one message into many items without retyping.

**Evidence:** A viral request to 'upload a list... makes the cart for me' led to Instamart's 'Say it / Scan it / Write it' pilot (Oct 2024; current status unconfirmed): https://www.freepressjournal.in/business/swiggys-instamart-pilots-shopping-list-feature-after-viral-tweet-could-this-be-a-game-changer-for-digital-grocery-shopping ; JioMart 'type it and get your cart filled': https://www.jio.com/apps/jiomart/

**What has to change:** App only: a line parser built on ListLogic.parseQuantityText and Units.normalize, a preview dialog, and a text/plain share-target intent filter in AndroidManifest. SyncEngine already sends 100 changes per round. The parser must never silently overwrite an amount or change an item's type.

- Effort: Medium
- India-specific: no
- Much easier after the backend move: no
- Status: Done in 1.4 (⋮ > Paste a list, and Share > Grocery Sync from another app, open a preview of what each line does; offered once the family's list has loaded from the sheet)

## 5. Group To buy by shop section (automatic categories)

**What:** To buy is grouped in walking order (Sabzi/fruit, Dairy, Atta-dal-rice, Masala, Oil/ghee, Snacks, Cleaning, Puja, Medical, Other) with collapsible headers, using a built-in item-to-category table. Each phone can correct an item's group.

**Why:** In the shop the list follows the aisles or stalls instead of A to Z, so fewer items are missed. This is the most-requested gap across the competitor reviews.

**Evidence:** Microsoft To Do users work around missing categories with prefixes: https://techcommunity.microsoft.com/discussions/to-do/how-are-you-using-to-do-for-shopping-lists/1819276 ; a Google Keep family user asks for dairy/produce groups: https://justuseapp.com/en/app/1029207872/google-keep-notizen-listen/reviews ; an OurGroceries review asks for aisle sorting: https://apps.apple.com/us/app/id325851015 ; Apple added automatic grocery categories in iOS 17: https://support.apple.com/en-ae/105086

**What has to change:** App only: reuse the item dictionary, group in ListViewModel.buildState, and add stickyHeader in ListScreen. Store per-phone corrections in saved preferences to avoid a DB upgrade. Synced categories or a 'which shop' column would be a Sheet column plus script protocol 4 (L), so leave those until the backend decision. Unknown names fall into 'Other'.

- Effort: Medium
- India-specific: no
- Much easier after the backend move: no
- Status: Done in 1.4 (sticky section headers in walking order with counts, not collapsible; ⋮ > Group by shop section turns it off; the Edit dialog's Shop section is kept on this phone only; Share uses the same sections)

## 6. Join the family list without setup (invite link, QR or short code)

**What:** The first phone creates the list and the others join by scanning a QR code or opening a link: no Google Sheet, no Apps Script, no deploy and no token to paste.

**Why:** Setup is the single biggest barrier today and is exactly the user's question 3. Competitors sell 'no signup' sharing as a feature.

**Evidence:** Listonic shares by link/WhatsApp without signup: https://help.listonic.com/en/6163646-How-do-I-share-a-list- ; GroShare uses a code: https://apps.apple.com/us/app/groshare/id6746351751 ; AnyList needs accounts: https://help.anylist.com/articles/share-list/ . This is evidence of vendor marketing only; there is no independent user survey.

**What has to change:** Replace the Sheet/Apps Script backend (question 3), add a list/household id, an invite flow and a migration of the current Sheet data. Doing a QR of the existing URL plus token is possible but works against the current design (the token is never shown and the app no longer makes setup codes), and it still needs the Sheet set up.

- Effort: Large
- India-specific: no
- Much easier after the backend move: yes
- Status: Later

## 7. Notification when someone else changes the list, and an 'I'm going shopping' alert

**What:** When the app is closed, a notification such as 'Mummy added 3 items: Dahi, Oil, Batti' (can be turned off). The shopper can tap 'Going shopping' so others see 'add anything now' until Done or about 2 hours.

**Why:** Without it the other person must open the app to notice changes, and last-minute items get missed when someone is already at the shop.

**Evidence:** MacRumors users asking for shared-list alerts, with AnyList recommended: https://forums.macrumors.com/threads/shared-list-app-with-notifications.2062137 and https://forums.macrumors.com/threads/reminders-push-notifications-for-shared-lists.2198690 ; Bring! going-shopping message: https://getbring.com/blog-posts/your-activities-at-a-glance ; Android periodic work is at least 15 minutes apart: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest

**What has to change:** On the Sheet: app only for a 15-minute background pull (SyncWorker with pull = true), a notification channel and the POST_NOTIFICATIONS runtime permission (Android 13+). That is delayed 15+ minutes. 'Going shopping' needs a new script action and a Code.gs redeploy. Instant push needs FCM, which is L on Apps Script. A real backend gives instant push for both.

- Effort: Medium
- India-specific: no
- Much easier after the backend move: yes
- Status: Later

## 8. More Undo (-/+, Need switch, rename, type) and a 'Ticked today' strip

**What:** Undo snackbars after -/+ (grouped, 'Rice 2 kg -> 3 kg  Undo'), the Need switch, a rename and a type change. A 'Ticked today' strip on To buy lets you restore an item ticked by mistake after the snackbar is gone.

**Why:** Mis-taps in a busy shop are common. Today only Got it and Delete can be undone.

**Evidence:** OurGroceries reviewer: 'accidentally touching products ... removes them from the list': https://apps.apple.com/us/app/id325851015 ; a Bring! reviewer says a cross-off is hard to undo (search snippet): https://apps.apple.com/app/bring-shopping-list-recipes/id580669177

**What has to change:** App only: extend ListEvent and showReplacingSnackbar. S for the snackbars, M for a 'Ticked today' strip that survives a restart (needs a small local table). Undo needs an 'expected value' check so a late Undo does not overwrite the other phone's newer amount.

- Effort: Small
- India-specific: no
- Much easier after the backend move: no
- Status: Later

## 9. Voice add in Hindi / Hinglish

**What:** A mic button in Add and Search that understands 'do kilo aloo, aadha kilo pyaz, ek packet doodh' (hi-IN) and shows a confirm sheet before saving.

**Why:** This helps elders, the cook with busy hands, and anyone who doesn't type easily. Google Assistant no longer adds to third-party lists, so voice has to be built into the app.

**Evidence:** 1 in 5 Indian internet users use voice (IAMAI-Kantar 2024): https://www.business-standard.com/india-news/india-s-internet-users-to-exceed-900-mn-in-2025-driven-by-indic-languages-125011600835_1.html ; Assistant ended third-party lists on 2023-06-20: https://9to5google.com/2023/05/31/google-assistant-notes-lists-integration/ ; Flipkart Hindi voice grocery assistant (2020): https://voicebot.ai/2020/06/09/walmarts-indian-e-commerce-subsidiary-flipkart-debuts-voice-assistant-for-grocery-shopping-in-hindi-and-english/ . Unconfirmed: offline Hindi voice packs on the family's phones.

**What has to change:** App only: Android speech recognizer with hi-IN, reusing the paste parser (feature 4) and the name table (feature 3). The on-device recognizer exists only from API 31 (minSdk is 26), and online recognition sends audio to Google. Robust multi-item Hinglish parsing grows toward L.

- Effort: Medium
- India-specific: yes
- Much easier after the backend move: no
- Status: Later

## 10. 'Buy again' suggestions from purchase history

**What:** A 'Bought recently' view and gentle suggestions such as 'Milk usually every ~7 days, last bought 8 days ago - Add'. These are suggestions only; nothing is added automatically.

**Why:** It catches staples that ran out before anyone noticed, and it fits the monthly-ration rhythm without having to set up schedules.

**Evidence:** OurGroceries 'common items first': https://ourgroceries.com/user-guide ; Home Assistant request to remember and suggest past items: https://community.home-assistant.io/t/shopping-list-remember-previous-items/627775 ; BigBasket Smart Basket: https://www.bigbasket.com/mobile-app/ . A vendor claim that 70-80% of a monthly list repeats is unsourced.

**What has to change:** M app only: a new local 'purchases' table (simple DB upgrade, new table only) logging Got it taps and items others tick off. It is approximate and kept separately on each phone. A shared version needs a 'bought' reason in the apply request, a Code.gs change and a history query (L on Apps Script, where reading a growing History tab is slow).

- Effort: Medium
- India-specific: no
- Much easier after the backend move: yes
- Status: Later

## 11. Note per item (brand, size)

**What:** A short note such as 'Amul 1 L pouch', 'only Fortune oil' or 'for Diwali', shown under the item on To buy.

**Why:** The shopper gets the right brand or size without a phone call.

**Evidence:** AnyList notes for brand/quantity: https://apps.apple.com/nz/app/anylist/id522167641 ; an OurGroceries reviewer wanting sizes was pointed to its Notes field: https://apps.apple.com/us/app/id325851015 ; JioMart uses 'your preferred brand': https://www.jio.com/apps/jiomart/

**What has to change:** Sheet column plus script protocol 4 (Code.gs fixed 7-column block, migration, History header), DB migration 3->4 for items and outbox, 'missing = keep' semantics so a -/+ tap does not erase a note, a version-check banner, the upgrade test, and the family redeploying Code.gs. There is no useful app-only version. It is M on a new backend, or bundled with other new columns in one protocol-4 release.

- Effort: Large
- India-specific: no
- Much easier after the backend move: yes
- Status: Later

## 12. Hindi UI and a simple mode for elders

**What:** All app text in Hindi (values-hi), layouts checked at 200% font size, and an optional Simple mode: only To buy, a big Add button and the mic, no swiping needed, Undo always visible.

**Why:** Grandparents and parents can use the list on their own phone without help.

**Evidence:** HelpAge India (June 2025): 66% of elders find digital tools too confusing: https://www.outlookmoney.com/news/digital-divide-among-elderly-66-find-digital-tools-too-confusing ; Android 14 font scaling to 200%: https://developer.android.com/about/versions/14/features . There is no direct request from this family, and the evidence is general.

**What has to change:** App only: move all hard-coded UI text into strings.xml (today it holds only app_name), add values-hi, test at 200% font, and build Simple mode as a separate layout. Translation alone is S to M, Simple mode M.

- Effort: Medium
- India-specific: yes
- Much easier after the backend move: no
- Status: Later

## 13. Home-screen widget (display only)

**What:** A widget showing 'To buy (6)' and the first few items. Tapping it opens the app.

**Why:** You can see the list at a glance without opening the app.

**Evidence:** An AnyList reviewer calls quick add from the Watch 'a god send': https://apps.apple.com/nz/app/anylist/id522167641 ; widgets are offered by AnyList, OurGroceries and Bring! (listings). A widget's own update period is at least 30 minutes: https://developer.android.com/develop/ui/views/appwidgets/advanced . The demand evidence is weak.

**What has to change:** App only: Jetpack Glance or RemoteViews, a receiver in the manifest, and a background pull (shares feature 7's 15-minute fetch). Check whether glance-appwidget 1.2.0 works with the project's compileSdk 36 / AGP 9.1.0 pin (unconfirmed); otherwise use RemoteViews. Ticking items on the widget is L.

- Effort: Medium
- India-specific: no
- Much easier after the backend move: no
- Status: Later

## 14. Multiple lists (Kirana, Sabzi, Medical, Diwali)

**What:** Switch between several shared lists, each with its own To buy and All items.

**Why:** Families shop at different places and for occasions, and some users switched apps for per-store lists.

**Evidence:** A Microsoft To Do user moved to OurGroceries for multiple store lists: https://techcommunity.microsoft.com/discussions/to-do/how-are-you-using-to-do-for-shopping-lists/1819276 ; an AnyList couple keeps per-store lists: https://forums.macrumors.com/threads/shared-list-app-with-notifications.2062137 . Categories (feature 5) and sets (feature 2) cover most of the need more cheaply.

**What has to change:** Script protocol change (a list id on every request, one Items sheet per list or a List column) plus a DB migration keying items, outbox and tombstones by list plus name, unique names per list, a list switcher and Settings changes. This carries the highest risk of sync bugs on the Sheet. It is an ordinary 'lists' table on a real backend.

- Effort: Large
- India-specific: no
- Much easier after the backend move: yes
- Status: Later

## Already in the app (1.4)

- Near-real-time sync between phones: about every 5 s while the app is open, edits sent at once (300 ms grouping), pull-to-refresh
- Works offline: local SQLite copy plus an outbox of unsent changes, retries with backoff, and a WorkManager send when the app is closed (no background fetch)
- Two pages, To buy and All items, switched by tab or swipe, each keeping its own scroll position
- Reusable master list: All items with a Need switch or a -/+ stepper; Got it keeps the item (sets 0/false) instead of deleting it
- Two item types, Yes/no or Quantity, switchable; units pcs, kg, g, L, ml, dozen, pack plus any custom unit up to 16 characters (so pao or bunch can already be typed); spelling clean-up (Kgs->kg, pkt->pack, nos->pcs); up to 3 decimals, comma as decimal mark
- Add dialog that remembers the last type, and a snackbar telling you where a new item went
- Duplicate names blocked (ignoring case and spacing), with 'Rice is already on the list' and Open 'Rice'; checked again on save
- Edit dialog: rename, change type, amount or unit, delete with confirmation, and a warning if the other phone changed or deleted the item meanwhile
- Undo after Got it and after Delete
- Offline search on the current page: ignores case, accents and the Devanagari nukta/chandrabindu, matches words in any order, offers 'Show n in All items' and Add '...'
- Who changed what per item ('Added by X - 2 hours ago' / 'Updated by X'), highlight for others' changes in the last 2 minutes, an icon on unsynced rows
- Full change log in the Sheet's History tab (not shown in the app), plus 30-day tombstones so deleted items stay deleted
- Status line (Live / Offline / Sheet busy / Google isn't responding / Sheet error), 'Changes aren't saving' with Retry, a failed-changes banner with Retry/Discard, a banner when the script is out of date
- Settings: phone name, web app URL, write-only secret token, paste legacy setup code, Test connection, help card, version
- The Sheet itself can be edited by hand (a basic web/desktop view)
- Dark theme and dynamic colour on Android 12+, TalkBack labels, layouts that handle large text, HTTPS-only release build, token excluded from backup
- Hindi item names work as free text (family list has Batti, Dahi, Diya, Kishmish)
- A built-in dictionary of about 150 common Indian grocery items (English, Roman Hindi and Devanagari names, a shop section and a usual unit), used by the 1.4 features below
- Share the To buy list as text (WhatsApp bold section headings, or A to Z), with an app bar of Search, Share and a ⋮ menu that holds Settings (1.4)
- Add common items: tick items from built-in sets (Everyday, Monthly kirana, Diwali puja, Vrat / fasting, All); items already on the list under any of their names are skipped (1.4)
- Search by another name ('curd' finds Dahi, 'pyaaz' or 'प्याज' finds Onion, at word starts only) and a 'Similar' warning in the Add and Edit dialogs (1.4)
- Paste a list, or Share > Grocery Sync from another app: a preview of what each line does (add, mark needed, change an amount, skip with the reason) before anything changes; a list shared from the app pastes back as the same items (1.4)
- To buy grouped by shop section in walking order with sticky headers; this phone can turn it off or move an item to another section (1.4)

## Considered and left out

- Local units (pao, bunch, gaddi) as a feature: the app already accepts any custom unit up to 16 characters (Units.kt: 'plus any unit the user types (bunch, box, bottle, ...)'), so the family can type 'pao' or 'bunch' today. Only quick ¼/½/1 kg chips and pao spellings would be new, which is too small to rank; 'gaddi' could not be confirmed as a unit.
- Separate 'Monthly ration vs daily/fresh' tags with usual amounts: merged into the starter list and built-in sets (feature 2), categories (5) and buy-again (10). Its own version needs new Sheet columns (L), and the 70-80% repeat figure is from an unsourced vendor blog.
- Festival templates with date reminders: merged into feature 2 as built-in sets. A hard-coded lunar calendar is a poor idea (dates move and differ by region), and nothing can ship for Diwali 2026, which is today.
- Quick-commerce handoff / 'send to Blinkit or Instamart cart': no public cart API found, so it reduces to the copy-as-text of feature 1. The 'Ordered via' state needs a synced column.
- Sort options (recently added, by person): no user evidence found. Categories (5) give the useful ordering and buy-again (10) gives 'usual items first'. Sorting by changed time makes rows jump under the finger.
- Prices, running total and monthly budget: weak fit. Kirana bills are often not itemised, units don't convert (price per kg vs 500 g), typing prices slows the shop flow, and it is L (synced column). The fit review ranks it below share, paste, starter list, notifications and categories. Evidence is a single OurGroceries reviewer plus listings.
- Cook / house-help restricted role: no survey of domestic workers' phone or WhatsApp use found, and evidence that cooks flag shortages is unverified. It needs server-side roles because the token gives full access. The WhatsApp share-into-app path (feature 4) already covers it.
- Daily milk / doodhwala log: outside a shared grocery list; separate milk apps exist.
- Item photos: listings and one review only, and storing images in a Google Sheet is impractical.
- Assign items to a person / split the shop: one Product Hunt comment; a poor fit for a two-phone household.
- Recipes and meal planning: low fit for staple-driven Indian lists; the useful part (one-tap sets) is in feature 2.
- Pantry / stock at home: the app already covers it, since Need off or 0 in All items means 'we have it'. Evidence is a listing only.
- Recurring items on a fixed schedule ('Milk every 2 days'): auto-adding is annoying and needs a synced column plus a background job or a new script trigger permission. Kept only as suggestions inside feature 10.
- Barcode scanning, deals and loyalty cards: loose kirana items rarely have barcodes, and deals and loyalty cards don't fit Indian kirana shopping.
- Web/desktop app: the Sheet can already be edited by hand. Reconsider after the backend decision.
- Dark mode / themes and Activity per item: already in the app (Theme.kt; 'Added by X - 2 hours ago'). An in-app history screen has no user demand beyond what per-item activity gives, and its data would come with buy-again (10).
- Apple Watch / Wear OS quick add: no fit for this family's Android phones; the evidence was an Apple Watch review.
