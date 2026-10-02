package com.nivara.app

import android.app.Application
import com.nivara.app.di.AppContainer
import com.nivara.app.di.DefaultAppContainer

/**
 * Application entry point and owner of the composition root.
 *
 * Nivara wires its dependencies by hand through [AppContainer] instead of using a dependency
 * injection framework. The object graph is currently a handful of classes, and staying
 * framework-free keeps the app small, the startup path short and the dependency of a
 * security-sensitive process easy to audit. The container can be replaced by a generated
 * graph later without touching call sites.
 */
class NivaraApplication : Application() {

    /** Application-wide dependency container. Created on first use, not during `onCreate`. */
    val container: AppContainer by lazy { DefaultAppContainer(this) }
}
