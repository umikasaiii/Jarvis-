package com.simone.jarvismobile.di

import com.simone.jarvismobile.weather.OpenMeteoWeatherSource
import com.simone.jarvismobile.weather.WeatherSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the weather fetch behind its interface, swappable like every other engine. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherModule {

    @Binds
    @Singleton
    abstract fun bindWeatherSource(impl: OpenMeteoWeatherSource): WeatherSource
}
