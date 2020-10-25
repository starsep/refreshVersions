package de.fayard.refreshVersions.core.internal.versions

import de.fayard.refreshVersions.core.extensions.sequences.uniqueBy
import de.fayard.refreshVersions.core.extensions.text.substringAfterLastLineStartingWith
import de.fayard.refreshVersions.core.extensions.text.substringBetween
import java.io.File

internal fun VersionsPropertiesModel.Companion.readFrom(
    versionsPropertiesFile: File
): VersionsPropertiesModel {
    return readFromText(versionsPropertiesFile.readText())
}

internal fun VersionsPropertiesModel.Companion.readFromText(
    fileContent: String
): VersionsPropertiesModel = try {
    readFromTextInternal(fileContent)
} catch (e: IllegalArgumentException) {
    throw IllegalStateException(e)
}

private fun VersionsPropertiesModel.Companion.readFromTextInternal(
    fileContent: String
): VersionsPropertiesModel {
    val preHeaderContent: String
    val generatedByVersion: String
    val sectionsText: String
    if (fileContent.startsWith(oldFileHeader)) {
        preHeaderContent = ""
        generatedByVersion = "0.9.7" // Might be actually older, but it doesn't matter.
        sectionsText = fileContent.substringAfter(oldFileHeader)
    } else {
        preHeaderContent = fileContent.substringBefore(headerLinesPrefix)
        generatedByVersion = fileContent.substringBetween(generatedByLineStart, "\n")
        sectionsText = fileContent.substringAfterLastLineStartingWith(headerLinesPrefix)
    }

    val sections = mutableListOf<VersionsPropertiesModel.Section>()

    sectionsText.trim().splitToSequence("\n\n").mapNotNull { sectionText ->
        readSection(sectionText)
    }.onEach {
        sections.add(it)
    }.filterIsInstance<VersionsPropertiesModel.Section.VersionEntry>().uniqueBy(
        onDuplicate = { key ->
            throw IllegalArgumentException("The version with key $key has been found twice!")
        }
    ) {
        it.key
    }.lastOrNull() // Force iteration of the whole sequence.

    return VersionsPropertiesModel(
        preHeaderContent = preHeaderContent,
        generatedByVersion = generatedByVersion,
        sections = sections
    )
}

private fun readSection(sectionText: String): VersionsPropertiesModel.Section? {
    val lines = sectionText.lines().map { it.trim() }

    val versionLineIndex = lines.indexOfFirst {
        VersionsPropertiesModel.versionKeysPrefixes.any { prefix -> it.startsWith(prefix) }
    }.also {
        if (it == -1) {
            if (sectionText.isEmpty()) return null
            return VersionsPropertiesModel.Section.Comment(lines = sectionText)
        }
    }

    val versionLine = lines[versionLineIndex]

    fun String.isAvailableUpdateComment(): Boolean {
        return startsWith("##") && "${VersionsPropertiesModel.availableComment}=" in this
    }

    val remainingLines = lines.subList(
        fromIndex = (versionLineIndex + 1).coerceAtMost(lines.size),
        toIndex = lines.size
    )

    var availableUpdatesSectionPassed = false

    val (availableUpdatesComments, trailingComments) = remainingLines.partition { line ->
        line.isAvailableUpdateComment().also { isAvailableUpdateLine ->
            if (isAvailableUpdateLine) check(availableUpdatesSectionPassed.not()) {
                "Putting custom comments between available updates comments is not supported."
            }
            availableUpdatesSectionPassed = isAvailableUpdateLine.not()
        }
    }

    val versionKey = versionLine.substringBefore('=')

    return VersionsPropertiesModel.Section.VersionEntry(
        leadingCommentLines = lines.subList(fromIndex = 0, toIndex = versionLineIndex),
        key = versionKey,
        currentVersion = versionLine.substringAfter('=', missingDelimiterValue = "").ifEmpty {
            error("Didn't find the value of the version for the following key: $versionKey")
        },
        availableUpdates = availableUpdatesComments.map { it.substringAfter('=') },
        trailingCommentLines = trailingComments
    )
}

private val oldFileHeader = """
## suppress inspection "SpellCheckingInspection" for whole file
## suppress inspection "UnusedProperty" for whole file
##
## Dependencies and Plugin versions with their available updates
## Generated by ${'$'} ./gradlew refreshVersions
## Please, don't put extra comments in that file yet, keeping them is not supported yet.
""".trimMargin()
