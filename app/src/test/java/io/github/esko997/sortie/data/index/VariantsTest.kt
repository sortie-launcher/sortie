package io.github.esko997.sortie.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariantsTest {
    private fun e(name: String, playable: Boolean = true) = Variants.Entry("psx/$name", name, playable)

    @Test
    fun `later discs point at disc 1`() {
        val r = Variants.resolve(listOf(e("Colony Wars (USA) (Disc 1).chd"), e("Colony Wars (USA) (Disc 2).chd"), e("Tobal No. 1 (USA).chd")))
        assertEquals("psx/Colony Wars (USA) (Disc 1).chd", r["psx/Colony Wars (USA) (Disc 2).chd"])
        assertNull(r["psx/Colony Wars (USA) (Disc 1).chd"])
        assertNull(r["psx/Tobal No. 1 (USA).chd"])
    }

    @Test
    fun `same stem in two formats keeps the better container`() {
        val r = Variants.resolve(listOf(e("Bushido Blade (USA).pbp"), e("Bushido Blade (USA).chd")))
        assertEquals("psx/Bushido Blade (USA).chd", r["psx/Bushido Blade (USA).pbp"])
        assertEquals(1, r.size)
    }

    @Test
    fun `a placeholder never becomes the primary`() {
        val r = Variants.resolve(listOf(e("Viewpoint.zip", playable = false), e("Viewpoint.7z")))
        assertEquals("psx/Viewpoint.7z", r["psx/Viewpoint.zip"])
    }

    @Test
    fun `different tags are different games`() {
        val r = Variants.resolve(listOf(e("Alundra (USA) (Rev 1).chd"), e("Alundra (USA) (Un-Working Designs).chd")))
        assertEquals(0, r.size)
    }
}
