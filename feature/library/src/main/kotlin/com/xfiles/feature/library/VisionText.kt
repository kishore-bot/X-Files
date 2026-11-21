package com.xfiles.feature.library


/*
 * The words of the About and Why Xfiles screens, kept apart from the layout
 * so they are easy to edit.
 */

/** One piece of the Why Xfiles text, in reading order. */
sealed interface VisionBlock {
    data class Title(val text: String) : VisionBlock

    data class Paragraph(val text: String) : VisionBlock

    /** A bold line, shown as a pull quote. */
    data class PullQuote(val text: String) : VisionBlock

    data class Beliefs(val items: List<String>) : VisionBlock

    /** The italic closing line. */
    data class SignOff(val text: String) : VisionBlock
}

/** The owner's words, exactly as written. Change the layout, not the text, unless the owner asks. */
val VisionText: List<VisionBlock> = listOf(
    VisionBlock.Title("Why Xfiles Exists"),
    VisionBlock.PullQuote("Free doesn't mean cheap."),
    VisionBlock.Paragraph("Today, even the simplest applications often come with advertisements, subscriptions, and unnecessary restrictions."),
    VisionBlock.Paragraph("Technology has given us extraordinary possibilities, yet access to useful tools increasingly comes with a price."),
    VisionBlock.Paragraph("As artificial intelligence transforms our world, the future of work and the economy remains uncertain."),
    VisionBlock.Paragraph("I believe technology should help people grow, not create more barriers for them."),
    VisionBlock.Paragraph("That's why I started Xfiles."),
    VisionBlock.Paragraph("Not to build another subscription service. Not to compete for people's attention through advertisements."),
    VisionBlock.Paragraph("But to create something useful, accessible, and respectful of its users."),
    VisionBlock.PullQuote("I believe an idea shouldn't end with the person who created it."),
    VisionBlock.Paragraph("When we share our knowledge, tools, and ideas, someone else might take them further than we ever imagined."),
    VisionBlock.Paragraph("One person's small contribution could become the foundation for something extraordinary."),
    VisionBlock.Paragraph("Xfiles is just a file reader today. It won't change the world on its own."),
    VisionBlock.Paragraph("But perhaps it can be one small example of a different approach to building software."),
    VisionBlock.Paragraph("Because I believe:"),
    VisionBlock.Beliefs(
        listOf(
            "Free software can be high-quality software.",
            "Privacy should be a principle, not a premium feature.",
            "Knowledge becomes more powerful when shared.",
            "Technology should empower people rather than exploit them.",
            "Progress should benefit humanity and the world we share.",
        ),
    ),
    VisionBlock.PullQuote("We don't all have to change the world. But we can each contribute something that helps it move forward."),
    VisionBlock.Paragraph("That's the vision behind Xfiles."),
    VisionBlock.SignOff("Built for people. Shared with everyone."),
)
