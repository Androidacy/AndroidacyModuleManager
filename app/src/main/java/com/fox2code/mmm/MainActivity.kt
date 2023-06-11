package com.fox2code.mmm

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.mmm.AppUpdateManager.Companion.appUpdateManager
import com.fox2code.mmm.OverScrollManager.OverScrollHelper
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityCreate
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityResume
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.installer.InstallerInitializer.Companion.errorNotification
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.installer.InstallerInitializer.Companion.tryGetMagiskPathAsync
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.module.ModuleViewAdapter
import com.fox2code.mmm.module.ModuleViewListBuilder
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.settings.SettingsActivity
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.RuntimeUtils
import com.fox2code.mmm.utils.io.net.Http.Companion.cleanDnsCache
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.fox2code.mmm.utils.realm.ReposList
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.realm.Realm
import io.realm.RealmConfiguration
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.sql.Timestamp

class MainActivity : FoxActivity(), OnRefreshListener, SearchView.OnQueryTextListener,
    SearchView.OnCloseListener, OverScrollHelper {
    val moduleViewListBuilder: ModuleViewListBuilder = ModuleViewListBuilder(this)
    val moduleViewListBuilderOnline: ModuleViewListBuilder = ModuleViewListBuilder(this)
    var progressIndicator: LinearProgressIndicator? = null
    private var moduleViewAdapter: ModuleViewAdapter? = null
    private var moduleViewAdapterOnline: ModuleViewAdapter? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var swipeRefreshLayoutOrigStartOffset = 0
    private var swipeRefreshLayoutOrigEndOffset = 0
    private var swipeRefreshBlocker: Long = 0
    override var overScrollInsetTop = 0
        private set
    override var overScrollInsetBottom = 0
        private set
    private var moduleList: RecyclerView? = null
    private var moduleListOnline: RecyclerView? = null
    private var searchCard: CardView? = null
    private var searchView: SearchView? = null
    private var initMode = false
    private var runtimeUtils: RuntimeUtils? = null

    init {
        moduleViewListBuilder.addNotification(NotificationType.INSTALL_FROM_STORAGE)
    }

    override fun onResume() {
        onMainActivityResume(this)
        super.onResume()
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        initMode = true
        if (doSetupRestarting) {
            doSetupRestarting = false
        }
        onMainActivityCreate(this)
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(MainApplication.INSTANCE!!.tracker)
        // track enabled repos
        val realmConfig = RealmConfiguration.Builder().name("ReposList.realm")
            .encryptionKey(MainApplication.INSTANCE!!.key)
            .directory(MainApplication.INSTANCE!!.getDataDirWithPath("realms")).schemaVersion(1)
            .allowQueriesOnUiThread(true).allowWritesOnUiThread(true).build()
        val realm = Realm.getInstance(realmConfig)
        val enabledRepos = StringBuilder()
        realm.executeTransaction { r: Realm ->
            for (r2 in r.where(
                ReposList::class.java
            ).equalTo("enabled", true).findAll()) {
                enabledRepos.append(r2.url).append(":").append(r2.name).append(",")
            }
        }
        if (enabledRepos.isNotEmpty()) {
            enabledRepos.setLength(enabledRepos.length - 1)
        }
        TrackHelper.track().event("enabled_repos", enabledRepos.toString())
            .with(MainApplication.INSTANCE!!.tracker)
        realm.close()
        // hide this behind a buildconfig flag for now, but crash the app if it's not an official build and not debug
        if (BuildConfig.ENABLE_PROTECTION && !MainApplication.o && !BuildConfig.DEBUG) {
            throw RuntimeException("This is not an official build of AMM")
        } else if (!MainApplication.o && !BuildConfig.DEBUG) {
            Timber.w("You may be running an untrusted build.")
            // Show a toast to warn the user
            Toast.makeText(this, R.string.not_official_build, Toast.LENGTH_LONG).show()
        }
        val ts = Timestamp(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        // check if this build has expired
        val buildTime = Timestamp(BuildConfig.BUILD_TIME)
        // if the build is a debug build and the build time exceeds 30 days, throw an exception
        if (BuildConfig.DEBUG) {
            check(ts.time < buildTime.time) { "This build has expired. Please download a stable build or update to the latest version." }
        }
        setContentView(R.layout.activity_main)
        this.setTitle(R.string.app_name)
        // set window flags to ignore status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ignore status bar space
            this.window.setDecorFitsSystemWindows(false)
        } else {
            this.window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val layoutParams = this.window.attributes
            layoutParams.layoutInDisplayCutoutMode =  // Support cutout in Android 9
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            this.window.attributes = layoutParams
        }
        progressIndicator = findViewById(R.id.progress_bar)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh)
        val swipeRefreshLayout = swipeRefreshLayout!!
        swipeRefreshLayoutOrigStartOffset = swipeRefreshLayout.progressViewStartOffset
        swipeRefreshLayoutOrigEndOffset = swipeRefreshLayout.progressViewEndOffset
        swipeRefreshBlocker = Long.MAX_VALUE
        moduleList = findViewById(R.id.module_list)
        moduleListOnline = findViewById(R.id.module_list_online)
        searchCard = findViewById(R.id.search_card)
        searchView = findViewById(R.id.search_bar)
        val searchView = searchView!!
        searchView.isIconified = true
        moduleViewAdapter = ModuleViewAdapter()
        moduleViewAdapterOnline = ModuleViewAdapter()
        val moduleList = moduleList!!
        val moduleListOnline = moduleListOnline!!
        moduleList.adapter = moduleViewAdapter
        moduleListOnline.adapter = moduleViewAdapterOnline
        moduleList.layoutManager = LinearLayoutManager(this)
        moduleListOnline.layoutManager = LinearLayoutManager(this)
        moduleList.setItemViewCacheSize(4) // Default is 2
        swipeRefreshLayout.setOnRefreshListener(this)
        runtimeUtils = RuntimeUtils()
        // add background blur if enabled
        updateBlurState()
        //hideActionBar();
        runtimeUtils!!.checkShowInitialSetup(this, this)
        val searchCard = searchCard!!
        moduleList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) searchView.clearFocus()
                // hide search view when scrolling
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    searchCard.animate().translationY(-searchCard.height.toFloat())
                        .setInterpolator(AccelerateInterpolator(2f)).start()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the search bar
                if (dy < 0) {
                    searchCard.animate().translationY(0f)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }
        })
        // same for online
        moduleListOnline.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) searchView.clearFocus()
                // hide search view when scrolling
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    searchCard.animate().translationY(-searchCard.height.toFloat())
                        .setInterpolator(AccelerateInterpolator(2f)).start()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the search bar
                if (dy < 0) {
                    searchCard.animate().translationY(0f)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }
        })
        searchCard.radius = searchCard.height / 2f
        searchView.minimumHeight = FoxDisplay.dpToPixel(16f)
        searchView.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
        searchView.setOnQueryTextListener(this)
        searchView.setOnCloseListener(this)
        searchView.setOnQueryTextFocusChangeListener { _: View?, h: Boolean ->
            if (!h) {
                val query = searchView.query.toString()
                if (query.isEmpty()) {
                    searchView.isIconified = true
                }
            }
            cardIconifyUpdate()
        }
        searchView.isEnabled = false // Enabled later
        cardIconifyUpdate()
        this.updateScreenInsets(this.resources.configuration)

        // on the bottom nav, there's a settings item. open the settings activity when it's clicked.
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.settings_menu_item -> {
                    TrackHelper.track().event("view_list", "settings")
                        .with(MainApplication.INSTANCE!!.tracker)
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                    finish()
                }
                R.id.online_menu_item -> {
                    TrackHelper.track().event("view_list", "online_modules")
                        .with(MainApplication.INSTANCE!!.tracker)
                    // set module_list_online as visible and module_list as gone. fade in/out
                    moduleListOnline.alpha = 0f
                    moduleListOnline.visibility = View.VISIBLE
                    moduleListOnline.animate().alpha(1f).setDuration(300).setListener(null)
                    moduleList.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                moduleList.visibility = View.GONE
                            }
                        })
                    // clear search view
                    searchView.setQuery("", false)
                    searchView.clearFocus()
                }
                R.id.installed_menu_item -> {
                    TrackHelper.track().event("view_list", "installed_modules")
                        .with(MainApplication.INSTANCE!!.tracker)
                    // set module_list_online as gone and module_list as visible. fade in/out
                    moduleList.alpha = 0f
                    moduleList.visibility = View.VISIBLE
                    moduleList.animate().alpha(1f).setDuration(300).setListener(null)
                    moduleListOnline.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                moduleListOnline.visibility = View.GONE
                            }
                        })
                    // set search view to cleared
                    searchView.setQuery("", false)
                    searchView.clearFocus()
                }
            }
            true
        }
        // update the padding of blur_frame to match the new bottom nav height
        val blurFrame = findViewById<View>(R.id.blur_frame)
        blurFrame.post {
            blurFrame.setPadding(
                blurFrame.paddingLeft,
                blurFrame.paddingTop,
                blurFrame.paddingRight,
                bottomNavigationView.height
            )
        }
        // for some reason, root_container has a margin at the top. remove it.
        val rootContainer = findViewById<View>(R.id.root_container)
        rootContainer.post {
            val params = rootContainer.layoutParams as MarginLayoutParams
            params.topMargin = 0
            rootContainer.layoutParams = params
            rootContainer.y = 0f
        }
        // reset update module and update module count in main application
        MainApplication.INSTANCE!!.resetUpdateModule()
        tryGetMagiskPathAsync(object : InstallerInitializer.Callback {
            override fun onPathReceived(path: String?) {
                Timber.i("Got magisk path: %s", path)
                if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND) moduleViewListBuilder.addNotification(
                    NotificationType.MAGISK_OUTDATED
                )
                if (!MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.INSTALL_FROM_STORAGE
                )
                instance!!.scan()
                instance!!.runAfterScan { moduleViewListBuilder.appendInstalledModules() }
                instance!!.runAfterScan { moduleViewListBuilderOnline.appendRemoteModules() }
                commonNext()
            }

            override fun onFailure(error: Int) {
                Timber.e("Failed to get magisk path!")
                moduleViewListBuilder.addNotification(errorNotification)
                moduleViewListBuilderOnline.addNotification(errorNotification)
                commonNext()
            }

            fun commonNext() {
                if (BuildConfig.DEBUG) {
                    Timber.d("Common next")
                    moduleViewListBuilder.addNotification(NotificationType.DEBUG)
                }
                NotificationType.NO_INTERNET.autoAdd(moduleViewListBuilderOnline)
                val progressIndicator = progressIndicator!!
                // hide progress bar is repo-manager says we have no internet
                if (!RepoManager.getINSTANCE().hasConnectivity()) {
                    Timber.i("No connection, hiding progress")
                    runOnUiThread {
                        progressIndicator.visibility = View.GONE
                        progressIndicator.isIndeterminate = false
                        progressIndicator.max = PRECISION
                    }
                }
                updateScreenInsets() // Fix an edge case
                val context: Context = this@MainActivity
                if (runtimeUtils!!.waitInitialSetupFinished(context, this@MainActivity)) {
                    Timber.d("waiting...")
                    return
                }
                swipeRefreshBlocker = System.currentTimeMillis() + 5000L
                if (MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.SHOWCASE_MODE
                )
                if (!hasWebView()) {
                    // Check Http for WebView availability
                    moduleViewListBuilder.addNotification(NotificationType.NO_WEB_VIEW)
                    // disable online tab
                    runOnUiThread {
                        bottomNavigationView.menu.getItem(1).isEnabled = false
                        bottomNavigationView.selectedItemId = R.id.installed_menu_item
                    }
                }
                moduleViewListBuilder.applyTo(moduleList, moduleViewAdapter!!)
                Timber.i("Scanning for modules!")
                if (BuildConfig.DEBUG) Timber.i("Initialize Update")
                val max = instance!!.getUpdatableModuleCount()
                if (RepoManager.getINSTANCE().customRepoManager != null && RepoManager.getINSTANCE().customRepoManager.needUpdate()) {
                    Timber.w("Need update on create")
                } else if (RepoManager.getINSTANCE().customRepoManager == null) {
                    Timber.w("CustomRepoManager is null")
                }
                // update compat metadata
                if (BuildConfig.DEBUG) Timber.i("Check Update Compat")
                appUpdateManager.checkUpdateCompat()
                if (BuildConfig.DEBUG) Timber.i("Check Update")
                // update repos
                if (hasWebView()) {
                    RepoManager.getINSTANCE().update { value: Double ->
                        runOnUiThread(if (max == 0) Runnable {
                            progressIndicator.setProgressCompat(
                                (value * PRECISION).toInt(),
                                true
                            )
                        } else Runnable {
                            progressIndicator.setProgressCompat(
                                (value * PRECISION * 0.75f).toInt(),
                                true
                            )
                        })
                    }
                }
                // various notifications
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilderOnline)
                NotificationType.DEBUG.autoAdd(moduleViewListBuilder)
                NotificationType.DEBUG.autoAdd(moduleViewListBuilderOnline)
                if (hasWebView() && !NotificationType.REPO_UPDATE_FAILED.shouldRemove()) {
                    moduleViewListBuilder.addNotification(NotificationType.REPO_UPDATE_FAILED)
                } else {
                    if (!hasWebView()) {
                        runOnUiThread {
                            progressIndicator.setProgressCompat(PRECISION, true)
                            progressIndicator.visibility = View.GONE
                            searchView.isEnabled = false
                            updateScreenInsets(resources.configuration)
                        }
                        return
                    }
                    // Compatibility data still needs to be updated
                    val appUpdateManager = appUpdateManager
                    if (BuildConfig.DEBUG) Timber.i("Check App Update")
                    if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                        NotificationType.UPDATE_AVAILABLE
                    )
                    if (BuildConfig.DEBUG) Timber.i("Check Json Update")
                    if (max != 0) {
                        var current = 0
                        for (localModuleInfo in instance!!.modules.values) {
                            // if it has updateJson and FLAG_MM_REMOTE_MODULE is not set on flags, check for json update
                            // this is a dirty hack until we better store if it's a remote module
                            // the reasoning is that remote repos are considered "validated" while local modules are not
                            // for instance, a potential attacker could hijack a perfectly legitimate module and inject an updateJson with a malicious update - thereby bypassing any checks repos may have, without anyone noticing until it's too late
                            if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                                if (BuildConfig.DEBUG) Timber.i(localModuleInfo.id)
                                try {
                                    localModuleInfo.checkModuleUpdate()
                                } catch (e: Exception) {
                                    Timber.e(e)
                                }
                                current++
                                val currentTmp = current
                                runOnUiThread {
                                    progressIndicator.setProgressCompat(
                                        (1f * currentTmp / max * PRECISION * 0.25f + PRECISION * 0.75f).toInt(),
                                        true
                                    )
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG) Timber.i("Apply")
                RepoManager.getINSTANCE()
                    .runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
                moduleViewListBuilder.applyTo(moduleList, moduleViewAdapter!!)
                moduleViewListBuilder.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                moduleViewListBuilderOnline.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                // if moduleViewListBuilderOnline has the upgradeable notification, show a badge on the online repo nav item
                if (MainApplication.INSTANCE!!.modulesHaveUpdates) {
                    Timber.i("Applying badge")
                    Handler(Looper.getMainLooper()).post {
                        val badge = bottomNavigationView.getOrCreateBadge(R.id.online_menu_item)
                        badge.isVisible = true
                        badge.number = MainApplication.INSTANCE!!.updateModuleCount
                        badge.applyTheme(MainApplication.INSTANCE!!.theme)
                        Timber.i("Badge applied")
                    }
                }
                runOnUiThread {
                    progressIndicator.setProgressCompat(PRECISION, true)
                    progressIndicator.visibility = View.GONE
                    searchView.isEnabled = true
                    updateScreenInsets(resources.configuration)
                }
                maybeShowUpgrade()
                Timber.i("Finished app opening state!")
            }
        }, true)
        // if system lang is not in MainApplication.supportedLocales, show a snackbar to ask user to help translate
        if (!MainApplication.supportedLocales.contains(this.resources.configuration.locales[0].language)) {
            // call showWeblateSnackbar() with language code and language name
            runtimeUtils!!.showWeblateSnackbar(
                this,
                this,
                this.resources.configuration.locales[0].language,
                this.resources.configuration.locales[0].displayLanguage
            )
        }
        ExternalHelper.INSTANCE.refreshHelper(this)
        initMode = false
    }

    private fun cardIconifyUpdate() {
        val iconified = searchView!!.isIconified
        val backgroundAttr =
            if (iconified) if (MainApplication.isMonetEnabled) com.google.android.material.R.attr.colorSecondaryContainer else  // Monet is special...
                com.google.android.material.R.attr.colorSecondary else com.google.android.material.R.attr.colorPrimarySurface
        val theme = searchCard!!.context.theme
        val value = TypedValue()
        theme.resolveAttribute(backgroundAttr, value, true)
        searchCard!!.setCardBackgroundColor(value.data)
        searchCard!!.alpha = if (iconified) 0.80f else 1f
    }

    fun updateScreenInsets() {
        runOnUiThread { this.updateScreenInsets(this.resources.configuration) }
    }

    private fun updateScreenInsets(configuration: Configuration) {
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottomInset = if (landscape) 0 else this.navigationBarHeight
        val statusBarHeight = statusBarHeight + FoxDisplay.dpToPixel(2f)
        swipeRefreshLayout!!.setProgressViewOffset(
            false,
            swipeRefreshLayoutOrigStartOffset + statusBarHeight,
            swipeRefreshLayoutOrigEndOffset + statusBarHeight
        )
        moduleViewListBuilder.setHeaderPx(statusBarHeight)
        moduleViewListBuilderOnline.setHeaderPx(statusBarHeight)
        moduleViewListBuilder.setFooterPx(FoxDisplay.dpToPixel(4f) + bottomInset + searchCard!!.height)
        moduleViewListBuilderOnline.setFooterPx(FoxDisplay.dpToPixel(4f) + bottomInset + searchCard!!.height)
        searchCard!!.radius = searchCard!!.height / 2f
        moduleViewListBuilder.updateInsets()
        //this.actionBarBlur.invalidate();
        overScrollInsetTop = statusBarHeight
        overScrollInsetBottom = bottomInset
        // set root_container to have zero padding
        findViewById<View>(R.id.root_container).setPadding(0, statusBarHeight, 0, 0)
    }

    private fun updateBlurState() {
        if (MainApplication.isBlurEnabled) {
            // set bottom navigation bar color to transparent blur
            val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
            if (bottomNavigationView != null) {
                bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
                bottomNavigationView.alpha = 0.8f
            } else {
                Timber.w("Bottom navigation view not found")
            }
            // set dialogs to have transparent blur
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    override fun refreshUI() {
        super.refreshUI()
        if (initMode) return
        initMode = true
        Timber.i("Item Before")
        searchView!!.setQuery("", false)
        searchView!!.clearFocus()
        searchView!!.isIconified = true
        cardIconifyUpdate()
        this.updateScreenInsets()
        updateBlurState()
        moduleViewListBuilder.setQuery(null)
        Timber.i("Item After")
        moduleViewListBuilder.refreshNotificationsUI(moduleViewAdapter!!)
        tryGetMagiskPathAsync(object : InstallerInitializer.Callback {
            override fun onPathReceived(path: String?) {
                val context: Context = this@MainActivity
                val mainActivity = this@MainActivity
                runtimeUtils!!.checkShowInitialSetup(context, mainActivity)
                // Wait for doSetupNow to finish
                while (doSetupNowRunning) {
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND) moduleViewListBuilder.addNotification(
                    NotificationType.MAGISK_OUTDATED
                )
                if (!MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.INSTALL_FROM_STORAGE
                )
                instance!!.scan()
                instance!!.runAfterScan { moduleViewListBuilder.appendInstalledModules() }
                commonNext()
            }

            override fun onFailure(error: Int) {
                Timber.e("Error: %s", error)
                moduleViewListBuilder.addNotification(errorNotification)
                moduleViewListBuilderOnline.addNotification(errorNotification)
                commonNext()
            }

            fun commonNext() {
                Timber.i("Common Before")
                if (MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.SHOWCASE_MODE
                )
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilderOnline)
                NotificationType.NO_INTERNET.autoAdd(moduleViewListBuilderOnline)
                if (appUpdateManager.checkUpdate(false)) moduleViewListBuilder.addNotification(
                    NotificationType.UPDATE_AVAILABLE
                )
                RepoManager.getINSTANCE().updateEnabledStates()
                if (RepoManager.getINSTANCE().customRepoManager.needUpdate()) {
                    runOnUiThread {
                        progressIndicator!!.isIndeterminate = false
                        progressIndicator!!.max = PRECISION
                    }
                    if (BuildConfig.DEBUG) Timber.i("Check Update")
                    RepoManager.getINSTANCE().update { value: Double ->
                        runOnUiThread {
                            progressIndicator!!.setProgressCompat(
                                (value * PRECISION).toInt(),
                                true
                            )
                        }
                    }
                    runOnUiThread {
                        progressIndicator!!.setProgressCompat(PRECISION, true)
                        progressIndicator!!.visibility = View.GONE
                    }
                }
                if (BuildConfig.DEBUG) Timber.i("Apply")
                RepoManager.getINSTANCE()
                    .runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
                Timber.i("Common Before applyTo")
                moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!)
                moduleViewListBuilderOnline.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
                Timber.i("Common After")
            }
        })
        initMode = false
    }

    override fun onWindowUpdated() {
        this.updateScreenInsets()
    }

    override fun onRefresh() {
        if (swipeRefreshBlocker > System.currentTimeMillis() || initMode || progressIndicator == null || progressIndicator!!.visibility == View.VISIBLE || doSetupNowRunning) {
            swipeRefreshLayout!!.isRefreshing = false
            return  // Do not double scan
        }
        if (BuildConfig.DEBUG) Timber.i("Refresh")
        progressIndicator!!.visibility = View.VISIBLE
        progressIndicator!!.setProgressCompat(0, false)
        swipeRefreshBlocker = System.currentTimeMillis() + 5000L
        // this.swipeRefreshLayout.setRefreshing(true); ??
        Thread({
            cleanDnsCache() // Allow DNS reload from network
            val max = instance!!.getUpdatableModuleCount()
            RepoManager.getINSTANCE().update { value: Double ->
                runOnUiThread(if (max == 0) Runnable {
                    progressIndicator!!.setProgressCompat(
                        (value * PRECISION).toInt(),
                        true
                    )
                } else Runnable {
                    progressIndicator!!.setProgressCompat(
                        (value * PRECISION * 0.75f).toInt(),
                        true
                    )
                })
            }
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            if (!NotificationType.NO_INTERNET.shouldRemove()) {
                moduleViewListBuilderOnline.addNotification(NotificationType.NO_INTERNET)
            } else if (!NotificationType.REPO_UPDATE_FAILED.shouldRemove()) {
                moduleViewListBuilder.addNotification(NotificationType.REPO_UPDATE_FAILED)
            } else {
                // Compatibility data still needs to be updated
                val appUpdateManager = appUpdateManager
                if (BuildConfig.DEBUG) Timber.i("Check App Update")
                if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                    NotificationType.UPDATE_AVAILABLE
                )
                if (BuildConfig.DEBUG) Timber.i("Check Json Update")
                if (max != 0) {
                    var current = 0
                    for (localModuleInfo in instance!!.modules.values) {
                        if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                            if (BuildConfig.DEBUG) Timber.i(localModuleInfo.id)
                            try {
                                localModuleInfo.checkModuleUpdate()
                            } catch (e: Exception) {
                                Timber.e(e)
                            }
                            current++
                            val currentTmp = current
                            runOnUiThread {
                                progressIndicator!!.setProgressCompat(
                                    (1f * currentTmp / max * PRECISION * 0.25f + PRECISION * 0.75f).toInt(),
                                    true
                                )
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) Timber.i("Apply")
            runOnUiThread {
                progressIndicator!!.visibility = View.GONE
                swipeRefreshLayout!!.isRefreshing = false
            }
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            RepoManager.getINSTANCE().updateEnabledStates()
            RepoManager.getINSTANCE()
                .runAfterUpdate { moduleViewListBuilder.appendInstalledModules() }
            RepoManager.getINSTANCE()
                .runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
            moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!)
            moduleViewListBuilderOnline.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
        }, "Repo update thread").start()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        searchView!!.clearFocus()
        if (initMode) return false
        TrackHelper.track().search(query).with(MainApplication.INSTANCE!!.tracker)
        if (moduleViewListBuilder.setQueryChange(query)) {
            Timber.i("Query submit: %s on offline list", query)
            Thread(
                { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                "Query update thread"
            ).start()
        }
        // same for online list
        if (moduleViewListBuilderOnline.setQueryChange(query)) {
            Timber.i("Query submit: %s on online list", query)
            Thread({
                moduleViewListBuilderOnline.applyTo(
                    moduleListOnline!!,
                    moduleViewAdapterOnline!!
                )
            }, "Query update thread").start()
        }
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        if (initMode) return false
        TrackHelper.track().search(query).with(MainApplication.INSTANCE!!.tracker)
        if (moduleViewListBuilder.setQueryChange(query)) {
            Timber.i("Query submit: %s on offline list", query)
            Thread(
                { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                "Query update thread"
            ).start()
        }
        // same for online list
        if (moduleViewListBuilderOnline.setQueryChange(query)) {
            Timber.i("Query submit: %s on online list", query)
            Thread({
                moduleViewListBuilderOnline.applyTo(
                    moduleListOnline!!,
                    moduleViewAdapterOnline!!
                )
            }, "Query update thread").start()
        }
        return false
    }

    override fun onClose(): Boolean {
        if (initMode) return false
        if (moduleViewListBuilder.setQueryChange(null)) {
            Thread(
                { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                "Query update thread"
            ).start()
        }
        // same for online list
        if (moduleViewListBuilderOnline.setQueryChange(null)) {
            Thread({
                moduleViewListBuilderOnline.applyTo(
                    moduleListOnline!!,
                    moduleViewAdapterOnline!!
                )
            }, "Query update thread").start()
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.updateScreenInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.updateScreenInsets()
    }

    fun maybeShowUpgrade() {
        if (AndroidacyRepoData.getInstance() == null || AndroidacyRepoData.getInstance().memberLevel == null) {
            // wait for up to 10 seconds for AndroidacyRepoData to be initialized
            var i = 0
            while (AndroidacyRepoData.getInstance() == null && i < 10) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    Timber.e(e)
                }
                i++
            }
            if (AndroidacyRepoData.getInstance()
                    .isEnabled && AndroidacyRepoData.getInstance().memberLevel == null
            ) {
                Timber.d("Member level is null, waiting for it to be initialized")
                i = 0
                while (AndroidacyRepoData.getInstance().memberLevel == null && i < 20) {
                    i++
                    try {
                        Thread.sleep(500)
                    } catch (e: InterruptedException) {
                        Timber.e(e)
                    }
                }
            }
            // if it's still null, but it's enabled, throw an error
            if (AndroidacyRepoData.getInstance()
                    .isEnabled && AndroidacyRepoData.getInstance().memberLevel == null
            ) {
                Timber.e("AndroidacyRepoData is enabled, but member level is null")
            }
            if (AndroidacyRepoData.getInstance() != null && AndroidacyRepoData.getInstance()
                    .isEnabled && AndroidacyRepoData.getInstance().memberLevel == "Guest"
            ) {
                runtimeUtils!!.showUpgradeSnackbar(this, this)
            } else {
                if (!AndroidacyRepoData.getInstance().isEnabled) {
                    Timber.i("AndroidacyRepoData is disabled, not showing upgrade snackbar 1")
                } else if (AndroidacyRepoData.getInstance().memberLevel != "Guest") {
                    Timber.i(
                        "AndroidacyRepoData is not Guest, not showing upgrade snackbar 1. Level: %s",
                        AndroidacyRepoData.getInstance().memberLevel
                    )
                } else {
                    Timber.i("Unknown error, not showing upgrade snackbar 1")
                }
            }
        } else if (AndroidacyRepoData.getInstance()
                .isEnabled && AndroidacyRepoData.getInstance().memberLevel == "Guest"
        ) {
            runtimeUtils!!.showUpgradeSnackbar(this, this)
        } else {
            if (!AndroidacyRepoData.getInstance().isEnabled) {
                Timber.i("AndroidacyRepoData is disabled, not showing upgrade snackbar 2")
            } else if (AndroidacyRepoData.getInstance().memberLevel != "Guest") {
                Timber.i(
                    "AndroidacyRepoData is not Guest, not showing upgrade snackbar 2. Level: %s",
                    AndroidacyRepoData.getInstance().memberLevel
                )
            } else {
                Timber.i("Unknown error, not showing upgrade snackbar 2")
            }
        }
    }

    companion object {
        fun getFoxActivity(activity: FoxActivity): FoxActivity {
            return activity
        }

        fun getFoxActivity(context: Context): FoxActivity {
            return context as FoxActivity
        }

        private const val PRECISION = 10000
        @JvmField
        var doSetupNowRunning = true
        var doSetupRestarting = false
        var localModuleInfoList: List<LocalModuleInfo> = ArrayList()
        var onlineModuleInfoList: List<RepoModule> = ArrayList()
        var isShowingWeblateSb = false // race condition
    }
}