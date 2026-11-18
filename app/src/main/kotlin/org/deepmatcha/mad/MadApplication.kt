package org.deepmatcha.mad

import android.app.Application
import org.deepmatcha.mad.contract.MadDependencies
import org.deepmatcha.mad.demo.DemoMadDependencies

class MadApplication : Application() {
    val dependencies: MadDependencies by lazy { DemoMadDependencies.create() }
}
