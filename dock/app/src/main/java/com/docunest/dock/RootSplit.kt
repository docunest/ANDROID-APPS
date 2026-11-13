package com.docunest.dock

import android.app.ActivityManager

/**
 * Run as root (`app_process`, through [Root] — the unit's own adb) to put an
 * app's existing task into the split-screen primary half with Android 10's
 * own IActivityTaskManager.setTaskWindowingModeSplitScreenPrimary — what
 * System UI does when you split by hand. Then anything launched "adjacent"
 * lands in the other half.
 *
 *   primary <package>   prints "ok <taskId> <result>" or the problem
 */
object RootSplit {
    @JvmStatic
    fun main(args: Array<String>) {
        println(runCatching { run(args) }.getOrElse { "error ${it.javaClass.simpleName}: ${it.message}" })
    }

    private fun run(args: Array<String>): String {
        if (args.getOrNull(0) != "primary") return "error usage: primary <package>"
        val pkg = args.getOrNull(1) ?: return "error no package"
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
        val getTasks = atm.javaClass.methods.first { it.name == "getTasks" && it.parameterTypes.size == 1 }
        @Suppress("UNCHECKED_CAST")
        val tasks = getTasks.invoke(atm, 50) as List<ActivityManager.RunningTaskInfo>
        val task = tasks.firstOrNull { it.baseActivity?.packageName == pkg || it.topActivity?.packageName == pkg }
            ?: return "missing no running task for $pkg"
        val id = task.taskId
        val m = atm.javaClass.methods.first { it.name == "setTaskWindowingModeSplitScreenPrimary" }
        // (taskId, createMode = TOP_OR_LEFT, toTop, animate, initialBounds, showRecents)
        val r = when (m.parameterTypes.size) {
            6 -> m.invoke(atm, id, 0, true, false, null, false)
            5 -> m.invoke(atm, id, 0, true, false, null)
            else -> return "error unexpected signature (${m.parameterTypes.size} params)"
        }
        return "ok $id $r"
    }
}
