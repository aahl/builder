package cn.alone.hamitv.esp

import cn.alone.hamitv.tv.CommandTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTableTest {

    @Test
    fun parsesEntriesAndSkipsInvalidOnes() {
        val commands = CommandTable.parse(
            """
            [
              {"id":"show_panel","name":"控制中心","type":"service",
               "action":"com.xiaomi.mitv.smartpanel.action.SHOW_PANEL",
               "component":"com.xiaomi.mitv.smartpanel/.SmartPanelService"},
              {"id":"no_action","name":"broken"},
              {"name":"missing id","action":"x"}
            ]
            """.trimIndent(),
        )

        assertEquals(1, commands.size)
        val command = commands.single()
        assertEquals("show_panel", command.id)
        assertEquals("service", command.type)
        assertEquals("com.xiaomi.mitv.smartpanel/.SmartPanelService", command.component)
    }

    @Test
    fun keepsExtraTypes() {
        val command = CommandTable.parse(
            """[{"id":"a","action":"b","extras":{"did":"1021043316","input":23,"raw":"23"}}]""",
        ).single()

        assertEquals("1021043316", command.extras["did"])
        // Xiaomi's ExternalSourceActivity reads an int, so the type must survive.
        assertEquals(23, command.extras["input"])
        assertEquals("23", command.extras["raw"])
    }

    @Test
    fun acceptsComponentOnlyTarget() {
        val command = CommandTable.parse(
            """[{"id":"hdmi","type":"activity","component":"com.xiaomi.mitv.tvplayer/.ExternalSourceActivity"}]""",
        ).single()

        assertEquals("", command.action)
        assertEquals("com.xiaomi.mitv.tvplayer/.ExternalSourceActivity", command.component)
    }

    @Test
    fun selectBecomesOneEntityWithOrderedOptions() {
        val registry = EntityRegistry(
            CommandTable.parse(
                """
                [
                  {"id":"input_source","name":"输入源","type":"select",
                   "options":[
                     {"name":"主页","type":"activity","action":"android.intent.action.MAIN",
                      "categories":["android.intent.category.HOME"]},
                     {"name":"HDMI 1","type":"activity",
                      "component":"com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                      "extras":{"input":23}}
                   ]}
                ]
                """.trimIndent(),
            ),
        )

        assertTrue(registry.buttons.isEmpty())
        val select = registry.selects.single()
        assertEquals("mitv_input_source", select.objectId)
        assertEquals(listOf("主页", "HDMI 1"), select.options)
        assertEquals(23, select.commands.getValue("HDMI 1").extras["input"])
        assertEquals(
            listOf("android.intent.category.HOME"),
            select.commands.getValue("主页").categories,
        )
    }

    @Test
    fun selectWithoutOptionsIsDropped() {
        val registry = EntityRegistry(
            CommandTable.parse("""[{"id":"empty","name":"空","type":"select","options":[]}]"""),
        )

        assertTrue(registry.selects.isEmpty())
        assertTrue(registry.buttons.isEmpty())
    }

    @Test
    fun duplicateOptionNamesAreIgnored() {
        val select = EntityRegistry(
            CommandTable.parse(
                """[{"id":"s","type":"select","options":[
                     {"name":"A","action":"a"},{"name":"A","action":"b"},{"action":"c"}
                   ]}]""",
            ),
        ).selects.single()

        assertEquals(listOf("A"), select.options)
        assertEquals("a", select.commands.getValue("A").action)
    }

    @Test
    fun entityKeysAreStableAndUnique() {
        val registry = EntityRegistry(
            CommandTable.parse("""[{"id":"a","action":"x"},{"id":"b","action":"y"}]"""),
        )

        assertEquals(2, registry.buttons.size)
        assertEquals(registry.buttons[0].key, EntityRegistry.keyOf(registry.buttons[0].objectId))
        assertNotEquals(registry.buttons[0].key, registry.buttons[1].key)
        assertTrue(registry.buttons.all { it.objectId.startsWith("mitv_") })
    }

    @Test
    fun buttonsAndSelectsNeverShareAKey() {
        val registry = EntityRegistry(
            CommandTable.parse(
                """[{"id":"a","action":"x"},
                    {"id":"b","type":"select","options":[{"name":"B","action":"y"}]}]""",
            ),
        )

        assertFalse(registry.buttons.any { it.key == registry.selects.single().key })
        assertNull(registry.select(registry.buttons.single().key))
    }
}
