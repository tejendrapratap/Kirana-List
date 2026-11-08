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
- Plurals are made in code, per language: "1 चीज़", "3 चीज़ें". A verb after a count of items agrees with चीज़
  (feminine): "1 चीज़ जोड़ दी", "5 चीज़ें जोड़ दीं". बदलाव (change) is the same for one and many; only the verb shows
  the number: "1 बदलाव सेव नहीं हो सका", "3 बदलाव सेव नहीं हो सके".
- A button named inside a sentence is in quotes: "सेव करें" दबाएँ. So is a page's name and the Need word:
  "ख़रीदना है" में, "सारा सामान" में, "चाहिए" कर दें.
- Buttons, and what TalkBack says for them, say what they do, in the polite imperative: कम करें, बढ़ाएँ, वापस लाएँ.
- "Then" inside a sentence is इसके बाद, not फिर, which the Retry button's name (फिर से कोशिश करें) starts with.
- A who / when line without a name puts the time first: "2 घंटे पहले जोड़ा गया".
- Apps Script's own words stay in English, as Apps Script shows them: Deploy > Manage deployments, Web app URL,
  Execute as, SECRET_TOKEN.

## Words

| English | Hindi |
|---|---|
| Kirana List (app bar title) | किराना लिस्ट |
| To buy | ख़रीदना है |
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
| Quantity / Yes or no (item types) | कितना चाहिए / हाँ या नहीं |
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
| change / changes (counted) | बदलाव (1 बदलाव, 3 बदलाव) |
| Discard | छोड़ दें |
| Skipped / left out (a pasted line, an item) | छोड़ दिया / छोड़ दी |
| Set up / Setup code / setup message | सेट अप करें / सेटअप कोड / सेटअप मैसेज |
| Open settings | सेटिंग्स खोलें |
| More options | और विकल्प |
| Group by shop section | सेक्शन के हिसाब से दिखाएँ |
| Item (the name field) / Edit item / Edit amount | सामान / सामान बदलें / मात्रा बदलें |
| e.g. | जैसे |
| Track as (the item type) | कैसे लिखें |
| Need to buy? | ख़रीदना है? |
| less / more (the − and + buttons, TalkBack) | कम करें / बढ़ाएँ (Rice 0.5 kg कम करें; in the dialog 0.5 kg बढ़ाएँ) |
| Quantity 2 kg (TalkBack, an amount) | मात्रा 2 kg |
| Choose (a unit, a section) / Selected (the tick in a menu) | चुनें / चुना गया |
| Select all / Select none | सब चुनें / कोई न चुनें |
| Other... (a unit of your own) / Use (the unit typed) | कोई और... / इस्तेमाल करें |
| This phone only | सिर्फ़ इस फ़ोन पर |
| Similar (item) | मिलता-जुलता |
| is already on the list | पहले से लिस्ट में है |
| changed on another phone | दूसरे फ़ोन पर बदला गया (an item counted as चीज़: बदली गई, थी / थीं) |
| Paste (button) / Next / Edit list | पेस्ट करें / आगे / लिस्ट बदलें |
| line / lines (counted) | लाइन / लाइनें (1 लाइन, 3 लाइनें) |
| characters | अक्षर |
| heading (of a pasted list) / set (in Add common items) | हेडिंग / सेट |
| Couldn't read (a pasted line) | समझ नहीं आया |
| Update 3 items / updated (Paste a list) | 3 चीज़ें अपडेट करें / अपडेट की, अपडेट कीं |
| Apply 5 changes | 5 बदलाव करें |
| Live - updated 3s ago (the list's status line) | लाइव - 3 सेकंड पहले अपडेट हुई (updated less than a minute ago: अभी अपडेट हुई) |
| Not syncing / Not synced yet / Not connected | सिंक नहीं हो रहा / अभी सिंक नहीं हुआ / शीट से नहीं जुड़ा |
| retrying | फिर से कोशिश हो रही है |
| waiting on this phone | इस फ़ोन पर रुकी है / रुकी हैं |
| on 3 Nov / on 3 Nov 2025 | 3 नवंबर को / 3 नवंबर 2025 को |
| months | जनवरी, फ़रवरी, मार्च, अप्रैल, मई, जून, जुलाई, अगस्त, सितंबर, अक्टूबर, नवंबर, दिसंबर |
| Show items / Hide items (Ticked today's header, TalkBack) | सामान दिखाएँ / सामान छिपाएँ |
| Expand / Collapse (TalkBack actions) | खोलें / बंद करें |
| Expanded / Collapsed (TalkBack states) | खुला है / बंद है |
| Back (TalkBack, leave a screen) | वापस जाएँ |
| You / Your name (Settings) | आप / आपका नाम |
| Test connection / Testing connection... | कनेक्शन जाँचें / कनेक्शन जाँच रहे हैं... |
| Web app URL (the field) | वेब ऐप का URL |
| clipboard | क्लिपबोर्ड |
| Version 1.6 | वर्ज़न 1.6 |
| Turn on / Not now | चालू करें / अभी नहीं |
| Notify me | मुझे सूचना दें |
| Allow in Android settings | Android सेटिंग्स में चालू करें |
| A and B / A, B and C / A, B and 2 others | A और B / A, B और C / A, B और 2 अन्य |
| +3 more | +3 और |

"1 घंटे पहले" and "2 घंटे पहले" alike: पहले (ago) takes the same form for one and many.

"Updated by X" is X ने बदला (someone changed the item), while the list's status line ("updated 3s ago") and Paste a
list's "Update" use अपडेट, as people say it of a list.

## Phrases

Snackbars, TalkBack texts and notifications made of the words above. Names and amounts are data.

| English | Hindi |
|---|---|
| Got Rice / Deleted Rice | Rice ले लिया / Rice हटा दिया |
| Got it: Rice (TalkBack) | ले लिया: Rice |
| Rice: 2 kg → 3 kg | Rice: 2 kg → 3 kg (the same: the amounts are data) |
| Dahi: needed / Dahi: not needed | Dahi: चाहिए / Dahi: नहीं चाहिए |
| Renamed Curd to Dahi | Curd का नाम बदलकर Dahi कर दिया |
| Changed Rice | Rice बदल दिया |
| Changed Curd, now called Dahi | Curd बदल दिया, अब इसका नाम Dahi है |
| Ticked today (3) / TalkBack: Ticked today, 3 items | आज लिया (3) / आज लिया, 3 चीज़ें |
| Put back Rice (TalkBack) | Rice वापस लाएँ |
| Tap Add item to add what you need. | जो चाहिए, उसे "सामान जोड़ें" दबाकर जोड़ें। |
| Everything is ticked off. Tap Add item when you need something. | सब ले लिया गया है। कुछ और चाहिए तो "सामान जोड़ें" दबाएँ। |
| Added Dahi - it isn't needed now, so it isn't on To buy | Dahi जोड़ा - यह अभी नहीं चाहिए, इसलिए "ख़रीदना है" में नहीं है |
| Mark Dahi needed / Already needed: Oil (Paste a list) | चाहिए: Dahi / पहले से चाहिए: Oil |
| On the list as Tel | लिस्ट में Tel नाम से है |
| Eggs is in dozen | Eggs की यूनिट dozen है |
| Bigger text and buttons (Simple mode) | बड़े अक्षर और बड़े बटन |
| Checks about every 15 minutes while the app is closed | ऐप बंद होने पर लगभग हर 15 मिनट में लिस्ट जाँचता है |
| Notifications are blocked for this app. | इस ऐप की सूचनाएँ बंद हैं। |
| Notifications aren't on yet for this app. / Turn on notifications | इस ऐप की सूचनाएँ अभी चालू नहीं हैं। / सूचनाएँ चालू करें |
| Type Your name above (like Mummy), so the phones can tell whose change it is. | ऊपर "आपका नाम" भरें (जैसे Mummy), ताकि फ़ोन पहचान सकें कि बदलाव किसका है। |
| Changes to To buy (the notification channel) | "ख़रीदना है" में बदलाव |
| Mummy added 1 item / 3 items to To buy | Mummy ने "ख़रीदना है" में 1 चीज़ जोड़ी / 3 चीज़ें जोड़ीं |
| Mummy changed To buy | Mummy ने "ख़रीदना है" में बदलाव किया |

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
| Other | अन्य |

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
