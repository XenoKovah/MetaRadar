package com.darkmentor.ui.exclusionzones

import android.app.Application
import android.widget.Toast
import com.darkmentor.data.repo.SettingsRepository
import com.darkmentor.domain.model.ExclusionZone
import com.darkmentor.utils.navigation.BackCommand
import com.darkmentor.utils.navigation.Router
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.osmdroid.util.GeoPoint

/**
 * Drives the [ExclusionZonesViewModel] state machine over mocked dependencies: Browse↔Adjust mode
 * transitions, add/edit/delete + persistence, size clamping, and the 3-zone cap. `Toast` (hit on
 * the cap path) is stubbed statically so it no-ops in a JVM unit test.
 */
class ExclusionZonesViewModelTest {

    private val app = mockk<Application>(relaxed = true)
    private val router = mockk<Router>(relaxed = true)
    private val settings = mockk<SettingsRepository>()
    private var stored: MutableList<ExclusionZone> = mutableListOf()

    @Before
    fun setUp() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)
        every { settings.getExclusionZones() } answers { stored.toList() }
        every { settings.setExclusionZones(any()) } answers { stored = firstArg<List<ExclusionZone>>().toMutableList() }
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(Toast::class)
    }

    private fun vm() = ExclusionZonesViewModel(app, settings, router)

    @Test
    fun `loads persisted zones on init`() {
        stored = mutableListOf(ExclusionZone.Circle(1.0, 2.0, 50.0))
        assertEquals(1, vm().zones.size)
    }

    @Test
    fun `add a zone enters Adjust then Save appends + persists + returns to Browse`() {
        val vm = vm()
        vm.onAddShape(ExclusionZonesViewModel.ShapeKind.CIRCLE, GeoPoint(40.0, -75.0))
        val adjust = vm.mode as ExclusionZonesViewModel.Mode.Adjust
        assertEquals(ExclusionZonesViewModel.DEFAULT_ZONE_METERS, adjust.sizeMeters, 0.0)
        assertEquals(null, adjust.editIndex)

        vm.onSaveZone()

        assertEquals(ExclusionZonesViewModel.Mode.Browse, vm.mode)
        assertEquals(1, vm.zones.size)
        assertEquals(1, stored.size)
        assertTrue(vm.zones[0] is ExclusionZone.Circle)
    }

    @Test
    fun `edit re-enters Adjust and Save replaces in place`() {
        stored = mutableListOf(ExclusionZone.Circle(10.0, 20.0, 50.0))
        val vm = vm()

        vm.onEditZone(0)
        assertEquals(0, (vm.mode as ExclusionZonesViewModel.Mode.Adjust).editIndex)
        vm.onSizeChanged(123.0)
        vm.onSaveZone()

        assertEquals(1, vm.zones.size) // replaced, not appended
        assertEquals(123.0, (vm.zones[0] as ExclusionZone.Circle).radiusMeters, 0.0)
    }

    @Test
    fun `delete removes the zone and persists`() {
        stored = mutableListOf(ExclusionZone.Circle(1.0, 2.0, 50.0), ExclusionZone.Square(3.0, 4.0, 60.0))
        val vm = vm()

        vm.onDeleteZone(0)

        assertEquals(1, vm.zones.size)
        assertTrue(vm.zones[0] is ExclusionZone.Square)
        assertEquals(1, stored.size)
    }

    @Test
    fun `at the 3-zone cap adding does not enter Adjust`() {
        stored = (1..ExclusionZone.MAX_ZONES).map { ExclusionZone.Circle(it.toDouble(), it.toDouble(), 50.0) }.toMutableList()
        val vm = vm()
        assertFalse(vm.canAddZone)

        vm.onAddShape(ExclusionZonesViewModel.ShapeKind.SQUARE, GeoPoint(0.0, 0.0))

        assertEquals(ExclusionZonesViewModel.Mode.Browse, vm.mode) // stayed in Browse
        assertEquals(ExclusionZone.MAX_ZONES, vm.zones.size)
        verify { Toast.makeText(any(), any<Int>(), any()) }
    }

    @Test
    fun `size change clamps to the min and max radius`() {
        val vm = vm()
        vm.onAddShape(ExclusionZonesViewModel.ShapeKind.SQUARE, GeoPoint(0.0, 0.0))

        vm.onSizeChanged(99_999.0)
        assertEquals(ExclusionZonesViewModel.MAX_ZONE_METERS, (vm.mode as ExclusionZonesViewModel.Mode.Adjust).sizeMeters, 0.0)
        vm.onSizeChanged(0.5)
        assertEquals(ExclusionZonesViewModel.MIN_ZONE_METERS, (vm.mode as ExclusionZonesViewModel.Mode.Adjust).sizeMeters, 0.0)
    }

    @Test
    fun `cancel returns to Browse without saving`() {
        val vm = vm()
        vm.onAddShape(ExclusionZonesViewModel.ShapeKind.CIRCLE, GeoPoint(0.0, 0.0))

        vm.onCancelAdjust()

        assertEquals(ExclusionZonesViewModel.Mode.Browse, vm.mode)
        assertTrue(vm.zones.isEmpty())
    }

    @Test
    fun `close navigates back`() {
        vm().onCloseClick()
        verify { router.navigate(BackCommand) }
    }
}
