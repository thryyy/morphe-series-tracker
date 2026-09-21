package app.morphe.patches.youtube.video.series

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ResourceContractTest {
    private fun strings(locale: String): Map<String, String> {
        val stream =
            javaClass.getResourceAsStream("/addresources/values$locale/youtube/strings.xml")!!
        val document = stream.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val nodes = document.getElementsByTagName("string")
        val entries = (0 until nodes.length).map { nodes.item(it) as Element }
        assertEquals(
            "Duplicate resource names",
            entries.size,
            entries.map { it.getAttribute("name") }.toSet().size,
        )
        return entries
            .filter { it.getAttribute("name").startsWith("series_tracker_") }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun `translations preserve format arguments and cover the Series screen`() {
        val english = strings("")
        val french = strings("-fr-rFR")
        assertEquals(english.keys, french.keys)
        val arguments = Regex("%[0-9]+\\$[sd]")
        english.forEach { (key, value) ->
            assertEquals(
                key,
                arguments.findAll(value).map { it.value }.sorted().toList(),
                arguments.findAll(french.getValue(key)).map { it.value }.sorted().toList(),
            )
        }
        val directory =
            File("../extensions/youtube/src/main/java/app/morphe/extension/youtube/series")
        assertTrue(directory.isDirectory)
        // Scan every production Series class, including preferences and metadata helpers.
        val nonTextKeys = setOf(
            "series_tracker_history_root", "series_tracker_ui", "series_tracker_privacy",
            "series_tracker_button",
        )
        val keys = Regex("\"(series_tracker_[a-z0-9]+(?:_[a-z0-9]+)*)\"")
        directory.walkTopDown().filter { it.extension == "java" }.forEach { file ->
            keys.findAll(file.readText()).forEach { match ->
                val key = match.groupValues[1]
                if (key !in nonTextKeys)
                    assertTrue("Missing UI string in ${file.name}: $key", key in english)
            }
        }
    }

    @Test
    fun `UI setters do not introduce untranslated literal labels`() {
        val directory = File("../extensions/youtube/src/main/java/app/morphe/extension/youtube/series")
        val literalLabel = Regex(
            """\bset(?:Text|Title|Summary|Hint|ContentDescription)\(\s*"([A-Za-z][^"\n]*)""""
        )
        directory.walkTopDown().filter { it.extension == "java" }.forEach { file ->
            assertFalse("Untranslated UI label in ${file.name}",
                literalLabel.containsMatchIn(file.readText()))
        }
    }
}
