package com.taskly.android.di

import android.content.Context
import com.taskly.android.core.database.TasklyDatabase
import com.taskly.android.core.network.TasklyApiClient
import com.taskly.android.core.sync.SyncEngine
import com.taskly.android.core.sync.SyncSession
import com.taskly.android.core.sync.TaskRepository
import com.taskly.android.feature.tasks.AuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Application-wide singletons. The core modules stay DI-agnostic; wiring lives here. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSession(@ApplicationContext context: Context): SyncSession = SyncSession(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TasklyDatabase = TasklyDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideApiClient(session: SyncSession): TasklyApiClient = TasklyApiClient.create(session.baseUrl)

    @Provides
    @Singleton
    fun provideSyncEngine(db: TasklyDatabase, api: TasklyApiClient): SyncEngine = SyncEngine(db, api)

    @Provides
    @Singleton
    fun provideTaskRepository(db: TasklyDatabase, sync: SyncEngine): TaskRepository = TaskRepository(db, sync)

    @Provides
    @Singleton
    fun provideAuthRepository(api: TasklyApiClient, session: SyncSession): AuthRepository = AuthRepository(api, session)
}
