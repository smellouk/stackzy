package com.theapache64.stackzy.ui.feature.appdetail

import com.github.theapache64.gpa.model.Account
import com.theapache64.stackzy.data.local.AnalysisReport
import com.theapache64.stackzy.data.local.Platform
import com.theapache64.stackzy.data.local.toResult
import com.theapache64.stackzy.data.remote.Config
import com.theapache64.stackzy.data.remote.Library
import com.theapache64.stackzy.data.remote.Result
import com.theapache64.stackzy.data.remote.UntrackedLibrary
import com.theapache64.stackzy.data.repo.*
import com.theapache64.stackzy.data.util.calladapter.flow.Resource
import com.theapache64.stackzy.model.AnalysisReportWrapper
import com.theapache64.stackzy.model.AndroidAppWrapper
import com.theapache64.stackzy.model.AndroidDeviceWrapper
import com.theapache64.stackzy.model.LibraryWrapper
import com.theapache64.stackzy.util.ApkSource
import com.theapache64.stackzy.util.R
import com.toxicbakery.logging.Arbor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.net.URI
import javax.inject.Inject
import kotlin.math.roundToInt


class AppDetailViewModel @Inject constructor(
    private val adbRepo: AdbRepo,
    private val apkToolRepo: ApkToolRepo,
    private val apkAnalyzerRepo: ApkAnalyzerRepo,
    private val librariesRepo: LibrariesRepo,
    private val untrackedLibsRepo: UntrackedLibsRepo,
    private val playStoreRepo: PlayStoreRepo,
    private val resultRepo: ResultRepo,
    private val configRepo: ConfigRepo
) {

    companion object {
        val TABS = mutableListOf(
            "Libraries",
            "Details"
        )
    }

    private lateinit var viewModelScope: CoroutineScope
    private lateinit var androidAppWrapper: AndroidAppWrapper
    private lateinit var apkSource: ApkSource<AndroidDeviceWrapper, Account>
    private var decompiledDir: File? = null
    private lateinit var config: Config
    private val _fatalError = MutableStateFlow<String?>(null)
    val fatalError: StateFlow<String?> = _fatalError

    private val _analysisReport = MutableStateFlow<AnalysisReportWrapper?>(null)
    val analysisReport: StateFlow<AnalysisReportWrapper?> = _analysisReport

    private val _loadingMessage = MutableStateFlow<String?>(null)
    val loadingMessage: StateFlow<String?> = _loadingMessage

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex

    fun init(
        scope: CoroutineScope,
        apkSource: ApkSource<AndroidDeviceWrapper, Account>,
        androidApp: AndroidAppWrapper,
    ) {
        this.viewModelScope = scope
        this.config = configRepo.getLocalConfig()!! // shouldn't be null
        this.apkSource = apkSource
        this.androidAppWrapper = androidApp
    }

    /**
     * To start the decompiling and analysis from given source
     */
    fun startDecompile() {

        viewModelScope.launch {
            if (androidAppWrapper.versionCode != null && config.shouldConsiderResultCache) {
                // We've version code here, so we can check results to see if this app has already decompiled by anyone
                resultRepo.findResult(
                    androidAppWrapper.appPackage.name,
                    androidAppWrapper.versionCode!!,
                    config.latestStackzyLibVersion
                )
                    .collect {
                        when (it) {
                            is Resource.Loading -> {
                                _loadingMessage.value = "Analysing previous results..."
                            }
                            is Resource.Success -> {
                                // Found
                                onResultFound(it.data)
                            }
                            is Resource.Error -> {
                                // Not found
                                if (it.errorData == "No data found") {
                                    // Its a new app, no one didn't decompiled it before
                                    Arbor.d("Decompiling it from scratch...")
                                    startDecompileFromScratch()
                                } else {
                                    // Some network error
                                    _fatalError.value = it.errorData
                                }
                            }
                        }
                    }
            } else {
                // We don't have versionCode, so we've to decompile it from scratch
                startDecompileFromScratch()
            }
        }
    }

    private suspend fun onResultFound(
        result: Result
    ) {
        // Convert result to AnalysisReport
        val report = AnalysisReport(
            appName = result.appName,
            packageName = result.packageName,
            platform = Platform.fromClassName(result.platform),
            libraries = getLibrariesFromPackages(result.libPackages),
            untrackedLibraries = setOf(),
            apkSizeInMb = result.apkSizeInMb,
            assetsDir = null,
            permissions = getFullPermissionsFromPermissions(result.permissions),
            gradleInfo = resultRepo.parseGradleInfo(result.gradleInfoJson)!! // this shouldn't be null
        )

        onReportReady(report)
    }


    private fun getFullPermissionsFromPermissions(permissions: String?): List<String> {
        return permissions?.split(",")?.filter { it.isNotBlank() } ?: listOf()
    }

    private fun getLibrariesFromPackages(libPackages: String): List<Library> {
        val packages = libPackages.split(",")
            .map { it.trim() }

        return librariesRepo.getCachedLibraries()?.filter { packages.contains(it.packageName) } ?: listOf()
    }

    /**
     * To start decompile from scratch (download APK and decompile using apk-tool)
     */
    private suspend fun startDecompileFromScratch() {
        try {
            when (apkSource) {

                // User wants to decompile using adb
                is ApkSource.Adb -> {
                    decompileViaAdb()
                }

                // User wants to pull APK from PlayStore
                is ApkSource.PlayStore -> {
                    decompileViaPlayStore(config.shouldConsiderResultCache)
                }

            }
        } catch (e: Exception) {
            e.printStackTrace()
            _fatalError.value = e.message
        }
    }

    /**
     * Downloads APK from playstore and decompile it
     */
    private suspend fun decompileViaPlayStore(
        shouldStoreResult: Boolean
    ) {
        _loadingMessage.value = "Initialising download..."

        val packageName = androidAppWrapper.appPackage.name
        val apkFile = kotlin.io.path.createTempFile(packageName, ".apk").toFile()

        // Download APK from playstore
        playStoreRepo.downloadApk(
            apkFile,
            (apkSource as ApkSource.PlayStore<Account>).value,
            packageName
        ).distinctUntilChanged().collect { downloadPercentage ->
            _loadingMessage.value = "Downloading APK... $downloadPercentage%"

            if (downloadPercentage == 100) {
                // Give some time to APK to prepare for decompile
                _loadingMessage.value = "Preparing APK for decompiling..."
                delay(2000)
                onApkPulled(androidAppWrapper, apkFile, shouldStoreResult = shouldStoreResult)
            }
        }
    }

    /**
     * Downloads APK from device using and decompile it
     */
    private suspend fun decompileViaAdb() {
        val androidDeviceWrapper = (apkSource as ApkSource.Adb).value
        _loadingMessage.value = R.string.app_detail_loading_fetching_apk

        // First get APK path
        val apkRemotePath = adbRepo.getApkPath(androidDeviceWrapper.androidDevice, androidAppWrapper.androidApp)
        if (apkRemotePath != null) {

            val apkFile = kotlin.io.path.createTempFile(
                suffix = ".apk"
            ).toFile()

            adbRepo.pullFile(
                androidDeviceWrapper.androidDevice,
                apkRemotePath,
                apkFile
            ).distinctUntilChanged()
                .catch {
                    _fatalError.value = it.message ?: "Something went wrong while pulling APK"
                }
                .collect { downloadPercentage ->
                    _loadingMessage.value = "Pulling APK $downloadPercentage% ..."

                    if (downloadPercentage == 100) {
                        // Give some time to APK to prepare for decompile
                        _loadingMessage.value = "Preparing APK for decompiling..."
                        delay(2000)
                        onApkPulled(androidAppWrapper, apkFile, shouldStoreResult = true)
                    }

                }
        } else {
            _fatalError.value = R.string.app_detail_error_apk_remote_path
        }
    }


    private suspend fun onApkPulled(
        androidApp: AndroidAppWrapper,
        apkFile: File,
        shouldStoreResult: Boolean
    ) {
        // Now let's decompile
        _loadingMessage.value = R.string.app_detail_loading_decompiling
        this.decompiledDir = apkToolRepo.decompile(
            destinationFile = apkFile,
            targetDir = File(getDecompiledDirPath(androidApp.appPackage.name)),
            onDecompileMessage = {
                _loadingMessage.value = "Decompiling... (${it.replace("I: ", "")})"
            }
        )
        // val decompiledDir = File("build/topcorn_decompiled")

        // Analyse
        _loadingMessage.value = R.string.app_detail_loading_analysing
        val allLibraries = librariesRepo.getCachedLibraries()
        require(allLibraries != null) { "Cached libraries are null" }

        // Report
        val report = apkAnalyzerRepo.analyze(
            androidApp.appPackage.name,
            apkFile,
            decompiledDir!!,
            allLibraries
        )

        _loadingMessage.value = "Hold on please..."

        // Move APK to decompiled dir, so that when user opens the source code dir, he can also see the APK.
        moveApkToDecompiledDir(report, decompiledDir!!, apkFile)

        if (shouldStoreResult) {
            // Converting AnalysisReport to Result
            val result = report.toResult(resultRepo, config)

            // Add result to remove
            resultRepo.add(result).collect {
                when (it) {
                    is Resource.Loading -> {
                        _loadingMessage.value = "Saving result..."
                    }
                    is Resource.Success -> {
                        // Result synced, now lets show the data
                        onReportReady(report)
                    }

                    is Resource.Error -> {
                        _fatalError.value = it.errorData
                    }
                }
            }
        } else {
            onReportReady(report)
        }

    }

    private fun moveApkToDecompiledDir(
        report: AnalysisReport,
        decompiledDir: File,
        apkFile: File
    ) {
        val newApkName = "${report.packageName}_${report.gradleInfo.versionName}.apk"
        val newApkFile = File("${decompiledDir.absolutePath}${File.separator}$newApkName")
        apkFile.renameTo(newApkFile)
    }

    private suspend fun onReportReady(report: AnalysisReport) {
        trackUntrackedLibs(report)
        _analysisReport.value = AnalysisReportWrapper(
            report,
            report.libraries.map { LibraryWrapper(it) }
        )
        _loadingMessage.value = null
    }

    /**
     * TODO: ONLY FOR DEBUG PURPOSE
     */
    private suspend fun trackUntrackedLibs(report: AnalysisReport) {

        if (true) {
            return
        }

        if (report.untrackedLibraries.isNotEmpty()) {
            // Sync untracked libs
            untrackedLibsRepo.getUntrackedLibs()
                .collect { remoteUntrackedLibsResp ->
                    when (remoteUntrackedLibsResp) {
                        is Resource.Loading -> {
                            _loadingMessage.value = "Loading untracked libs..."
                        }
                        is Resource.Success -> {

                            // remove already listed libs and app packages
                            val newUntrackedLibs =
                                report.untrackedLibraries.filter { localUntrackedLib ->
                                    remoteUntrackedLibsResp.data.find { it.packageNames == localUntrackedLib } == null &&
                                            localUntrackedLib.startsWith(report.packageName).not()
                                }.map { UntrackedLibrary(it) }


                            val totalLibsToSync = newUntrackedLibs.size
                            var syncedLibs = 0
                            for (ut in newUntrackedLibs) {
                                untrackedLibsRepo.add(ut)
                                    .collect {
                                        when (it) {
                                            is Resource.Loading -> {
                                                val percentage = (syncedLibs.toFloat() / totalLibsToSync) * 100
                                                _loadingMessage.value =
                                                    "Adding ${ut.packageNames} to untracked libs... ${percentage.roundToInt()}%"
                                            }
                                            is Resource.Success -> {
                                                Arbor.d("Done!! -> ${ut.packageNames}")
                                                syncedLibs++
                                            }

                                            is Resource.Error -> {
                                                Arbor.d("Failed to sync: ${it.errorData}")
                                            }
                                        }
                                    }
                            }
                        }
                        is Resource.Error -> {

                        }
                    }
                }
        }
    }

    fun onTabClicked(index: Int) {
        _selectedTabIndex.value = index
    }

    fun onPlayStoreIconClicked() {
        _analysisReport.value?.let { report ->
            val playStoreUrl = URI("https://play.google.com/store/apps/details?id=${report.packageName}")
            Desktop.getDesktop().browse(playStoreUrl)
        }
    }

    fun onCodeIconClicked() {
        if (decompiledDir?.exists() == true) {
            // Decompiled exists
            Desktop.getDesktop().open(decompiledDir)
        } else {
            // Let's construct possible decompiledDir and check it exist
            val possibleDecompiledDir = File(getDecompiledDirPath(androidAppWrapper.appPackage.name))

            if (possibleDecompiledDir.exists()) {
                // Gotcha! There's one dir available for this package. lets show it
                decompiledDir = possibleDecompiledDir
                onCodeIconClicked() // go again
            } else {
                // We're currently showing cached result, so there won't be any decompiled dir. so let's go decompile
                viewModelScope.launch {
                    decompileViaPlayStore(
                        shouldStoreResult = false // Because, we already have the result in `results` table. We are decompiling to show the source only.
                    )
                    onCodeIconClicked()
                }
            }
        }
    }

    private fun getDecompiledDirPath(
        packageName: String
    ): String {
        val tempDir = System.getProperty("java.io.tmpdir")
        return "$tempDir${File.separator}stackzy${File.separator}$packageName"
    }
}