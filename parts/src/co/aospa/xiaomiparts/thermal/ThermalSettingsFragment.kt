/*
 * SPDX-FileCopyrightText: 2020 The LineageOS Project
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.xiaomiparts.thermal

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import co.aospa.xiaomiparts.R
import co.aospa.xiaomiparts.thermal.ThermalUtils.ThermalState
import co.aospa.xiaomiparts.utils.dlog
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SettingsSpinnerAdapter

/** Thermal profiles, with preferences and per-app controls sharing one scrolling list. */
class ThermalSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var launcherApps: LauncherApps
    private lateinit var thermalUtils: ThermalUtils
    private val appsAdapter = AppsAdapter()
    private val sectionAdapter = AppsSectionAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handlerThread = HandlerThread(TAG).apply { start() }
    private val bgHandler = Handler(handlerThread.looper)
    private var isLoaded = false
    private var loadFailed = false
    private var loadGeneration = 0

    private val launcherAppsCallback =
        object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = reloadApps(user)

            override fun onPackageAdded(packageName: String, user: UserHandle) = reloadApps(user)

            override fun onPackageChanged(packageName: String, user: UserHandle) = reloadApps(user)

            override fun onPackagesAvailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean
            ) = reloadApps(user)

            override fun onPackagesUnavailable(
                packageNames: Array<String>,
                user: UserHandle,
                replacing: Boolean
            ) = reloadApps(user)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.thermal_settings)
        thermalUtils = ThermalUtils.getInstance(requireContext())
        findPreference<MainSwitchPreference>(THERMAL_ENABLE_KEY)!!.apply {
            isChecked = thermalUtils.enabled
            setOnPreferenceChangeListener { _, newValue ->
                thermalUtils.enabled = newValue as Boolean
                updateAppContent()
                true
            }
        }
        updateAppContent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherApps = requireContext().getSystemService(LauncherApps::class.java)!!
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> =
        ConcatAdapter(super.onCreateAdapter(preferenceScreen), sectionAdapter, appsAdapter)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.apply {
            clipToPadding = false
            setPaddingRelative(
                paddingStart,
                paddingTop,
                paddingEnd,
                (24 * resources.displayMetrics.density).toInt()
            )
        }
        launcherApps.registerCallback(launcherAppsCallback, mainHandler)
        loadApps()
    }

    override fun onDestroyView() {
        launcherApps.unregisterCallback(launcherAppsCallback)
        ++loadGeneration
        bgHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun reloadApps(user: UserHandle) {
        if (user == Process.myUserHandle() && view != null) loadApps()
    }

    private fun updateAppContent() {
        appsAdapter.stateRestorationPolicy =
            if (thermalUtils.enabled && !isLoaded && !loadFailed) {
                RecyclerView.Adapter.StateRestorationPolicy.PREVENT
            } else {
                RecyclerView.Adapter.StateRestorationPolicy.ALLOW
            }
        sectionAdapter.notifyDataSetChanged()
        appsAdapter.notifyDataSetChanged()
    }

    private fun loadApps() {
        val generation = ++loadGeneration
        loadFailed = false
        sectionAdapter.notifyDataSetChanged()
        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post {
            val result = runCatching {
                launcherApps
                    .getActivityList(null, Process.myUserHandle())
                    .distinctBy { it.componentName.packageName }
                    .map { it.toAppEntry() }
                    .sortedBy { it.label.lowercase() }
            }
            mainHandler.post applyApps@{
                if (view == null || generation != loadGeneration) return@applyApps
                result
                    .onSuccess { entries ->
                        dlog(TAG, "loaded ${entries.size} apps")
                        appsAdapter.entries = entries
                        isLoaded = true
                    }
                    .onFailure {
                        Log.e(TAG, "Unable to load apps", it)
                        loadFailed = true
                    }
                updateAppContent()
            }
        }
    }

    private fun LauncherActivityInfo.toAppEntry() =
        AppEntry(
            packageName = componentName.packageName,
            label = label.toString(),
            icon = getIcon(0),
        )

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable,
    )

    private class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view)

    private inner class AppsSectionAdapter : RecyclerView.Adapter<SectionViewHolder>() {
        override fun getItemCount() = if (thermalUtils.enabled) 1 else 0

        override fun getItemViewType(position: Int) =
            if (isLoaded) R.layout.thermal_apps_header else R.layout.thermal_loading

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SectionViewHolder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

        override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
            if (isLoaded) return
            holder.itemView.findViewById<View>(R.id.thermal_loading_progress).isVisible = !loadFailed
            holder.itemView.findViewById<TextView>(R.id.thermal_loading_message).setText(
                if (loadFailed) R.string.thermal_loading_failed else R.string.thermal_loading
            )
            holder.itemView.isFocusable = loadFailed
            holder.itemView.setOnClickListener(
                if (loadFailed) View.OnClickListener { loadApps() } else null
            )
        }
    }

    private inner class ModeAdapter(context: Context) : SettingsSpinnerAdapter<String>(context) {
        init {
            addAll(ThermalState.values().map { context.getString(it.label) })
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (convertView as? TextView ?: getDefaultView(position, convertView, parent) as TextView)
                .apply { text = getItem(position) }
    }

    private inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.app_name)
        val mode: Spinner = view.findViewById(R.id.app_mode)
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val modeAdapter = ModeAdapter(view.context)

        init {
            mode.adapter = modeAdapter
            view.setOnClickListener { mode.performClick() }
        }
    }

    private inner class AppsAdapter :
        RecyclerView.Adapter<AppViewHolder>(), AdapterView.OnItemSelectedListener {

        var entries: List<AppEntry> = emptyList()

        override fun getItemCount() = if (thermalUtils.enabled && isLoaded) entries.size else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AppViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.thermal_list_item, parent, false)
            )

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val entry = entries[position]
            val state = thermalUtils.getStateForPackage(entry.packageName)
            holder.mode.apply {
                onItemSelectedListener = null
                tag = entry
                holder.modeAdapter.setSelectedPosition(state.id)
                setSelection(state.id, false)
                contentDescription = getString(R.string.thermal_profile_for_app, entry.label)
                onItemSelectedListener = this@AppsAdapter
            }
            holder.title.text = entry.label
            holder.icon.setImageDrawable(entry.icon)
        }

        override fun onViewRecycled(holder: AppViewHolder) {
            holder.mode.onItemSelectedListener = null
            holder.mode.tag = null
            super.onViewRecycled(holder)
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val entry = parent?.tag as? AppEntry ?: return
            (parent.adapter as ModeAdapter).setSelectedPosition(position)
            if (thermalUtils.getStateForPackage(entry.packageName).id != position) {
                thermalUtils.writePackage(entry.packageName, position)
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        private const val TAG = "ThermalSettingsFragment"
        private const val THERMAL_ENABLE_KEY = "thermal_enable"
    }
}
