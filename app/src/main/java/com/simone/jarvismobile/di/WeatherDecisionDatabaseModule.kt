package com.simone.jarvismobile.di

import android.content.Context
import androidx.room.Room
import com.simone.jarvismobile.weather.receipt.ForecastDecisionReceiptDao
import com.simone.jarvismobile.weather.receipt.WeatherDecisionDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * § JARVIS Implementation Master Plan — PROACTIVITY RELIABILITY CLOSURE WORK
 * PACKAGE D §22/§29. A SEPARATE Hilt module for [WeatherDecisionDatabase] —
 * see that class's own doc comment for why it is a second Room instance
 * rather than a table on [com.simone.jarvismobile.background.JarvisDatabase].
 * `fallbackToDestructiveMigration()` is safe here (unlike on the main
 * database): every row is a disposable, re-derivable evaluation receipt —
 * never data only the user can produce — so a future schema bump losing
 * old receipts is an acceptable, disclosed trade-off, not a data-loss risk.
 */
@Module
@InstallIn(SingletonComponent::class)
object WeatherDecisionDatabaseModule {
    @Provides
    @Singleton
    fun provideWeatherDecisionDatabase(@ApplicationContext context: Context): WeatherDecisionDatabase =
        Room.databaseBuilder(context, WeatherDecisionDatabase::class.java, "weather_decisions.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideForecastDecisionReceiptDao(database: WeatherDecisionDatabase): ForecastDecisionReceiptDao =
        database.forecastDecisionReceiptDao()
}
