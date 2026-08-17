package com.simone.jarvismobile.di

import com.simone.jarvismobile.navigation.AStarRouterEngine
import com.simone.jarvismobile.navigation.NavigationEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [NavigationEngine] to its current backend. Swapping to a future native
 * routing engine (see `ValhallaNavigationEngine.kt` — not implemented yet) is a
 * single-line change here; [com.simone.jarvismobile.navigation.NavigationRepository]
 * and everything above it depends only on the interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NavigationEngineModule {

    @Binds
    @Singleton
    abstract fun bindNavigationEngine(impl: AStarRouterEngine): NavigationEngine
}
