package io.github.trebronwayne.droplink.core

/** "12 B", "3.4 KB", "56 MB", "1.20 GB": short sizes for people. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
