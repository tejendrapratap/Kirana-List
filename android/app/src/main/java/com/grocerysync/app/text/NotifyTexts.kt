package com.grocerysync.app.text

/**
 * Change notifications: the notification that says what someone else put on To buy while the app was closed, the
 * name of its channel (Android lists it in the app's notification settings) and the offer card on the list screen.
 * Names of people and items, and amounts, are data: they go into these texts as they are.
 */
interface NotifyTexts {
    /** The notification channel's name in Android's settings for the app's notifications. */
    val channelName: String

    /** Under [channelName] there: when these notifications come. */
    val channelDescription: String

    /**
     * Who made the changes, for the title: "Mummy", "Mummy and Shop", "Mummy, Shop and Papa", and with more than
     * three people "Mummy, Shop and 2 others". [names] as the sheet has them, in order, never empty.
     */
    fun people(names: List<String>): String

    /** The title when at least one item is new on To buy: "Mummy added 3 items to To buy" ([people] from [people]). */
    fun addedTitle(people: String, n: Int): String

    /** The title when only amounts of items already on To buy changed: "Mummy changed To buy". */
    fun changedTitle(people: String): String

    /** After the first names in the notification's text: "+3 more". */
    fun more(n: Int): String

    /** The list screen's one-time offer: the question. */
    val offerQuestion: String

    /** The offer's button that turns notifications on (it asks Android for the permission). */
    val turnOn: String

    /** The offer's button that answers no and hides the card. */
    val notNow: String
}

object EnglishNotifyTexts : NotifyTexts {
    private val toBuy = EnglishCommonTexts.toBuy

    override val channelName = "Changes to $toBuy"
    override val channelDescription = "When someone else adds to $toBuy while the app is closed"

    override fun people(names: List<String>) = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        3 -> "${names[0]}, ${names[1]} and ${names[2]}"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} others"
    }

    override fun addedTitle(people: String, n: Int) = "$people added ${EnglishCommonTexts.itemCount(n)} to $toBuy"
    override fun changedTitle(people: String) = "$people changed $toBuy"
    override fun more(n: Int) = "+$n more"
    override val offerQuestion = "Get a notification when someone adds to $toBuy?"
    override val turnOn = "Turn on"
    override val notNow = "Not now"
}

object HindiNotifyTexts : NotifyTexts {
    /** The page's name inside a sentence is quoted, like a button's: "ख़रीदना है" में. */
    private val toBuy = "\"${HindiCommonTexts.toBuy}\""

    override val channelName = "$toBuy में बदलाव"
    override val channelDescription = "जब ऐप बंद हो और कोई और $toBuy में कुछ जोड़े"

    override fun people(names: List<String>) = when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} और ${names[1]}"
        3 -> "${names[0]}, ${names[1]} और ${names[2]}"
        else -> "${names[0]}, ${names[1]} और ${names.size - 2} अन्य"
    }

    // With ने the verb agrees with what was added, चीज़ (feminine): "1 चीज़ जोड़ी", "3 चीज़ें जोड़ीं".
    override fun addedTitle(people: String, n: Int) =
        "$people ने $toBuy में ${HindiCommonTexts.itemCount(n)} ${if (n == 1) "जोड़ी" else "जोड़ीं"}"

    override fun changedTitle(people: String) = "$people ने $toBuy में बदलाव किया"
    override fun more(n: Int) = "+$n और"
    override val offerQuestion = "कोई $toBuy में कुछ जोड़े, तो सूचना चाहिए?"
    override val turnOn = "चालू करें"
    override val notNow = "अभी नहीं"
}
