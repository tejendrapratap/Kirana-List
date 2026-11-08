# Hindi glossary

The words the app's Hindi texts use (1.6 onwards), so the same thing is called the same on every screen. The texts
themselves are in `android/app/src/main/java/com/grocerysync/app/text/`: one catalogue per area of the app, each with
an English and a Hindi version (see `Texts.kt` for how they are written and read). When a new word is used by several
texts, add it here too.

## How the Hindi is written

- Everyday spoken Hindi in Devanagari, the polite आप form, short.
- The common English loanword where that is what people say: सेटिंग्स, शेयर, लिस्ट, फ़ोन, शीट.
- Nukta forms are kept: फ़ोन, ज़रूरत, सब्ज़ी. (Search and name matching compare text with the nukta folded,
  `SearchText.fold`, so a ज़ typed as ज + ़ still matches.)
- Item names, units (kg, g, L, ml, pcs, dozen, pack and custom units) and amounts are data: never translated.
- The names of the languages in the Language choice are always in their own script: English, हिन्दी.
- Plurals are made in code, per language: "1 चीज़", "3 चीज़ें".

## Words

| English | Hindi |
|---|---|
| Kirana List (app bar title) | किराना लिस्ट |
| To buy | खरीदना है |
| All items | सारा सामान |
| item / items (counted) | चीज़ / चीज़ें (1 चीज़, 3 चीज़ें) |
| Got it (tick off) | ले लिया |
| Undo | पहले जैसा करें |
| Put back (Ticked today) | वापस लाएँ |
| Ticked today | आज लिया |
| Add / Add item | जोड़ें / सामान जोड़ें |
| Save / Cancel / Delete / Edit / OK / Close | सेव करें / रद्द करें / हटाएँ / बदलें / ठीक है / बंद करें |
| Search | खोजें |
| Share | शेयर करें |
| Settings | सेटिंग्स |
| Retry / Try again | फिर से कोशिश करें |
| Needed / Not needed / Need | चाहिए / नहीं चाहिए / चाहिए |
| Quantity / Yes or no (item types) | मात्रा / हाँ या नहीं |
| Unit / Amount | यूनिट / मात्रा |
| Shop section | सेक्शन |
| Added by X / Updated by X | X ने जोड़ा / X ने बदला |
| Added / Updated (no name) | जोड़ा गया / बदला गया |
| just now / 5 min ago / 2 hours ago / yesterday / 3 days ago | अभी / 5 मिनट पहले / 2 घंटे पहले / कल / 3 दिन पहले |
| Live / Offline / Connecting | लाइव / ऑफ़लाइन / जुड़ रहा है |
| sheet / Google Sheet / web app / secret token | शीट / Google शीट / वेब ऐप / सीक्रेट टोकन |
| phone / this phone | फ़ोन / यह फ़ोन |
| notification(s) | सूचना / सूचनाएँ |
| Simple mode | आसान मोड |
| Language / Phone's language | भाषा / फ़ोन की भाषा |
| Add common items | आम सामान जोड़ें |
| Paste a list | लिस्ट पेस्ट करें |

"1 घंटे पहले" and "2 घंटे पहले" alike: पहले (ago) takes the same form for one and many.

## Shop sections

`CommonTexts.sectionName`. The English names (`ShopSection.label`) stay the stable names; only what is shown changes.

| English | Hindi |
|---|---|
| Sabzi & fruit | सब्ज़ी और फल |
| Dairy, eggs & bread | दूध, अंडे और ब्रेड |
| Meat & fish | मीट और मछली |
| Atta, rice & dal | आटा, चावल और दाल |
| Oil & ghee | तेल और घी |
| Masala | मसाले |
| Tea, sugar & salt | चाय, चीनी और नमक |
| Dry fruits & nuts | मेवे |
| Snacks & packaged food | नमकीन और पैक्ड सामान |
| Cleaning & household | सफ़ाई और घर का सामान |
| Personal care | पर्सनल केयर |
| Puja | पूजा |
| Other | बाकी |

## Sets in Add common items

| English | Hindi |
|---|---|
| Everyday | रोज़ का |
| Monthly kirana | महीने का किराना |
| Diwali puja | दिवाली पूजा |
| Vrat / fasting | व्रत |
| All | सब |

## Share

The shared To buy message is written in the phone's language: its title (`CommonTexts.shareTitle`) and the section
headings in WhatsApp bold (`*सब्ज़ी और फल*`). Item names and amounts stay as they are. Paste a list skips the title and
the headings in both languages, so a list shared from a Hindi phone pastes back on an English one and the other way
round.

| English | Hindi |
|---|---|
| Grocery list (1 item) | किराने की लिस्ट (1 चीज़) |
| Grocery list (3 items) | किराने की लिस्ट (3 चीज़ें) |
