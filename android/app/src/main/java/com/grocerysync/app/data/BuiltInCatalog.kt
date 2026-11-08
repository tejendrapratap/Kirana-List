package com.grocerysync.app.data

import com.grocerysync.app.data.CatalogGrade.COMMON
import com.grocerysync.app.data.CatalogGrade.CORE
import com.grocerysync.app.data.CatalogGrade.REGIONAL
import com.grocerysync.app.data.CatalogSet.DIWALI_PUJA
import com.grocerysync.app.data.CatalogSet.MONTHLY_KIRANA
import com.grocerysync.app.data.CatalogSet.VRAT
import com.grocerysync.app.data.Units.DOZEN
import com.grocerysync.app.data.Units.KG
import com.grocerysync.app.data.Units.L
import com.grocerysync.app.data.Units.PACK
import com.grocerysync.app.data.Units.PCS

/**
 * The entries of [ItemCatalog], in walking order (see [ShopSection]).
 *
 * Source: docs/indian-grocery-list.md. All its 90 items are here, with its grades; rows that a family writes as
 * separate items are split (each dal, each whole spice, palak and methi, lauki, tori and kaddu, kabuli and
 * kala chana, the millet attas, coriander powder and seeds, talcum powder and face cream). The other entries
 * are items of the ready-made sets (Diwali puja, vrat) and everyday items the doc left out (butter, rusk,
 * ketchup, sabudana, ...); they are graded regional, so Everyday stays the doc's core and common items.
 *
 * Each line: the display name, the unit to add it with ([YES_NO] for yes/no), the grade, the other names shown
 * next to it, the further names that match (both comma-separated) and the sets it is in besides Everyday and
 * All. Units are never g or ml, where a - / + step of 100 rarely fits: spices and packets are "pack", greens
 * "pcs" (one bunch), and items bought by eye (green chilli, ginger, garlic, dahi) are yes/no.
 *
 * Every core and common item has a Devanagari name. Brand names are names only where families use them for
 * the thing itself (Maggi, Colgate, Vim, Harpic, Surf, ...). Two things a family buys separately never share a
 * name: a split dal is named only as a dal ("Moong", "Urad" and "Masoor" alone are the whole pulses, which have
 * no entry but sort with the dals, see [ItemCatalog.sectionFor]), and kheel and batashe are two entries.
 */
internal object BuiltInCatalog {

    val entries: List<CatalogEntry> = Table().apply {
        section(ShopSection.SABZI_FRUIT)
        item("Onion", KG, CORE, "Pyaaz, प्याज़", "Pyaz, Pyaj, Piyaz, Piyaj, Kanda, कांदा")
        item("Potato", KG, CORE, "Aloo, आलू", "Alu, Aaloo, Batata, बटाटा", VRAT)
        item("Tomato", KG, COMMON, "Tamatar, टमाटर", "Tamaatar")
        item(
            "Hari mirch", YES_NO, CORE, "Green chilli, हरी मिर्च",
            "Green chili, Hari mirchi, Mirchi, Mirch, Chilli, Chili, हरी मिर्ची",
            VRAT,
        )
        item("Garlic", YES_NO, CORE, "Lahsun, लहसुन", "Lehsun, Lasun, Lassan, Lahasun")
        item("Ginger", YES_NO, COMMON, "Adrak, अदरक", "Adrakh")
        item(
            "Hara dhaniya", PCS, COMMON, "Coriander, हरा धनिया",
            "Dhaniya, Dhania, Hara dhania, Coriander leaves, Cilantro, Dhaniya patta, Kothmir, Kothimbir, धनिया",
        )
        item("Palak", PCS, COMMON, "Spinach, पालक", "Paalak")
        item("Methi", PCS, COMMON, "Fenugreek leaves, मेथी", "Methi leaves, Methi saag, Methi patta")
        item("Sarson ka saag", PCS, REGIONAL, "Mustard greens, सरसों का साग", "Saag, Sarson saag, Sarso ka saag")
        item("Baingan", KG, COMMON, "Brinjal, बैंगन", "Baigan, Bengan, Begun, Eggplant, Aubergine")
        item("Lemon", PCS, COMMON, "Nimbu, नींबू", "Neembu, Nimboo, Limbu, Lime, निम्बू", VRAT)
        item("Bhindi", KG, COMMON, "Okra, भिंडी", "Ladyfinger, Lady finger, Ladies finger, Bhendi")
        item(
            "Cauliflower", PCS, COMMON, "Phool gobhi, फूल गोभी",
            "Gobhi, Gobi, Phool gobi, Phoolgobhi, Gobhi phool, गोभी",
        )
        item(
            "Cabbage", PCS, COMMON, "Patta gobhi, पत्ता गोभी",
            "Patta gobi, Band gobhi, Bandh gobhi, Bandgobhi, बंद गोभी",
        )
        item("Lauki", PCS, COMMON, "Bottle gourd, लौकी", "Ghiya, Ghia, Doodhi, Dudhi, Louki, घिया", VRAT)
        item("Tori", KG, COMMON, "Ridge gourd, तोरी", "Torai, Turai, Toori, Turiya, Sponge gourd, तोरई")
        item("Kaddu", KG, COMMON, "Pumpkin, कद्दू", "Kaddoo, Kadu, Halwa kaddu, Kashiphal", VRAT)
        item("Beans", KG, COMMON, "Sem, बीन्स", "French beans, Green beans, Sem phali, सेम")
        item("Carrot", KG, COMMON, "Gajar, गाजर", "Gaajar")
        item("Matar", KG, COMMON, "Green peas, मटर", "Peas, Mutter, Mattar, Hari matar")
        item(
            "Kadi patta", PCS, REGIONAL, "Curry leaves, कढ़ी पत्ता",
            "Curry leaf, Curry patta, Kari patta, Kadhi patta, Kadi patti, Meetha neem, करी पत्ता, मीठा नीम",
        )
        item("Nariyal", PCS, REGIONAL, "Coconut, नारियल", "Naariyal, Narial", DIWALI_PUJA)
        item("Pudina", PCS, REGIONAL, "Mint, पुदीना", "Podina, Pudeena, Phudina, Mint leaves")
        item("Mooli", PCS, REGIONAL, "Radish, मूली", "Muli")
        item("Cucumber", KG, REGIONAL, "Kheera, खीरा", "Khira, Kakdi, ककड़ी", VRAT)
        item("Capsicum", KG, REGIONAL, "Shimla mirch, शिमला मिर्च", "Shimla mirchi, Bell pepper, Green capsicum")
        item("Karela", KG, REGIONAL, "Bitter gourd, करेला", "Karele, Bitter melon")
        item("Arbi", KG, REGIONAL, "Colocasia, अरबी", "Arvi, Arabi, Taro, Ghuiyan, घुइयाँ", VRAT)
        item("Shakarkandi", KG, REGIONAL, "Sweet potato, शकरकंदी", "Shakarkand, Sakarkandi, शकरकंद", VRAT)
        item("Banana", DOZEN, COMMON, "Kela, केला", "Kele, केले", VRAT)
        item("Mango", KG, COMMON, "Aam, आम", "")
        item("Apple", KG, REGIONAL, "Seb, सेब", "Saib", VRAT)
        item("Orange", KG, REGIONAL, "Santra, संतरा", "Santara, Narangi, Kinnow, Kinoo")
        item("Grapes", KG, REGIONAL, "Angoor, अंगूर", "Angur")
        item("Pomegranate", KG, REGIONAL, "Anar, अनार", "Anaar")

        section(ShopSection.DAIRY_EGGS_BREAD)
        item("Milk", L, COMMON, "Doodh, दूध", "Dudh, Dood", VRAT)
        item("Dahi", YES_NO, REGIONAL, "Curd, दही", "Curds, Yogurt, Yoghurt, Dahee", VRAT)
        item("Paneer", PACK, REGIONAL, "Cottage cheese, पनीर", "Panir", VRAT)
        item("Butter", PACK, REGIONAL, "Makhan, मक्खन", "Makkhan, Maska")
        item("Cheese", PACK, REGIONAL, "चीज़", "Cheese slices, Cheese cubes")
        optIn { item("Eggs", DOZEN, COMMON, "Ande, अंडे", "Anda, Anday, अंडा") }
        item("Bread", PACK, COMMON, "Double roti, ब्रेड", "Pav, Pao, डबल रोटी, पाव")
        item("Rusk", PACK, REGIONAL, "Toast, रस्क", "Cake rusk")

        section(ShopSection.MEAT_FISH)
        optIn {
            item("Chicken", KG, COMMON, "Murga, चिकन", "Murgh, Murgi, मुर्गा")
            item("Fish", KG, REGIONAL, "Machhli, मछली", "Machli, Machhi")
            item("Mutton", KG, REGIONAL, "Bakre ka gosht, मटन", "Gosht, Goat meat")
        }

        section(ShopSection.ATTA_RICE_DAL)
        item("Rice", KG, CORE, "Chawal, चावल", "Chaval", MONTHLY_KIRANA)
        item(
            "Atta", KG, CORE, "Wheat flour, आटा",
            "Aata, Gehun ka atta, Gehu atta, Wheat atta, Chakki atta, गेहूं का आटा",
            MONTHLY_KIRANA,
        )
        item("Suji", KG, COMMON, "Rava, सूजी", "Sooji, Semolina, Rawa, Bombay rava, रवा", MONTHLY_KIRANA)
        item("Besan", KG, COMMON, "Gram flour, बेसन", "Chickpea flour, Chana atta", MONTHLY_KIRANA)
        item(
            "Maida", KG, REGIONAL, "Refined flour, मैदा",
            "All purpose flour, Plain flour, Refined wheat flour",
            MONTHLY_KIRANA,
        )
        item(
            "Poha", KG, REGIONAL, "Flattened rice, पोहा",
            "Chura, Chuda, Beaten rice, Aval, चूड़ा, चिउड़ा",
            MONTHLY_KIRANA,
        )
        item("Sewai", PACK, REGIONAL, "Vermicelli, सेवई", "Sewaiyan, Seviyan, Sevai, Semiya, सेवइयाँ")
        item("Jowar atta", KG, REGIONAL, "Jowar flour, ज्वार का आटा", "Jowar, Jawar atta, Jowar ka atta, Sorghum flour")
        item("Bajra atta", KG, REGIONAL, "Bajra flour, बाजरे का आटा", "Bajra, Bajre ka atta, Pearl millet flour, बाजरा")
        item(
            "Ragi atta", KG, REGIONAL, "Ragi flour, रागी आटा",
            "Ragi, Nachni, Nachni atta, Mandua, Finger millet flour",
        )
        item(
            "Kuttu atta", KG, REGIONAL, "Buckwheat flour, कुट्टू का आटा",
            "Kuttu, Kuttu ka atta, Kootu atta, Buckwheat",
            VRAT,
        )
        item(
            "Singhara atta", KG, REGIONAL, "Water chestnut flour, सिंघाड़े का आटा",
            "Singhare ka atta, Singhade ka atta, Singada atta",
            VRAT,
        )
        item(
            "Samak chawal", KG, REGIONAL, "Sama ke chawal, समा के चावल",
            "Samak, Samak ke chawal, Samvat chawal, Sanwa, Bhagar, Vrat ke chawal, Barnyard millet",
            VRAT,
        )
        item("Sabudana", KG, REGIONAL, "Sago, साबूदाना", "Sabudane, Saboodana, Sabu dana, Tapioca pearls", VRAT)
        item("Dal", KG, CORE, "Lentils, दाल", "Daal, Dhal, Pulses", MONTHLY_KIRANA)
        item(
            "Toor dal", KG, COMMON, "Arhar dal, तूर दाल",
            "Toor, Arhar, Tur dal, Tuar dal, Tuvar dal, Toovar dal, Toor daal, Arhar daal, Pigeon pea, " +
                "Split pigeon pea, अरहर दाल",
            MONTHLY_KIRANA,
        )
        item(
            "Moong dal", KG, COMMON, "Green gram, मूंग दाल",
            "Mung dal, Moong daal, Split green gram",
            MONTHLY_KIRANA,
        )
        item(
            "Chana dal", KG, COMMON, "Bengal gram, चना दाल",
            "Chana daal, Channa dal, Split chickpea, Split bengal gram",
            MONTHLY_KIRANA,
        )
        item(
            "Masoor dal", KG, COMMON, "Red lentil, मसूर दाल",
            "Masur dal, Masoor daal",
            MONTHLY_KIRANA,
        )
        item(
            "Urad dal", KG, COMMON, "Black gram, उड़द दाल",
            "Urd dal, Udad dal, Urad daal, Split black gram",
            MONTHLY_KIRANA,
        )
        item(
            "Kabuli chana", KG, COMMON, "Chole, काबुली चना",
            "Chickpeas, Chhole, Kabuli chane, Safed chana, White chana, Garbanzo, छोले",
            MONTHLY_KIRANA,
        )
        item(
            "Kala chana", KG, COMMON, "Black chana, काला चना",
            "Chana, Chane, Kale chane, Black chickpeas, Desi chana, चना",
            MONTHLY_KIRANA,
        )
        item("Rajma", KG, REGIONAL, "Kidney beans, राजमा", "Rajmah, Red kidney beans", MONTHLY_KIRANA)

        section(ShopSection.OIL_GHEE)
        item(
            "Oil", L, CORE, "Cooking oil, तेल",
            "Tel, Khane ka tel, Refined oil, Refined, Edible oil, Sunflower oil, Soyabean oil, Soybean oil, " +
                "Groundnut oil",
            MONTHLY_KIRANA,
        )
        item("Ghee", L, COMMON, "घी", "Desi ghee, Ghi, Cow ghee", MONTHLY_KIRANA, DIWALI_PUJA, VRAT)
        item(
            "Sarson ka tel", L, REGIONAL, "Mustard oil, सरसों का तेल",
            "Sarson tel, Sarso ka tel, Kachi ghani",
            DIWALI_PUJA,
        )

        section(ShopSection.MASALA)
        item("Haldi", PACK, CORE, "Turmeric, हल्दी", "Haldee, Haldi powder, Turmeric powder", MONTHLY_KIRANA)
        item(
            "Lal mirch", PACK, CORE, "Red chilli powder, लाल मिर्च",
            "Red chilli, Red chili, Red chili powder, Chilli powder, Chili powder, Lal mirchi, Lal mirch powder, " +
                "Mirch powder, Mirchi powder",
            MONTHLY_KIRANA,
        )
        item("Jeera", PACK, CORE, "Cumin, जीरा", "Zeera, Jira, Cumin seeds", MONTHLY_KIRANA, VRAT)
        item("Dhaniya powder", PACK, COMMON, "Coriander powder, धनिया पाउडर", "Dhania powder", MONTHLY_KIRANA)
        item(
            "Sabut dhaniya", PACK, COMMON, "Coriander seeds, साबुत धनिया",
            "Sabut dhania, Dhaniya seeds, Dhania seeds",
            MONTHLY_KIRANA,
        )
        item("Rai", PACK, COMMON, "Mustard seeds, राई", "Sarson, Rai dana, सरसों", MONTHLY_KIRANA)
        item("Garam masala", PACK, COMMON, "गरम मसाला", "Garam masala powder", MONTHLY_KIRANA)
        item("Hing", PACK, COMMON, "Asafoetida, हींग", "Heeng", MONTHLY_KIRANA)
        item(
            "Elaichi", PACK, COMMON, "Cardamom, इलायची",
            "Ilaichi, Elachi, Illaichi, Green cardamom, Choti elaichi",
            MONTHLY_KIRANA, DIWALI_PUJA,
        )
        item(
            "Kali mirch", PACK, COMMON, "Black pepper, काली मिर्च",
            "Pepper, Kaali mirch, Kali mirchi",
            MONTHLY_KIRANA, VRAT,
        )
        item("Laung", PACK, COMMON, "Cloves, लौंग", "Long, Lavang", MONTHLY_KIRANA, DIWALI_PUJA)
        item("Dalchini", PACK, COMMON, "Cinnamon, दालचीनी", "Dalcheeni", MONTHLY_KIRANA)
        item(
            "Tej patta", PACK, COMMON, "Bay leaf, तेज पत्ता",
            "Bay leaves, Tejpatta, Tej pata, Tej patti",
            MONTHLY_KIRANA,
        )
        item("Imli", PACK, REGIONAL, "Tamarind, इमली", "Imlee")
        item(
            "Sambar masala", PACK, REGIONAL, "Sambhar masala, सांभर मसाला",
            "Sambar powder, Curry powder, Curry masala",
        )
        item("Saunf", PACK, REGIONAL, "Fennel, सौंफ", "Sounf, Fennel seeds, Variyali")
        item("Ajwain", PACK, REGIONAL, "Carom seeds, अजवाइन", "Ajvain, Ajowan, Owa")
        item("Methi dana", PACK, REGIONAL, "Fenugreek seeds, मेथी दाना", "Methi seeds, Methi daana, Methidana")
        item("Kasuri methi", PACK, REGIONAL, "Dried fenugreek leaves, कसूरी मेथी", "Kasoori methi, Kasuri")
        item("Amchur", PACK, REGIONAL, "Dry mango powder, अमचूर", "Amchoor, Aamchur, Mango powder")
        item("Chaat masala", PACK, REGIONAL, "चाट मसाला", "Chat masala")

        section(ShopSection.TEA_SUGAR_SALT)
        item("Salt", KG, CORE, "Namak, नमक", "Table salt, Iodised salt, Iodized salt", MONTHLY_KIRANA)
        item("Sugar", KG, CORE, "Cheeni, चीनी", "Chini", MONTHLY_KIRANA)
        item("Gud", KG, COMMON, "Jaggery, गुड़", "Gur, Goor", MONTHLY_KIRANA)
        item(
            "Chai patti", PACK, CORE, "Tea, चाय पत्ती",
            "Chai, Chaipatti, Chai ki patti, Tea leaves, Tea powder, Tea dust, चाय",
            MONTHLY_KIRANA,
        )
        item("Coffee", PACK, REGIONAL, "कॉफ़ी", "Coffee powder, Filter coffee, Kaapi", MONTHLY_KIRANA)
        item("Sendha namak", KG, REGIONAL, "Rock salt, सेंधा नमक", "Sendha, Vrat ka namak, Pink salt", VRAT)
        item("Kala namak", PACK, REGIONAL, "Black salt, काला नमक", "Kaala namak, Sanchal")
        item("Honey", PCS, REGIONAL, "Shahad, शहद", "Shehad, Madhu")

        section(ShopSection.DRY_FRUITS_NUTS)
        item("Moongphali", KG, COMMON, "Peanuts, मूंगफली", "Mungfali, Moongfali, Groundnuts, Singdana, Shengdana", VRAT)
        item("Kishmish", PACK, REGIONAL, "Raisins, किशमिश", "Kismis, Kishmis, Kismish", VRAT)
        item("Kaju", PACK, REGIONAL, "Cashews, काजू", "Kaaju, Cashew nuts", VRAT)
        item("Badam", PACK, REGIONAL, "Almonds, बादाम", "Baadam", VRAT)
        item("Khajur", PACK, REGIONAL, "Dates, खजूर", "Khajoor", VRAT)
        item("Makhana", PACK, REGIONAL, "Fox nuts, मखाना", "Makhane, Phool makhana, Foxnut, Lotus seeds", VRAT)

        section(ShopSection.SNACKS_PACKAGED)
        item("Biscuits", PACK, COMMON, "बिस्कुट", "Biscut, Biskut, Cookies")
        item("Namkeen", PACK, COMMON, "Bhujia, नमकीन", "Bhujiya, Mixture, Sev, Chivda, भुजिया")
        item("Papad", PACK, REGIONAL, "Papadum, पापड़", "Papadam, Appalam")
        item("Achaar", PACK, REGIONAL, "Pickle, अचार", "Achar, Aachar")
        item("Noodles", PACK, REGIONAL, "Maggi, नूडल्स", "Instant noodles, Maggie, मैगी")
        item("Ketchup", PCS, REGIONAL, "Sauce, केचप", "Ketchap")
        item("Oats", PACK, REGIONAL, "ओट्स", "Rolled oats")
        item("Chips", PACK, REGIONAL, "Wafers, चिप्स", "")
        item("Mithai", KG, REGIONAL, "Sweets, मिठाई", "Mithaai", DIWALI_PUJA)

        section(ShopSection.CLEANING_HOUSEHOLD)
        item(
            "Detergent", PACK, CORE, "Surf, Washing powder",
            "Detergent powder, Detergent bar, Washing soap, Kapde dhone ka sabun, Kapde ka sabun, " +
                "Kapde dhone ka powder, Surf excel, डिटर्जेंट, कपड़े धोने का साबुन",
            MONTHLY_KIRANA,
        )
        item(
            "Dishwash", PCS, COMMON, "Vim, Bartan ka sabun",
            "Dish wash, Dishwash bar, Dishwash liquid, Bartan dhone ka sabun, Bartan sabun, Vim bar, Vim liquid, " +
                "बर्तन धोने का साबुन",
            MONTHLY_KIRANA,
        )
        item(
            "Scrubber", PCS, COMMON, "Juna, जूना",
            "Scrub pad, Steel wool, Steel scrubber, Scotch brite, Joona",
            MONTHLY_KIRANA,
        )
        item(
            "Floor cleaner", L, COMMON, "Phenyl, फिनाइल",
            "Phenyle, Pocha liquid, Lizol, Floor cleaning liquid",
            MONTHLY_KIRANA,
        )
        item("Toilet cleaner", PCS, REGIONAL, "Harpic", "Toilet cleaning liquid", MONTHLY_KIRANA)
        item(
            "Mosquito repellent", PACK, COMMON, "Machhar coil, Good knight",
            "Mosquito coil, Mosquito liquid, Machhar liquid, Goodknight, Good night, All out, Allout, Mortein, " +
                "मच्छर कॉइल",
            MONTHLY_KIRANA,
        )
        item(
            "Garbage bags", PACK, REGIONAL, "Dustbin bags, Kachra thaili",
            "Trash bags, Kachre ki thaili, Kooda bag",
            MONTHLY_KIRANA,
        )
        item(
            "Machis", PACK, CORE, "Matchbox, माचिस",
            "Matches, Match box, Maachis, Machis ki dibbi",
            MONTHLY_KIRANA, DIWALI_PUJA,
        )
        item("Jhadu", PCS, REGIONAL, "Broom, झाड़ू", "Jhaadu, Jharu, Jhaddu")

        section(ShopSection.PERSONAL_CARE)
        item(
            "Bath soap", PCS, CORE, "Sabun, साबुन",
            "Soap, Nahane ka sabun, Bathing soap, नहाने का साबुन",
            MONTHLY_KIRANA,
        )
        item(
            "Toothpaste", PCS, CORE, "Colgate, टूथपेस्ट",
            "Paste, Tooth paste, Manjan, Dant manjan, मंजन",
            MONTHLY_KIRANA,
        )
        item("Toothbrush", PCS, CORE, "Brush, टूथब्रश", "Tooth brush")
        item(
            "Hair oil", PCS, CORE, "Baalon ka tel, बालों का तेल",
            "Balon ka tel, Sir ka tel, Sar ka tel, Parachute",
            MONTHLY_KIRANA,
        )
        item("Shampoo", PCS, CORE, "शैम्पू", "Shampu, Shampoo sachet", MONTHLY_KIRANA)
        item("Talcum powder", PCS, COMMON, "Powder, पाउडर", "Talc, Talcum, Body powder")
        item("Face cream", PCS, COMMON, "Cream, क्रीम", "Cold cream, Moisturiser, Moisturizer")
        item("Blade", PACK, COMMON, "Razor, ब्लेड", "Shaving blade, Razor blade, Gillette")
        item("Sanitary pads", PACK, COMMON, "Pads, सैनिटरी पैड", "Sanitary napkins, Whisper, Stayfree", MONTHLY_KIRANA)

        section(ShopSection.PUJA)
        item(
            "Agarbatti", PACK, COMMON, "Incense sticks, अगरबत्ती",
            "Agarbathi, Agar batti, Incense",
            MONTHLY_KIRANA, DIWALI_PUJA,
        )
        item(
            "Dhoop", PACK, REGIONAL, "Dhoop batti, धूप",
            "Dhoopbatti, Dhup, Dhoop sticks, Dhoop cones, Sambrani",
            DIWALI_PUJA,
        )
        item("Kapoor", PACK, COMMON, "Camphor, कपूर", "Kapur, Karpoor, Karpur", MONTHLY_KIRANA, DIWALI_PUJA)
        item(
            "Rui batti", PACK, COMMON, "Cotton wicks, रुई बत्ती",
            "Batti, Diya batti, Rui ki batti, Cotton wick",
            MONTHLY_KIRANA, DIWALI_PUJA,
        )
        item(
            "Diya", PCS, REGIONAL, "Clay lamps, दीया",
            "Diye, Deeya, Clay diya, Mitti ke diye, Deepak, दीये, दिया",
            DIWALI_PUJA,
        )
        item("Candles", PACK, REGIONAL, "Mombatti, मोमबत्ती", "Mom batti, Mombati", DIWALI_PUJA)
        item("Roli", PACK, REGIONAL, "Kumkum, रोली", "Kumkum powder, Kumkuma, Kanku, कुमकुम", DIWALI_PUJA)
        item("Kalava", PACK, REGIONAL, "Mauli, कलावा", "Moli, Mouli, Kalawa, Raksha sutra, मौली", DIWALI_PUJA)
        item("Sindoor", PACK, REGIONAL, "सिंदूर", "Sindur, Sindhoor, Vermilion", DIWALI_PUJA)
        item("Supari", PACK, REGIONAL, "Betel nut, सुपारी", "Supaari, Areca nut", DIWALI_PUJA)
        item(
            "Paan patta", PCS, REGIONAL, "Betel leaves, पान का पत्ता",
            "Paan, Pan patta, Paan ke patte, Pan ke patte, Betel leaf, पान",
            DIWALI_PUJA,
        )
        item(
            "Genda phool mala", PCS, REGIONAL, "Marigold garland, गेंदे की माला",
            "Genda mala, Genda phool, Genda, Phool mala, Marigold, Garland, फूल माला",
            DIWALI_PUJA,
        )
        // Two things, often two lines; "Kheel batashe" on one line is the kheel (Batashe can still be added).
        item(
            "Kheel", PACK, REGIONAL, "Khil, खील",
            "Kheel batashe, Khil batashe, Kheel batasha, खील बताशे",
            DIWALI_PUJA,
        )
        item("Batashe", PACK, REGIONAL, "Batasha, बताशे", "Batase, बताशा", DIWALI_PUJA)
        item(
            "Rangoli colours", PACK, REGIONAL, "Rangoli, रंगोली",
            "Rangoli colors, Rangoli powder, Rangoli rang",
            DIWALI_PUJA,
        )
    }.entries
}

/** Yes/no instead of a unit. */
private val YES_NO: String? = null

/** Builds the entries section by section (see [BuiltInCatalog] for the line format). */
private class Table {
    val entries = ArrayList<CatalogEntry>()
    private var section = ShopSection.OTHER
    private var optingIn = false

    /** The entries that follow are in [section]. */
    fun section(section: ShopSection) {
        this.section = section
    }

    /** The entries [block] adds start unticked in "Add common items" ([CatalogEntry.optIn]: meat, fish, eggs). */
    fun optIn(block: Table.() -> Unit) {
        optingIn = true
        block()
        optingIn = false
    }

    /**
     * @param unit a standard unit, or [YES_NO]
     * @param shown the other names shown next to it, comma-separated
     * @param more further names that match, comma-separated
     * @param sets the sets it is in besides Everyday (core and common entries) and All
     */
    fun item(name: String, unit: String?, grade: CatalogGrade, shown: String, more: String, vararg sets: CatalogSet) {
        val allSets = sets.toMutableSet()
        allSets += CatalogSet.ALL
        if (grade != CatalogGrade.REGIONAL) allSets += CatalogSet.EVERYDAY
        entries += CatalogEntry(
            displayName = name,
            otherNames = names(shown),
            moreNames = names(more),
            section = section,
            type = if (unit == null) ValueType.BOOLEAN else ValueType.QUANTITY,
            unit = unit.orEmpty(),
            grade = grade,
            sets = allSets,
            optIn = optingIn,
        )
    }

    private fun names(list: String): List<String> =
        if (list.isBlank()) emptyList() else list.split(',').map(String::trim)
}
