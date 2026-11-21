package com.docunest.dock

/**
 * Which windowing mode each app's task is in, read from Android 10's
 * `dumpsys activity activities` (stack headers "Stack #12: type=standard
 * mode=split-screen-primary", then that stack's "TaskRecord{… A=com.waze …}").
 * So the dock can *check* that Google Maps really landed beside Waze, not over
 * it. Pure, unit-tested.
 */
object SplitState {
    /** package (task affinity) → mode, e.g. "com.waze" → "split-screen-primary". First (topmost) wins. */
    fun parse(dump: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var mode: String? = null
        val stack = Regex("Stack #\\d+:.*?mode=([\\w-]+)")
        val task = Regex("TaskRecord\\{[^}]*? A=(?:\\d+:)?([\\w.]+)")
        for (line in dump.lines()) {
            stack.find(line)?.let { mode = it.groupValues[1] }
            task.find(line)?.let { m -> mode?.let { out.putIfAbsent(m.groupValues[1], it) } }
        }
        return out
    }

    /** Maps really is beside Waze: one in each split half. */
    fun mapsBesideWaze(modes: Map<String, String>, waze: String = Apps.WAZE, maps: String = Apps.GMAPS): Boolean {
        val w = modes.entries.firstOrNull { it.key.startsWith(waze) }?.value
        val m = modes.entries.firstOrNull { it.key.startsWith(maps) }?.value
        return w != null && m != null && w.startsWith("split-screen") && m.startsWith("split-screen") && w != m
    }

    fun describe(modes: Map<String, String>): String =
        modes.filterKeys { it.startsWith(Apps.WAZE) || it.startsWith(Apps.GMAPS) }.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }.ifEmpty { "neither Waze nor Maps running" }
}
