/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.architecture.blueprints.todoapp.data.source

import com.example.android.architecture.blueprints.todoapp.data.Result
import com.example.android.architecture.blueprints.todoapp.data.Result.Error
import com.example.android.architecture.blueprints.todoapp.data.Result.Success
import com.example.android.architecture.blueprints.todoapp.data.Task
import com.example.android.architecture.blueprints.todoapp.util.EspressoIdlingResource
import java.util.ArrayList
import java.util.LinkedHashMap

/**
 * Concrete implementation to load tasks from the data sources into a cache.
 *
 *
 * For simplicity, this implements a dumb synchronisation between locally persisted data and data
 * obtained from the server, by using the remote data source only if the local database doesn't
 * exist or is empty.
 */
class TasksRepository(
        val tasksRemoteDataSource: TasksDataSource,
        val tasksLocalDataSource: TasksDataSource
) : TasksDataSource {


    /**
     * This variable has public visibility so it can be accessed from tests.
     */
    var cachedTasks: LinkedHashMap<String, Task> = LinkedHashMap()

    /**
     * Marks the cache as invalid, to force an update the next time data is requested. This variable
     * has package local visibility so it can be accessed from tests.
     */
    var cacheIsDirty = false

    override suspend fun getTasks(): Result<List<Task>> {

        // Respond immediately with cache if available and not dirty
        if (cachedTasks.isNotEmpty() && !cacheIsDirty) {
            return Success(ArrayList(cachedTasks.values))
        }

        EspressoIdlingResource.increment() // Set app as busy.

        return if (cacheIsDirty) {
            // If the cache is dirty we need to fetch new data from the network.
            getTasksFromRemoteDataSource()
        } else {
            val localTasks = tasksLocalDataSource.getTasks()
            if (localTasks is Success) {
                refreshCache(localTasks.data)
                Success(cachedTasks.values.toList())
            } else {
                getTasksFromRemoteDataSource()
            }
        }
    }

    override fun saveTask(task: Task) {
        // Do in memory cache update to keep the app UI up to date
        cacheAndPerform(task) {
            tasksRemoteDataSource.saveTask(it)
            tasksLocalDataSource.saveTask(it)
        }
    }

    override fun completeTask(task: Task) {
        // Do in memory cache update to keep the app UI up to date
        cacheAndPerform(task) {
            it.isCompleted = true
            tasksRemoteDataSource.completeTask(it)
            tasksLocalDataSource.completeTask(it)
        }
    }

    override fun completeTask(taskId: String) {
        getTaskWithId(taskId)?.let {
            completeTask(it)
        }
    }

    override fun activateTask(task: Task) {
        // Do in memory cache update to keep the app UI up to date
        cacheAndPerform(task) {
            it.isCompleted = false
            tasksRemoteDataSource.activateTask(it)
            tasksLocalDataSource.activateTask(it)
        }
    }

    override fun activateTask(taskId: String) {
        getTaskWithId(taskId)?.let {
            activateTask(it)
        }
    }

    override fun clearCompletedTasks() {
        tasksRemoteDataSource.clearCompletedTasks()
        tasksLocalDataSource.clearCompletedTasks()

        cachedTasks = cachedTasks.filterValues {
            !it.isCompleted
        } as LinkedHashMap<String, Task>
    }

    /**
     * Gets tasks from local data source (sqlite) unless the table is new or empty. In that case it
     * uses the network data source. This is done to simplify the sample.
     *
     *
     * Note: [GetTaskCallback.onDataNotAvailable] is fired if both data sources fail to
     * get the data.
     */

    override suspend fun getTask(taskId: String): Result<Task> {
        val taskInCache = getTaskWithId(taskId)

        // Respond immediately with cache if available
        if (taskInCache != null) {
            return Success(taskInCache)
        }

        EspressoIdlingResource.increment() // Set app as busy.

        // Try local data source first, then remote.
        val localTask = tasksLocalDataSource.getTask(taskId)
        if (localTask is Success) {
            val task = localTask.data
            // Do in memory cache update to keep the app UI up to date
            cacheAndPerform(task) {
                EspressoIdlingResource.decrement() // Set app as idle.
                return localTask
            }
        } else {
            val remoteTask = tasksRemoteDataSource.getTask(taskId)
            if (remoteTask is Success) {
                cacheAndPerform(remoteTask.data) {
                    EspressoIdlingResource.decrement() // Set app as idle.
                    return remoteTask
                }
            } else {
                EspressoIdlingResource.decrement() // Set app as idle.
                return Error(Exception("Error loading task"))
            }
        }
        return Error(IllegalStateException())
    }

    override fun refreshTasks() {
        cacheIsDirty = true
    }

    override fun deleteAllTasks() {
        tasksRemoteDataSource.deleteAllTasks()
        tasksLocalDataSource.deleteAllTasks()
        cachedTasks.clear()
    }

    override fun deleteTask(taskId: String) {
        tasksRemoteDataSource.deleteTask(taskId)
        tasksLocalDataSource.deleteTask(taskId)
        cachedTasks.remove(taskId)
    }

    private suspend fun getTasksFromRemoteDataSource(): Result<List<Task>> {
        val remoteTasks = tasksRemoteDataSource.getTasks()

        if (remoteTasks is Success) {
            refreshCache(remoteTasks.data)
            refreshLocalDataSource(remoteTasks.data)
        }

        EspressoIdlingResource.decrement() // Set app as idle.
        return remoteTasks
    }

    private fun refreshCache(tasks: List<Task>) {
        cachedTasks.clear()
        tasks.forEach {
            cacheAndPerform(it) {}
        }
        cacheIsDirty = false
    }

    private fun refreshLocalDataSource(tasks: List<Task>) {
        tasksLocalDataSource.deleteAllTasks()
        for (task in tasks) {
            tasksLocalDataSource.saveTask(task)
        }
    }

    private fun getTaskWithId(id: String) = cachedTasks[id]

    private inline fun cacheAndPerform(task: Task, perform: (Task) -> Unit) {
        val cachedTask = Task(task.title, task.description, task.id).apply {
            isCompleted = task.isCompleted
        }
        cachedTasks.put(cachedTask.id, cachedTask)
        perform(cachedTask)
    }

    companion object {

        private var INSTANCE: TasksRepository? = null

        /**
         * Returns the single instance of this class, creating it if necessary.

         * @param tasksRemoteDataSource the backend data source
         * *
         * @param tasksLocalDataSource  the device storage data source
         * *
         * @return the [TasksRepository] instance
         */
        @JvmStatic fun getInstance(tasksRemoteDataSource: TasksDataSource,
                tasksLocalDataSource: TasksDataSource) =
                INSTANCE ?: synchronized(TasksRepository::class.java) {
                    INSTANCE ?: TasksRepository(tasksRemoteDataSource, tasksLocalDataSource)
                            .also { INSTANCE = it }
                }


        /**
         * Used to force [getInstance] to create a new instance
         * next time it's called.
         */
        @JvmStatic fun destroyInstance() {
            INSTANCE = null
        }
    }
}
