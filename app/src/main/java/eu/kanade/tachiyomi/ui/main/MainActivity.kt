package eu.kanade.tachiyomi.ui.main

import android.app.SearchManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.GestureDetectorCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import com.bluelinelabs.conductor.changehandler.FadeChangeHandler
import com.bluelinelabs.conductor.changehandler.SimpleSwapChangeHandler
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.Migrations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.DownloadServiceListener
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.CatalogueController
import eu.kanade.tachiyomi.ui.catalogue.global_search.CatalogueSearchController
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.extension.ExtensionController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.library.LibraryListController
import eu.kanade.tachiyomi.ui.manga.MangaChaptersController
import eu.kanade.tachiyomi.ui.recent_updates.RecentChaptersController
import eu.kanade.tachiyomi.ui.recently_read.RecentlyReadController
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.setting.SettingsMainController
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.updateLayoutParams
import eu.kanade.tachiyomi.util.view.updatePadding
import eu.kanade.tachiyomi.util.view.visible
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

open class MainActivity : BaseActivity(), DownloadServiceListener {

    protected lateinit var router: Router

    protected var drawerArrow: DrawerArrowDrawable? = null
    private var currentGestureDelegate:SwipeGestureInterface? = null
    private lateinit var gestureDetector:GestureDetectorCompat

    protected open var trulyGoBack = false

    private var secondaryDrawer: ViewGroup? = null

    private var snackBar:Snackbar? = null
    private var extraViewForUndo:View? = null
    private var canDismissSnackBar = false

    fun setUndoSnackBar(snackBar: Snackbar?, extraViewToCheck: View? = null) {
        this.snackBar = snackBar
        canDismissSnackBar = false
        launchUI {
            delay(2000)
            if (this@MainActivity.snackBar == snackBar) {
                canDismissSnackBar = true
            }
        }
        extraViewForUndo = extraViewToCheck
    }

    lateinit var tabAnimator: TabsAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        // Create a webview before extensions do or else they will break night mode theme
        // https://stackoverflow.com/questions/54191883
        Timber.d("Manually instantiating WebView to avoid night mode issue.")
        try {
            WebView(applicationContext)
        } catch (e: Exception) {
            Timber.e(e, "Exception when creating webview at start")
        }
        super.onCreate(savedInstanceState)
        if (trulyGoBack) return

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }
        gestureDetector = GestureDetectorCompat(this, GestureListener())

        setContentView(R.layout.main_activity)

        setSupportActionBar(toolbar)

        drawerArrow = DrawerArrowDrawable(this)
        drawerArrow?.color = getResourceColor(R.attr.actionBarTintColor)
        toolbar.navigationIcon = drawerArrow

       // tabAnimator = TabsAnimator(tabs)

        var continueSwitchingTabs = false
        navigationView.setOnNavigationItemSelectedListener { item ->
            val id = item.itemId
            val currentController = router.backstack.lastOrNull()?.controller()
            if (!continueSwitchingTabs && currentController is BottomNavBarInterface) {
                if (!currentController.canChangeTabs {
                    continueSwitchingTabs = true
                    this@MainActivity.navigationView.selectedItemId = id
                }) return@setOnNavigationItemSelectedListener false
            }
            continueSwitchingTabs = false
            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_library -> setRoot(
                        if (preferences.libraryAsSingleList().getOrDefault())
                            LibraryListController()
                        else
                            LibraryController(), id)
                    R.id.nav_recents ->  {
                        if (preferences.showRecentUpdates().getOrDefault())
                            setRoot(RecentChaptersController(), id)
                        else
                            setRoot(RecentlyReadController(), id)
                    }
                    R.id.nav_catalogues -> setRoot(CatalogueController(), id)
                    R.id.nav_settings -> setRoot(SettingsMainController(), id)
                }
            }
            else if (currentRoot.tag()?.toIntOrNull() == id)  {
                when (id) {
                    R.id.nav_recents -> {
                        if (router.backstack.size > 1) router.popToRoot()
                        else {
                            val showRecents = preferences.showRecentUpdates().getOrDefault()
                            if (!showRecents) setRoot(RecentChaptersController(), id)
                            else setRoot(RecentlyReadController(), id)
                            preferences.showRecentUpdates().set(!showRecents)
                            updateRecentsIcon()
                        }
                    }
                    R.id.nav_library -> {
                        if (router.backstack.size > 1) router.popToRoot()
                        else {
                            val controller = router.getControllerWithTag(id.toString()) as?
                                LibraryController
                            controller?.showFiltersBottomSheet()
                        }
                    }
                    R.id.nav_catalogues, R.id.nav_settings -> router.popToRoot()
                }
            }
            true
        }
        val container: ViewGroup = findViewById(R.id.controller_container)

        val content: ViewGroup = findViewById(R.id.main_content)
        //val dwawerContainer: ViewGroup = findViewById(R.id.drawer_container)
        DownloadService.addListener(this)
        content.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        /*dwawerContainer.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION*/
        updateRecentsIcon()
        content.viewTreeObserver.addOnGlobalLayoutListener {
            val heightDiff: Int = content.rootView.height - content.height
            if (heightDiff > 200 &&
                window.attributes.softInputMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                //keyboard is open, hide layout
                navigationView.gone()
            } else if (navigationView.visibility == View.GONE
                && window.attributes.softInputMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                //keyboard is hidden, show layout
                // use coroutine to delay so the bottom bar doesn't flash on top of the keyboard
                launchUI {
                    navigationView.visible()
                }
            }
        }

        supportActionBar?.setDisplayShowCustomEnabled(true)

        window.statusBarColor = getResourceColor(android.R.attr.colorPrimary)
        content.setOnApplyWindowInsetsListener { v, insets ->
                // if device doesn't support light nav bar
            window.navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    // basically if in landscape on a phone
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        (v.rootWindowInsets.systemWindowInsetLeft > 0 ||
                            v.rootWindowInsets.systemWindowInsetRight > 0))
                        // For lollipop, draw opaque nav bar
                        Color.BLACK
                    else Color.argb(179, 0, 0, 0)
                }
                /*else {
                    getColor(android.R.color.transparent)
                }*/
                // if the android q+ device has gesture nav, transparent nav bar
                // this is here incase some crazy with a notch uses landscape
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && (v.rootWindowInsets.systemWindowInsetBottom != v.rootWindowInsets
                    .tappableElementInsets.bottom)) {
                    getColor(android.R.color.transparent)
                }
                // if in landscape with 2/3 button mode, fully opaque nav bar
                else if (v.rootWindowInsets.systemWindowInsetLeft > 0
                    || v.rootWindowInsets.systemWindowInsetRight > 0) {
                    getResourceColor( android.R.attr.colorPrimary )
                }
                // if in portrait with 2/3 button mode, translucent nav bar
                else {
                    ColorUtils.setAlphaComponent(
                        getResourceColor(android.R.attr.colorPrimary), 179)
                }
            val contextView = window?.decorView?.findViewById<View>(R.id.action_mode_bar)
            contextView?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = insets.systemWindowInsetLeft
                rightMargin = insets.systemWindowInsetRight
            }
            // Consume any horizontal insets and pad all content in. There's not much we can do
            // with horizontal insets
            v.updatePadding(
                left = insets.systemWindowInsetLeft,
                right = insets.systemWindowInsetRight
            )
            appbar.updatePadding(
                top = insets.systemWindowInsetTop
            )
            navigationView.updatePadding(bottom = insets.systemWindowInsetBottom)

            /*controller_container.updateLayoutParams<ConstraintLayout.LayoutParams> {
                val attrsArray = intArrayOf(android.R.attr.actionBarSize)
                val array = v.context.obtainStyledAttributes(attrsArray)
                topMargin = insets.systemWindowInsetTop + array.getDimensionPixelSize(0, 0)
                array.recycle()
            }*/

            /*nav_bar_scrim.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                height = 0//insets.systemWindowInsetBottom
            }*/
            insets.replaceSystemWindowInsets(
                0, insets.systemWindowInsetTop,
                0, insets.systemWindowInsetBottom
            )

            insets
        }
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (Build.VERSION.SDK_INT >= 26 && currentNightMode == Configuration.UI_MODE_NIGHT_NO &&
            preferences.theme() >= 8) {
            content.systemUiVisibility = content.systemUiVisibility.or(View
                .SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentNightMode == Configuration
                .UI_MODE_NIGHT_NO && preferences.theme() >= 8)
            content.systemUiVisibility = content.systemUiVisibility.or(View
                .SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)

        //val drawerContainer: FrameLayout = findViewById(R.id.drawer_container)

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                navigationView.selectedItemId = R.id.nav_library
            }
        }

        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        navigationView.visibility = if (router.backstackSize > 1) View.GONE else View.VISIBLE
        router.addChangeListener(object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(to: Controller?, from: Controller?, isPush: Boolean,
                                         container: ViewGroup, handler: ControllerChangeHandler) {

                syncActivityViewWithController(to, from)
                navigationView.visibility = if (router.backstackSize > 1) View.GONE else View.VISIBLE
            }

            override fun onChangeCompleted(to: Controller?, from: Controller?, isPush: Boolean,
                                           container: ViewGroup, handler: ControllerChangeHandler) {
            }

        })

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        if (savedInstanceState == null) {
            // Show changelog if needed
            if (Migrations.upgrade(preferences)) {
                if (BuildConfig.DEBUG) {
                    MaterialDialog(this)
                        .title(text = "Welcome to the J2K MD2 Beta")
                        .message(text = "This beta is for testing the upcoming " +
                            "release. Requests for new additions this beta will ignored (however" +
                            " suggestions on how to better implement a feature in this beta are " +
                            "welcome).\n\nFor any bugs you come across, there is a bug report " +
                            "button in settings.\n\nAs a reminder this is a *BETA* build and bugs" +
                            " may happen and features may be missing/not implemented yet." +
                            "\n\nEnjoy and thanks for testing!")
                        .positiveButton(android.R.string.ok)
                        .cancelOnTouchOutside(false)
                        .show()
                }
                else ChangelogDialogController().showDialog(router)
            }
        }
        preferences.extensionUpdatesCount().asObservable().subscribe {
            setExtensionsBadge()
        }
        setExtensionsBadge()
    }

    fun updateRecentsIcon() {
        navigationView.menu.findItem(R.id.nav_recents).icon =
            AppCompatResources.getDrawable(this,
                if (preferences.showRecentUpdates().getOrDefault()) R.drawable.ic_update_black_24dp
                else R.drawable.ic_history_black_24dp)
    }

    override fun startSupportActionMode(callback: androidx.appcompat.view.ActionMode.Callback): androidx.appcompat.view.ActionMode? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
            window?.statusBarColor = getResourceColor(R.attr.colorPrimary)
        return super.startSupportActionMode(callback)
    }

    override fun onSupportActionModeFinished(mode: androidx.appcompat.view.ActionMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) launchUI {
            val scale = Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
            )
            val duration = resources.getInteger(android.R.integer.config_mediumAnimTime) * scale
            delay(duration.toLong())
            delay(100)
            window?.statusBarColor = getResourceColor(android.R.attr.statusBarColor)
        }
        super.onSupportActionModeFinished(mode)
    }

    private fun setExtensionsBadge() {
        val updates = preferences.extensionUpdatesCount().getOrDefault()
        if (updates > 0) {
            val badge = navigationView.getOrCreateBadge(R.id.nav_settings)
            badge.number = updates
            badge.backgroundColor = getResourceColor(R.attr.badgeColor)
            badge.badgeTextColor = Color.WHITE
        }
        else {
            navigationView.removeBadge(R.id.nav_settings)
        }
    }

    override fun onResume() {
        super.onResume()
        // setting in case someone comes from the search activity to main
        usingBottomNav = true
        getExtensionUpdates()
        DownloadService.callListeners()
    }

    private fun getExtensionUpdates() {
        if (Date().time >= preferences.lastExtCheck().getOrDefault() +
            TimeUnit.HOURS.toMillis(1)) {
            GlobalScope.launch(Dispatchers.IO) {
                val preferences: PreferencesHelper by injectLazy()
                try {
                    val pendingUpdates = ExtensionGithubApi().checkforUpdates(this@MainActivity)
                    preferences.extensionUpdatesCount().set(pendingUpdates.size)
                    preferences.lastExtCheck().set(Date().time)
                } catch (e: java.lang.Exception) { }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    protected open fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) NotificationReceiver.dismissNotification(
            applicationContext, notificationId, intent.getIntExtra("groupId", 0)
        )
        when (intent.action) {
            SHORTCUT_LIBRARY -> navigationView.selectedItemId = R.id.nav_library
            SHORTCUT_RECENTLY_UPDATED, SHORTCUT_RECENTLY_READ -> {
                preferences.showRecentUpdates().set(intent.action == SHORTCUT_RECENTLY_UPDATED)
                navigationView.selectedItemId = R.id.nav_recents
                updateRecentsIcon()
            }
            SHORTCUT_CATALOGUES -> navigationView.selectedItemId = R.id.nav_catalogues
            SHORTCUT_EXTENSIONS -> {
                if (router.backstack.none { it.controller() is ExtensionController }) {
                    if (router.backstack.isEmpty()) {
                        navigationView.selectedItemId = R.id.nav_library
                        router.pushController(
                            RouterTransaction.with(ExtensionController()).pushChangeHandler(
                                SimpleSwapChangeHandler()
                            ).popChangeHandler(FadeChangeHandler())
                        )
                    } else {
                        router.pushController(ExtensionController().withFadeTransaction())
                    }
                }
            }
            SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                if (router.backstack.isEmpty()) {
                    navigationView.selectedItemId = R.id.nav_library
                }
                router.pushController(MangaChaptersController(extras).withFadeTransaction())
            }
            SHORTCUT_DOWNLOADS -> {
                if (router.backstack.none { it.controller() is DownloadController }) {
                    if (router.backstack.isEmpty()) {
                        navigationView.selectedItemId = R.id.nav_library
                        router.pushController(RouterTransaction.with(DownloadController())
                            .pushChangeHandler(SimpleSwapChangeHandler())
                            .popChangeHandler(FadeChangeHandler()))
                    }
                    else {
                        router.pushController(DownloadController().withFadeTransaction())
                    }
                }
            }
            Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                //If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                //Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(CatalogueSearchController(query).withFadeTransaction())
                }
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(CatalogueSearchController(query, filter).withFadeTransaction())
                }
            }
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadService.removeListener(this)
        toolbar?.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        if (trulyGoBack) {
            super.onBackPressed()
            return
        }
        /*if (drawer.isDrawerOpen(GravityCompat.START) || drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawers()
        } else  {*/
            val baseController = router.backstack.last().controller() as? BaseController
            if (if (router.backstackSize == 1) !(baseController?.handleRootBack() ?: false)
                else !router.handleBack()) {
                SecureActivityDelegate.locked = true
                super.onBackPressed()
            }
        //}
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            if (snackBar != null && snackBar!!.isShown) {
                val sRect = Rect()
                snackBar!!.view.getGlobalVisibleRect(sRect)

                val extRect:Rect? = if (extraViewForUndo != null) Rect() else null
                extraViewForUndo?.getGlobalVisibleRect(extRect)
                //This way the snackbar will only be dismissed if
                //the user clicks outside it.
                if (canDismissSnackBar && !sRect.contains(ev.x.toInt(), ev.y.toInt())
                    && (extRect == null ||
                        !extRect.contains(ev.x.toInt(), ev.y.toInt()))) {
                    snackBar?.dismiss()
                    snackBar = null
                    extraViewForUndo = null
                }
            }
            else if (snackBar != null) {
                snackBar = null
                extraViewForUndo = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    protected open fun syncActivityViewWithController(to: Controller?, from: Controller? = null) {
        if (from is DialogController || to is DialogController) {
            return
        }
        val onRoot = router.backstackSize == 1
        if (onRoot) {
            toolbar.navigationIcon = null
        } else {
            toolbar.navigationIcon = drawerArrow
        }
        drawerArrow?.progress = 1f

       /* if (from is TabbedController) {
            from.cleanupTabs(tabs)
        }
        if (to is TabbedController) {
            tabAnimator.expand()
            to.configureTabs(tabs)
        } else {
            tabAnimator.collapse()
            tabs.setupWithViewPager(null)
        }*/

        currentGestureDelegate = to as? SwipeGestureInterface

        /*if (from is SecondaryDrawerController) {
            if (secondaryDrawer != null) {
                from.cleanupSecondaryDrawer(drawer)
                drawer.removeView(secondaryDrawer)
                secondaryDrawer = null
            }
        }
        if (to is SecondaryDrawerController) {
            val newDrawer = to.createSecondaryDrawer(drawer)?.also { drawer.addView(it) }
            secondaryDrawer = if (newDrawer == null && secondaryDrawer != null) {
                drawer.removeView(secondaryDrawer)
                null
            } else newDrawer
        }*/

        if (to is NoToolbarElevationController) {
            appbar.disableElevation()
        } else {
            appbar.enableElevation()
        }
    }

    override fun downloadStatusChanged(downloading: Boolean) {
        val downloadManager = Injekt.get<DownloadManager>()
        val hasQueue = downloading || downloadManager.hasQueue()
        launchUI {
            if (hasQueue) {
                val badge = navigationView?.getOrCreateBadge(R.id.nav_library) ?: return@launchUI
                badge.clearNumber()
                badge.backgroundColor = getResourceColor(R.attr.badgeColor)
            } else {
                navigationView?.removeBadge(R.id.nav_library)
            }
        }
    }


    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            return true
        }

        override fun onFling(
            e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (currentGestureDelegate == null) return false
            var result = false
            try {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > Companion.SWIPE_THRESHOLD &&
                        abs(velocityX) > Companion.SWIPE_VELOCITY_THRESHOLD
                        && abs(diffY) <= Companion.SWIPE_THRESHOLD * 0.75f
                    ) {
                        if (diffX > 0) {
                            currentGestureDelegate?.onSwipeRight(e1.x,  e1.y)
                        } else {
                            currentGestureDelegate?.onSwipeLeft(e1.x,  e1.y)
                        }
                        result = true
                    }
                } else if (abs(diffY) > Companion.SWIPE_THRESHOLD && abs(
                        velocityY
                    ) > Companion.SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffY > 0) {
                        currentGestureDelegate?.onSwipeBottom(e1.x,  e1.y)
                        //onSwipeBottom()
                    } else {
                        currentGestureDelegate?.onSwipeTop(e1.x,  e1.y)
                    }
                    result = true
                }
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
            return result
        }

    }

    companion object {

        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_CATALOGUES = "eu.kanade.tachiyomi.SHOW_CATALOGUES"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"
        const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"

        var usingBottomNav = true
            internal set
    }
}

interface BottomNavBarInterface {
    fun canChangeTabs(block: () -> Unit): Boolean
}

interface SwipeGestureInterface {
    fun onSwipeRight(x: Float, y: Float)
    fun onSwipeLeft(x: Float, y: Float)
    fun onSwipeTop(x: Float, y: Float)
    fun onSwipeBottom(x: Float, y: Float)
}