package com.theapache64.stackzy.ui.feature.splash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.arkivanov.decompose.ComponentContext
import com.theapache64.stackzy.data.di.AppComponent
import com.theapache64.stackzy.ui.navigation.Component
import com.toxicbakery.logging.Arbor
import javax.inject.Inject

/**
 * Splash Screen Component
 */
class SplashScreenComponent(
    appComponent: AppComponent,
    private val componentContext: ComponentContext,
    private val onSyncFinished: () -> Unit,
    private val onUpdateNeeded: () -> Unit,
) : Component, ComponentContext by componentContext {

    @Inject
    lateinit var splashViewModel: SplashViewModel

    init {
        appComponent.inject(this)
    }

    @Composable
    override fun render() {

        val scope = rememberCoroutineScope()
        LaunchedEffect(splashViewModel) {
            Arbor.d("Syncing data...")
            splashViewModel.init(scope)
            splashViewModel.syncData()
        }


        SplashScreen(
            splashViewModel = splashViewModel,
            onSyncFinished = onSyncFinished,
            onUpdateNeeded = onUpdateNeeded
        )
    }

}