package cn.alone.hamitv.esp

import cn.alone.hamitv.tv.CommandTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun keepsExtraValuesAsStrings() {
        val command = CommandTable.parse(
            """[{"id":"a","action":"b","extras":{"did":"1021043316","index":2}}]""",
        ).single()

        assertEquals("1021043316", command.extras["did"])
        assertEquals("2", command.extras["index"])
    }

    @Test
    fun entityKeysAreStableAndUnique() {
        val registry = EntityRegistry(CommandTable.parse(
            """[{"id":"a","action":"x"},{"id":"b","action":"y"}]""",
        ))

        assertEquals(2, registry.buttons.size)
        assertEquals(registry.buttons[0].key, EntityRegistry.keyOf(registry.buttons[0].objectId))
        assertNotEquals(registry.buttons[0].key, registry.buttons[1].key)
        assertTrue(registry.buttons.all { it.objectId.startsWith("mitv_") })
    }
}
