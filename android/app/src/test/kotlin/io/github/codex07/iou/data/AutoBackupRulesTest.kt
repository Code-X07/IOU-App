package io.github.codex07.iou.data

import io.kotest.matchers.shouldBe
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/** Spec §9 and §14 item 5: both rule files back up exactly the ledger databases and their pointer. */
class AutoBackupRulesTest {

    private data class Rule(val tag: String, val domain: String, val path: String, val flags: String = "")

    private val ledgerOnly = listOf(
        Rule("include", "database", "."),
        Rule("exclude", "database", "${DbFiles.POINTER}.tmp"),
        Rule("exclude", "database", DbFiles.MARKER),
        Rule("exclude", "database", "${DbFiles.MARKER}.tmp"),
    )

    private fun xml(name: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/$name.xml")).documentElement

    private fun Element.rules(): List<Rule> = children().filter { it.tagName in setOf("include", "exclude") }
        .map { Rule(it.tagName, it.getAttribute("domain"), it.getAttribute("path"), it.getAttribute("requireFlags")) }

    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

    @Test
    fun `API 31 and later back up the ledger only, to the cloud only when it is encrypted`() {
        val root = xml("data_extraction_rules")
        val sections = root.children().associateBy { it.tagName }
        sections.keys shouldBe setOf("cloud-backup", "device-transfer")
        sections.getValue("cloud-backup").getAttribute("disableIfNoEncryptionCapabilities") shouldBe "true"
        sections.getValue("cloud-backup").rules() shouldBe ledgerOnly
        sections.getValue("device-transfer").rules() shouldBe ledgerOnly
    }

    @Test
    fun `API 30 backs up the ledger only, into encrypted backups and device transfers`() {
        xml("backup_rules").rules() shouldBe listOf(
            Rule("include", "database", ".", "clientSideEncryption"),
            Rule("include", "database", ".", "deviceToDeviceTransfer"),
        ) + ledgerOnly.drop(1)
    }

    @Test
    fun `the manifest enables Auto Backup with both rule files`() {
        val app = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement.getElementsByTagName("application").item(0) as Element
        fun attr(name: String) = app.getAttributeNS("http://schemas.android.com/apk/res/android", name)
        attr("allowBackup") shouldBe "true"
        attr("dataExtractionRules") shouldBe "@xml/data_extraction_rules"
        attr("fullBackupContent") shouldBe "@xml/backup_rules"
    }
}
