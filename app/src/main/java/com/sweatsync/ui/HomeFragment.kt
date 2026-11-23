package com.sweatsync.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.sweatsync.R
import com.sweatsync.ble.MachineDevice
import com.sweatsync.ble.MachineScanner
import com.sweatsync.data.Prefs
import com.sweatsync.service.MachineClient
import com.sweatsync.service.MachineService
import com.sweatsync.util.Perms

/**
 * Home tab: looks for machines and pairs with the one the user taps. The search runs for a while and then
 * pauses to save battery; pulling the list down (or the button shown while paused) searches again.
 */
class HomeFragment : Fragment(R.layout.fragment_home), MachineScanner.Listener {

    /** What the empty list says, and the one action that fixes it (if any). */
    private class EmptyState(val title: Int, val hint: Int?, val action: Int? = null, val onAction: (() -> Unit)? = null)

    private lateinit var prefs: Prefs
    private lateinit var scanner: MachineScanner
    private lateinit var adapter: DeviceAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyScroll: View
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var pulse: PulseView
    private lateinit var tvEmpty: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var btnAction: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var devices = emptyList<MachineDevice>()
    private var searching = false
    private var problem: MachineScanner.Problem? = null
    private var searchPaused = false
    private var navigating = false

    private val pauseSearch = Runnable {
        scanner.stop(this)
        searchPaused = true
        render()
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        search()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MachineClient(requireContext(), lifecycle) { state -> if (state.isBusy) openWorkout() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        prefs = Prefs(context)
        scanner = MachineScanner.get(context)
        adapter = DeviceAdapter(::connect)
        list = view.findViewById<RecyclerView>(R.id.deviceList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HomeFragment.adapter
            itemAnimator = null
        }
        emptyScroll = view.findViewById(R.id.emptyScroll)
        refresh = view.findViewById<SwipeRefreshLayout>(R.id.refresh).apply {
            // The gesture is only a trigger: the radar is the search indicator, so the library's own spinner
            // is made invisible and kept off screen.
            setColorSchemeColors(Color.TRANSPARENT)
            setProgressBackgroundColorSchemeColor(Color.TRANSPARENT)
            setProgressViewOffset(false, HIDDEN_OFFSET_PX, HIDDEN_OFFSET_PX)
            // Its direct child is a plain FrameLayout, so ask the visible list whether it can still scroll up;
            // otherwise pulling down on a scrolled list would search again instead of scrolling.
            setOnChildScrollUpCallback { _, _ ->
                (if (emptyScroll.visibility == View.VISIBLE) emptyScroll else list).canScrollVertically(-1)
            }
            setOnRefreshListener {
                isRefreshing = false
                search()
            }
        }
        pulse = view.findViewById(R.id.pulse)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvEmptyHint = view.findViewById(R.id.tvEmptyHint)
        btnAction = view.findViewById(R.id.btnAction)
    }

    override fun onStart() {
        super.onStart()
        navigating = false
        if (Perms.hasBluetooth(requireContext())) search() else permissions.launch(Perms.toRequest())
    }

    override fun onStop() {
        handler.removeCallbacks(pauseSearch)
        scanner.stop(this)
        super.onStop()
    }

    /** A new search window: forgets what was found and looks again (pull to refresh, "Search again", returning). */
    private fun search() {
        if (!Perms.hasBluetooth(requireContext())) {
            render()
            return
        }
        searchPaused = false
        handler.removeCallbacks(pauseSearch)
        handler.postDelayed(pauseSearch, SEARCH_WINDOW_MS)
        scanner.start(this)
        render()
    }

    override fun onDevices(devices: List<MachineDevice>) {
        this.devices = devices
        render()
    }

    override fun onScanState(searching: Boolean, problem: MachineScanner.Problem?) {
        this.searching = searching
        this.problem = problem
        render()
    }

    private fun connect(device: MachineDevice) {
        if (navigating) return
        // Leave the radio to the connection.
        handler.removeCallbacks(pauseSearch)
        scanner.stop(this, linger = false)
        MachineService.connect(requireContext(), device)
        openWorkout()
    }

    private fun openWorkout() {
        if (navigating) return
        navigating = true
        startActivity(Intent(requireContext(), WorkoutActivity::class.java))
    }

    private fun render() {
        if (view == null) return
        val rows = devices.map(::rowFor).sortedWith(compareByDescending<DeviceRow> { it.isLastUsed }.thenBy { it.device.name })
        adapter.submitList(rows)

        val permissionMissing = !Perms.hasBluetooth(requireContext())
        pulse.isActive = searching && problem == null && !permissionMissing

        emptyScroll.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val empty = emptyState(permissionMissing)
        tvEmpty.setText(empty.title)
        tvEmptyHint.visibility = if (empty.hint != null) View.VISIBLE else View.GONE
        empty.hint?.let(tvEmptyHint::setText)
        btnAction.visibility = if (empty.action != null) View.VISIBLE else View.GONE
        empty.action?.let(btnAction::setText)
        btnAction.setOnClickListener { empty.onAction?.invoke() }
    }

    private fun emptyState(permissionMissing: Boolean): EmptyState = when {
        permissionMissing -> EmptyState(R.string.status_permission, R.string.status_permission_hint, R.string.action_grant, ::askPermission)
        problem == MachineScanner.Problem.BLUETOOTH_OFF ->
            EmptyState(R.string.status_bt_off, R.string.status_bt_hint, R.string.action_enable_bt, ::enableBluetooth)
        problem == MachineScanner.Problem.LOCATION_OFF -> EmptyState(
            R.string.status_location_off, R.string.status_location_hint, R.string.action_enable_location
        ) { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        problem == MachineScanner.Problem.SCAN_FAILED -> EmptyState(R.string.status_scan_failed, null)
        searchPaused -> EmptyState(R.string.search_paused, R.string.search_paused_hint, R.string.search_again, ::search)
        else -> EmptyState(R.string.scanning, R.string.devices_hint)
    }

    /** Asks again while Android still allows it; after "don't ask again" only the app's settings page can grant it. */
    private fun askPermission() {
        if (Perms.toRequest().any(::shouldShowRequestPermissionRationale)) {
            permissions.launch(Perms.toRequest())
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)))
        }
    }

    /** The scanner resumes by itself when Bluetooth comes on. The permission was checked before this button shows. */
    @SuppressLint("MissingPermission")
    private fun enableBluetooth() = startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

    /** The advertised type wins; for the machine used last time we also remember its type. */
    private fun rowFor(device: MachineDevice): DeviceRow {
        val isLast = device.address == prefs.lastAddress
        return DeviceRow(device, device.type ?: prefs.lastType.takeIf { isLast }, isLast)
    }

    private companion object {
        const val SEARCH_WINDOW_MS = 45_000L
        const val HIDDEN_OFFSET_PX = -4_000
    }
}
