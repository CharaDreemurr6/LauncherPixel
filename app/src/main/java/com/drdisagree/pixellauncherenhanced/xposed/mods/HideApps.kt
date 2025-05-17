package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.ComponentName
import android.content.Context
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_BLOCK_LIST
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SEARCH_HIDDEN_APPS
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getExtraFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setExtraField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.reflect.Modifier
import java.util.Arrays

class HideApps(context: Context) : ModPack(context) {

    private var appBlockList: Set<String> = mutableSetOf()
    private var searchHiddenApps: Boolean = false
    private var invariantDeviceProfileInstance: Any? = null
    private var activityAllAppsContainerViewInstance: Any? = null
    private var hotseatPredictionControllerInstance: Any? = null
    private var predictionRowViewInstance: Any? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            appBlockList = getStringSet(APP_BLOCK_LIST, emptySet())!!
            searchHiddenApps = getBoolean(SEARCH_HIDDEN_APPS, false)
        }

        when (key.firstOrNull()) {
            APP_BLOCK_LIST -> updateLauncherIcons()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        val activityAllAppsContainerViewClass =
            findClass("com.android.launcher3.allapps.ActivityAllAppsContainerView")
        val hotseatPredictionControllerClass =
            findClass("com.android.launcher3.hybridhotseat.HotseatPredictionController")
        val predictionRowViewClass =
            findClass("com.android.launcher3.appprediction.PredictionRowView")
        val defaultAppSearchAlgorithmClass = findClass(
            "com.android.launcher3.allapps.DefaultAppSearchAlgorithm",
            "com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm"
        )
        val alphabeticalAppsListClass =
            findClass("com.android.launcher3.allapps.AlphabeticalAppsList")
        val allAppsStoreClass = findClass("com.android.launcher3.allapps.AllAppsStore")
        val appInfoClass = findClass("com.android.launcher3.model.data.AppInfo")
        val allAppsListClass = findClass("com.android.launcher3.model.AllAppsList")
        val launcherModelClass = findClass("com.android.launcher3.LauncherModel")

        invariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param -> invariantDeviceProfileInstance = param.thisObject }

        activityAllAppsContainerViewClass
            .hookConstructor()
            .runAfter { param -> activityAllAppsContainerViewInstance = param.thisObject }

        hotseatPredictionControllerClass
            .hookConstructor()
            .runAfter { param -> hotseatPredictionControllerInstance = param.thisObject }

        predictionRowViewClass
            .hookConstructor()
            .runAfter { param -> predictionRowViewInstance = param.thisObject }

        allAppsStoreClass
            .hookMethod("setApps")
            .runAfter { param ->
                val apps = param.args[0]

                if (apps != null) {
                    param.thisObject.setExtraField("mAppsBackup", apps)
                }
            }

        @Suppress("UNCHECKED_CAST")
        allAppsStoreClass
            .hookMethod("getApp")
            .runBefore { param ->
                val componentKey = param.args[0]
                val comparator = if (param.args.size > 1) {
                    param.args[1]
                } else {
                    appInfoClass.getStaticField("COMPONENT_KEY_COMPARATOR")
                } as Comparator<Any?>

                val mApps = param.thisObject.getExtraFieldSilently("mAppsBackup") as? Array<*>
                    ?: return@runBefore

                val componentName = componentKey.getFieldSilently("componentName") as? ComponentName
                val user = componentKey.getFieldSilently("user")

                val appInfo = param.thisObject.getField("mTempInfo").apply {
                    setField("componentName", componentName)
                    setField("user", user)
                }

                val binarySearch = Arrays.binarySearch<Any?>(mApps, appInfo, comparator)

                if (binarySearch < 0 || (!searchHiddenApps && matchesBlocklist(componentName))) {
                    param.result = null
                } else {
                    param.result = mApps[binarySearch]
                }
            }

        predictionRowViewClass
            .hookMethod("applyPredictionApps")
            .runBefore { param ->
                val mPredictedApps =
                    (param.thisObject.getField("mPredictedApps") as ArrayList<*>).toMutableList()

                val iterator = mPredictedApps.iterator()

                while (iterator.hasNext()) {
                    val workspaceItemInfo = iterator.next()
                    val componentName = workspaceItemInfo.getComponentName()

                    if (matchesBlocklist(componentName)) {
                        iterator.remove()
                    }
                }

                param.thisObject.setField("mPredictedApps", ArrayList(mPredictedApps))
            }

        hotseatPredictionControllerClass
            .hookMethod("fillGapsWithPrediction")
            .parameters(Boolean::class.java)
            .runBefore { param ->
                val mPredictedItems =
                    (param.thisObject.getField("mPredictedItems") as List<*>).toMutableList()

                val iterator = mPredictedItems.iterator()

                while (iterator.hasNext()) {
                    val itemInfo = iterator.next()
                    val componentName = itemInfo.getComponentName()

                    if (matchesBlocklist(componentName)) {
                        iterator.remove()
                    }
                }
            }

        try {
            defaultAppSearchAlgorithmClass
                .hookMethod("getTitleMatchResult")
                .throwError()
                .runBefore { param ->
                    if (searchHiddenApps) return@runBefore

                    var index = if (param.args[0] is Context) 1 else 0
                    val apps = (param.args[index] as List<*>).toMutableList()

                    val iterator = apps.iterator()

                    while (iterator.hasNext()) {
                        val appInfo = iterator.next()
                        val componentName = appInfo.getComponentName()

                        if (matchesBlocklist(componentName)) {
                            iterator.remove()
                        }
                    }

                    param.args[index] = ArrayList(apps)
                }
        } catch (_: Throwable) {
            // Method seems to be unused on newer versions of pixel launcher
            // But still hook it just in case

            fun removeAppResult(param: XC_MethodHook.MethodHookParam) {
                if (searchHiddenApps) return

                val appsIndex = param.args.indexOfFirst {
                    it::class.java.simpleName == allAppsListClass!!.simpleName
                }

                val apps = param.args[appsIndex]
                val data = apps.getField("data") as ArrayList<*>

                val iterator = data.iterator()

                while (iterator.hasNext()) {
                    val appInfo = iterator.next()
                    val componentName = appInfo.getComponentName()

                    if (matchesBlocklist(componentName)) {
                        iterator.remove()
                    }
                }

                apps.setField("data", ArrayList(data))
                param.args[appsIndex] = apps
            }

            val methodName = (defaultAppSearchAlgorithmClass!!.declaredMethods.toList()
                .union(defaultAppSearchAlgorithmClass.methods.toList()))
                .firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                            method.parameterTypes.any { it == String::class.java } &&
                            method.parameterTypes.any { it.simpleName == allAppsListClass!!.simpleName } &&
                            method.parameterCount >= 2
                }?.name

            if (methodName != null) {
                defaultAppSearchAlgorithmClass
                    .hookMethod(methodName)
                    .runBefore { param ->
                        removeAppResult(param)
                    }
            } else {
                val baseModelUpdateTaskClass =
                    findClass("com.android.launcher3.model.BaseModelUpdateTask")

                launcherModelClass
                    .hookMethod("enqueueModelUpdateTask")
                    .runBefore { param ->
                        val modelUpdateTask = param.args[0]

                        if (modelUpdateTask::class.java.simpleName != baseModelUpdateTaskClass!!.simpleName) return@runBefore

                        modelUpdateTask::class.java
                            .hookMethod("execute")
                            .runBefore { param2 ->
                                removeAppResult(param2)
                            }
                    }
            }
        }

        alphabeticalAppsListClass
            .hookMethod("onAppsUpdated")
            .runBefore { param ->
                updateAllAppsStore(param, appInfoClass!!)
            }
            .runAfter { param ->
                val mAdapterItems =
                    (param.thisObject.getField("mAdapterItems") as ArrayList<*>).toMutableList()

                val iterator = mAdapterItems.iterator()

                while (iterator.hasNext()) {
                    val item = iterator.next()
                    val itemInfo = item.getFieldSilently("itemInfo")
                    val componentName = itemInfo.getComponentName()

                    if (matchesBlocklist(componentName)) {
                        iterator.remove()
                    }
                }

                param.thisObject.setField("mAdapterItems", ArrayList(mAdapterItems))
            }
    }

    private fun updateAllAppsStore(
        param: XC_MethodHook.MethodHookParam,
        appInfoClass: Class<*>
    ) {
        val mAllAppsStore = param.thisObject.getFieldSilently("mAllAppsStore") ?: return

        try {
            val mComponentToAppMap = try {
                mAllAppsStore.getField("mComponentToAppMap") as HashMap<*, *>
            } catch (_: Throwable) {
                throw IllegalStateException("mComponentToAppMap is null")
            }

            mComponentToAppMap.keys.forEach { key ->
                val appInfo = mComponentToAppMap[key]
                val componentName = appInfo.getComponentName()

                if (matchesBlocklist(componentName)) {
                    mComponentToAppMap.remove(key)
                }
            }

            mAllAppsStore.setField("mComponentToAppMap", mComponentToAppMap)
        } catch (_: Throwable) {
            val mApps = try {
                (mAllAppsStore.getField("mApps") as Array<*>).toMutableList()
            } catch (_: Throwable) {
                return
            }

            val iterator = mApps.iterator()

            while (iterator.hasNext()) {
                val appInfo = iterator.next()
                val componentName = appInfo.getComponentName()

                if (matchesBlocklist(componentName)) {
                    iterator.remove()
                }
            }

            val appInfoArray = java.lang.reflect.Array.newInstance(
                appInfoClass,
                mApps.size
            ) as Array<*>
            System.arraycopy(mApps.toTypedArray(), 0, appInfoArray, 0, mApps.size)

            mAllAppsStore.setField("mApps", appInfoArray)
        }
    }

    private fun Any?.getComponentName(): ComponentName {
        if (this == null) return ComponentName("", "")

        return getFieldSilently("componentName") as? ComponentName
            ?: getFieldSilently("mComponentName") as? ComponentName
            ?: callMethod("getTargetComponent") as ComponentName
    }

    private fun matchesBlocklist(componentName: ComponentName?): Boolean {
        return matchesBlocklist(componentName?.packageName)
    }

    private fun matchesBlocklist(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return appBlockList.contains(packageName)
    }

    private fun updateLauncherIcons() {
        activityAllAppsContainerViewInstance.callMethod("onAppsUpdated")
        hotseatPredictionControllerInstance.callMethod("fillGapsWithPrediction", true)
        predictionRowViewInstance.callMethod("applyPredictionApps")
        reloadLauncher(mContext)
    }
}