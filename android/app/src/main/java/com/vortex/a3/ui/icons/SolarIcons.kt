package com.vortex.a3.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

// NOTE: Vendored locally to avoid an extra Compose dependency and keep release shrink lean.

class IconLoadFailure(val iconName: String, causeDetail: Throwable? = null) :
    IllegalArgumentException("Solar icon failed to resolve: $iconName", causeDetail)

val SOLAR_ICON_ALLOWLIST: Set<String> = setOf(
    "ArrowBack", "Language", "LightMode", "DarkMode", "Headset",
    "Notifications", "NotificationsActive", "ContentPaste", "FileDownload", "TouchApp",
    "Add", "StickyNote2", "Close", "Check", "Delete",
    "Settings", "Laptop", "Smartphone", "Headphones",
    "BatteryChargingFull", "BatteryStd", "BatteryFull", "Battery2Bar", "BatteryAlert",
    "Cast", "Lock", "LockOpen",
)

fun assertSolarIconName(name: String) {
    if (!SOLAR_ICON_ALLOWLIST.contains(name)) {
        throw IconLoadFailure(name)
    }
}

private fun solarVector(name: String, nodes: List<PathNode>): ImageVector {
    assertSolarIconName(name)
    return try {
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = nodes,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    } catch (e: Exception) {
        if (e is IconLoadFailure) throw e
        throw IconLoadFailure(name, e)
    }
}

internal object SolarIcons {
    val ArrowBack: ImageVector by lazy {
        solarVector(
            "ArrowBack",
            listOf(
                PathNode.MoveTo(19f, 12f),
                PathNode.LineTo(5f, 12f),
                PathNode.MoveTo(11f, 6f),
                PathNode.LineTo(5f, 12f),
                PathNode.LineTo(11f, 18f),
            ),
        )
    }
    val Language: ImageVector by lazy {
        solarVector(
            "Language",
            listOf(
                PathNode.MoveTo(3.5f, 12f),
                PathNode.LineTo(20.5f, 12f),
                PathNode.MoveTo(12f, 3.5f),
                PathNode.LineTo(12f, 20.5f),
                PathNode.MoveTo(5f, 5f),
                PathNode.LineTo(19f, 5f),
                PathNode.LineTo(19f, 19f),
                PathNode.LineTo(5f, 19f),
                PathNode.Close,
            ),
        )
    }
    val LightMode: ImageVector by lazy {
        solarVector(
            "LightMode",
            listOf(
                PathNode.MoveTo(12f, 8f),
                PathNode.LineTo(16f, 12f),
                PathNode.LineTo(12f, 16f),
                PathNode.LineTo(8f, 12f),
                PathNode.Close,
                PathNode.MoveTo(12f, 2.5f),
                PathNode.LineTo(12f, 5f),
                PathNode.MoveTo(12f, 19f),
                PathNode.LineTo(12f, 21.5f),
                PathNode.MoveTo(2.5f, 12f),
                PathNode.LineTo(5f, 12f),
                PathNode.MoveTo(19f, 12f),
                PathNode.LineTo(21.5f, 12f),
            ),
        )
    }
    val DarkMode: ImageVector by lazy {
        solarVector(
            "DarkMode",
            listOf(
                PathNode.MoveTo(20f, 14.5f),
                PathNode.LineTo(15f, 19f),
                PathNode.LineTo(10f, 19f),
                PathNode.LineTo(6f, 15f),
                PathNode.LineTo(6f, 10f),
                PathNode.LineTo(10f, 5.5f),
                PathNode.LineTo(15f, 5.5f),
                PathNode.LineTo(19f, 9f),
                PathNode.Close,
            ),
        )
    }
    val Headset: ImageVector by lazy {
        solarVector(
            "Headset",
            listOf(
                PathNode.MoveTo(4f, 15f),
                PathNode.LineTo(4f, 11f),
                PathNode.LineTo(8f, 5f),
                PathNode.LineTo(16f, 5f),
                PathNode.LineTo(20f, 11f),
                PathNode.LineTo(20f, 15f),
                PathNode.MoveTo(4f, 14f),
                PathNode.LineTo(7f, 14f),
                PathNode.LineTo(7f, 19f),
                PathNode.LineTo(4f, 19f),
                PathNode.Close,
                PathNode.MoveTo(20f, 14f),
                PathNode.LineTo(17f, 14f),
                PathNode.LineTo(17f, 19f),
                PathNode.LineTo(20f, 19f),
                PathNode.Close,
            ),
        )
    }
    val Notifications: ImageVector by lazy {
        solarVector(
            "Notifications",
            listOf(
                PathNode.MoveTo(6f, 10f),
                PathNode.LineTo(6f, 15.5f),
                PathNode.LineTo(4.5f, 15.5f),
                PathNode.LineTo(19.5f, 15.5f),
                PathNode.LineTo(18f, 10f),
                PathNode.LineTo(15f, 5f),
                PathNode.LineTo(9f, 5f),
                PathNode.Close,
                PathNode.MoveTo(10f, 19f),
                PathNode.LineTo(14f, 19f),
            ),
        )
    }
    val NotificationsActive: ImageVector by lazy {
        solarVector(
            "NotificationsActive",
            listOf(
                PathNode.MoveTo(6f, 10f),
                PathNode.LineTo(6f, 15.5f),
                PathNode.LineTo(4.5f, 15.5f),
                PathNode.LineTo(19.5f, 15.5f),
                PathNode.LineTo(18f, 10f),
                PathNode.LineTo(15f, 5f),
                PathNode.LineTo(9f, 5f),
                PathNode.Close,
                PathNode.MoveTo(10f, 19f),
                PathNode.LineTo(14f, 19f),
                PathNode.MoveTo(19f, 3f),
                PathNode.LineTo(19f, 6f),
                PathNode.MoveTo(17.5f, 4.5f),
                PathNode.LineTo(20.5f, 4.5f),
            ),
        )
    }
    val ContentPaste: ImageVector by lazy {
        solarVector(
            "ContentPaste",
            listOf(
                PathNode.MoveTo(6f, 5f),
                PathNode.LineTo(18f, 5f),
                PathNode.LineTo(18f, 20f),
                PathNode.LineTo(6f, 20f),
                PathNode.Close,
                PathNode.MoveTo(9f, 3f),
                PathNode.LineTo(15f, 3f),
                PathNode.LineTo(15f, 7f),
                PathNode.LineTo(9f, 7f),
                PathNode.Close,
                PathNode.MoveTo(9.5f, 11f),
                PathNode.LineTo(14.5f, 11f),
                PathNode.MoveTo(9.5f, 14.5f),
                PathNode.LineTo(14.5f, 14.5f),
            ),
        )
    }
    val FileDownload: ImageVector by lazy {
        solarVector(
            "FileDownload",
            listOf(
                PathNode.MoveTo(6f, 3.5f),
                PathNode.LineTo(14f, 3.5f),
                PathNode.LineTo(19f, 8.5f),
                PathNode.LineTo(19f, 20f),
                PathNode.LineTo(5f, 20f),
                PathNode.LineTo(5f, 3.5f),
                PathNode.Close,
                PathNode.MoveTo(12f, 11f),
                PathNode.LineTo(12f, 17f),
                PathNode.MoveTo(9.5f, 14.5f),
                PathNode.LineTo(12f, 17f),
                PathNode.LineTo(14.5f, 14.5f),
            ),
        )
    }
    val TouchApp: ImageVector by lazy {
        solarVector(
            "TouchApp",
            listOf(
                PathNode.MoveTo(9f, 11f),
                PathNode.LineTo(9f, 5f),
                PathNode.LineTo(11f, 3.5f),
                PathNode.LineTo(13f, 5f),
                PathNode.LineTo(13f, 10f),
                PathNode.MoveTo(13f, 8f),
                PathNode.LineTo(15f, 6.5f),
                PathNode.LineTo(17f, 8f),
                PathNode.LineTo(17f, 14f),
                PathNode.LineTo(15f, 19f),
                PathNode.LineTo(10f, 19f),
                PathNode.LineTo(7f, 14f),
                PathNode.Close,
            ),
        )
    }
    val Add: ImageVector by lazy {
        solarVector(
            "Add",
            listOf(
                PathNode.MoveTo(12f, 5f),
                PathNode.LineTo(12f, 19f),
                PathNode.MoveTo(5f, 12f),
                PathNode.LineTo(19f, 12f),
            ),
        )
    }
    val StickyNote2: ImageVector by lazy {
        solarVector(
            "StickyNote2",
            listOf(
                PathNode.MoveTo(6f, 3.5f),
                PathNode.LineTo(17f, 3.5f),
                PathNode.LineTo(17f, 17f),
                PathNode.LineTo(6f, 17f),
                PathNode.Close,
                PathNode.MoveTo(9f, 8f),
                PathNode.LineTo(14f, 8f),
                PathNode.MoveTo(9f, 11.5f),
                PathNode.LineTo(14f, 11.5f),
            ),
        )
    }
    val Close: ImageVector by lazy {
        solarVector(
            "Close",
            listOf(
                PathNode.MoveTo(6f, 6f),
                PathNode.LineTo(18f, 18f),
                PathNode.MoveTo(18f, 6f),
                PathNode.LineTo(6f, 18f),
            ),
        )
    }
    val Check: ImageVector by lazy {
        solarVector(
            "Check",
            listOf(
                PathNode.MoveTo(5f, 12.5f),
                PathNode.LineTo(9.5f, 17f),
                PathNode.LineTo(19f, 7.5f),
            ),
        )
    }
    val Delete: ImageVector by lazy {
        solarVector(
            "Delete",
            listOf(
                PathNode.MoveTo(4f, 7f),
                PathNode.LineTo(20f, 7f),
                PathNode.MoveTo(9.5f, 7f),
                PathNode.LineTo(9.5f, 4.5f),
                PathNode.LineTo(14.5f, 4.5f),
                PathNode.LineTo(14.5f, 7f),
                PathNode.MoveTo(6.5f, 7f),
                PathNode.LineTo(7.5f, 20f),
                PathNode.LineTo(16.5f, 20f),
                PathNode.LineTo(17.5f, 7f),
            ),
        )
    }
    val Settings: ImageVector by lazy {
        solarVector(
            "Settings",
            listOf(
                PathNode.MoveTo(12f, 9f),
                PathNode.LineTo(15f, 12f),
                PathNode.LineTo(12f, 15f),
                PathNode.LineTo(9f, 12f),
                PathNode.Close,
                PathNode.MoveTo(12f, 2.8f),
                PathNode.LineTo(12f, 5.4f),
                PathNode.MoveTo(12f, 18.6f),
                PathNode.LineTo(12f, 21.2f),
                PathNode.MoveTo(2.8f, 12f),
                PathNode.LineTo(5.4f, 12f),
                PathNode.MoveTo(18.6f, 12f),
                PathNode.LineTo(21.2f, 12f),
            ),
        )
    }
    val Laptop: ImageVector by lazy {
        solarVector(
            "Laptop",
            listOf(
                PathNode.MoveTo(4f, 4.5f),
                PathNode.LineTo(20f, 4.5f),
                PathNode.LineTo(20f, 15.5f),
                PathNode.LineTo(4f, 15.5f),
                PathNode.Close,
                PathNode.MoveTo(2.5f, 19.5f),
                PathNode.LineTo(21.5f, 19.5f),
            ),
        )
    }
    val Smartphone: ImageVector by lazy {
        solarVector(
            "Smartphone",
            listOf(
                PathNode.MoveTo(7f, 2.5f),
                PathNode.LineTo(17f, 2.5f),
                PathNode.LineTo(17f, 21.5f),
                PathNode.LineTo(7f, 21.5f),
                PathNode.Close,
                PathNode.MoveTo(10.5f, 18.5f),
                PathNode.LineTo(13.5f, 18.5f),
            ),
        )
    }
    val Headphones: ImageVector by lazy {
        solarVector(
            "Headphones",
            listOf(
                PathNode.MoveTo(4f, 15f),
                PathNode.LineTo(4f, 11f),
                PathNode.LineTo(8f, 6f),
                PathNode.LineTo(16f, 6f),
                PathNode.LineTo(20f, 11f),
                PathNode.LineTo(20f, 15f),
                PathNode.MoveTo(3f, 14f),
                PathNode.LineTo(7f, 14f),
                PathNode.LineTo(7f, 20f),
                PathNode.LineTo(3f, 20f),
                PathNode.Close,
                PathNode.MoveTo(21f, 14f),
                PathNode.LineTo(17f, 14f),
                PathNode.LineTo(17f, 20f),
                PathNode.LineTo(21f, 20f),
                PathNode.Close,
            ),
        )
    }
    val BatteryChargingFull: ImageVector by lazy {
        solarVector(
            "BatteryChargingFull",
            listOf(
                PathNode.MoveTo(2.5f, 8f),
                PathNode.LineTo(19.5f, 8f),
                PathNode.LineTo(19.5f, 16f),
                PathNode.LineTo(2.5f, 16f),
                PathNode.Close,
                PathNode.MoveTo(21.5f, 11f),
                PathNode.LineTo(21.5f, 13f),
                PathNode.MoveTo(11f, 9.5f),
                PathNode.LineTo(9.5f, 12.5f),
                PathNode.LineTo(12f, 12.5f),
                PathNode.LineTo(11f, 14.5f),
            ),
        )
    }
    val BatteryStd: ImageVector by lazy {
        solarVector(
            "BatteryStd",
            listOf(
                PathNode.MoveTo(2.5f, 8f),
                PathNode.LineTo(19.5f, 8f),
                PathNode.LineTo(19.5f, 16f),
                PathNode.LineTo(2.5f, 16f),
                PathNode.Close,
                PathNode.MoveTo(21.5f, 11f),
                PathNode.LineTo(21.5f, 13f),
                PathNode.MoveTo(6f, 11f),
                PathNode.LineTo(6f, 13f),
                PathNode.MoveTo(9.5f, 11f),
                PathNode.LineTo(9.5f, 13f),
            ),
        )
    }
    val BatteryFull: ImageVector by lazy {
        solarVector(
            "BatteryFull",
            listOf(
                PathNode.MoveTo(2.5f, 8f),
                PathNode.LineTo(19.5f, 8f),
                PathNode.LineTo(19.5f, 16f),
                PathNode.LineTo(2.5f, 16f),
                PathNode.Close,
                PathNode.MoveTo(21.5f, 11f),
                PathNode.LineTo(21.5f, 13f),
                PathNode.MoveTo(6f, 11f),
                PathNode.LineTo(6f, 13f),
                PathNode.MoveTo(9.5f, 11f),
                PathNode.LineTo(9.5f, 13f),
                PathNode.MoveTo(13f, 11f),
                PathNode.LineTo(13f, 13f),
                PathNode.MoveTo(16f, 11f),
                PathNode.LineTo(16f, 13f),
            ),
        )
    }
    val Battery2Bar: ImageVector by lazy {
        solarVector(
            "Battery2Bar",
            listOf(
                PathNode.MoveTo(2.5f, 8f),
                PathNode.LineTo(19.5f, 8f),
                PathNode.LineTo(19.5f, 16f),
                PathNode.LineTo(2.5f, 16f),
                PathNode.Close,
                PathNode.MoveTo(21.5f, 11f),
                PathNode.LineTo(21.5f, 13f),
                PathNode.MoveTo(6f, 11f),
                PathNode.LineTo(6f, 13f),
            ),
        )
    }
    val BatteryAlert: ImageVector by lazy {
        solarVector(
            "BatteryAlert",
            listOf(
                PathNode.MoveTo(2.5f, 8f),
                PathNode.LineTo(19.5f, 8f),
                PathNode.LineTo(19.5f, 16f),
                PathNode.LineTo(2.5f, 16f),
                PathNode.Close,
                PathNode.MoveTo(21.5f, 11f),
                PathNode.LineTo(21.5f, 13f),
                PathNode.MoveTo(12f, 10f),
                PathNode.LineTo(12f, 12.5f),
                PathNode.MoveTo(12f, 14.5f),
                PathNode.LineTo(12f, 14.6f),
            ),
        )
    }
    val Cast: ImageVector by lazy {
        solarVector(
            "Cast",
            listOf(
                PathNode.MoveTo(3f, 4.5f),
                PathNode.LineTo(21f, 4.5f),
                PathNode.LineTo(21f, 15f),
                PathNode.LineTo(3f, 15f),
                PathNode.Close,
                PathNode.MoveTo(3f, 19f),
                PathNode.LineTo(7f, 19f),
                PathNode.MoveTo(5f, 8f),
                PathNode.LineTo(9f, 8f),
                PathNode.LineTo(9f, 12f),
            ),
        )
    }
    val Lock: ImageVector by lazy {
        solarVector(
            "Lock",
            listOf(
                PathNode.MoveTo(5f, 10.5f),
                PathNode.LineTo(19f, 10.5f),
                PathNode.LineTo(19f, 20f),
                PathNode.LineTo(5f, 20f),
                PathNode.Close,
                PathNode.MoveTo(8f, 10.5f),
                PathNode.LineTo(8f, 8f),
                PathNode.LineTo(10f, 5.5f),
                PathNode.LineTo(14f, 5.5f),
                PathNode.LineTo(16f, 8f),
                PathNode.LineTo(16f, 10.5f),
            ),
        )
    }
    val LockOpen: ImageVector by lazy {
        solarVector(
            "LockOpen",
            listOf(
                PathNode.MoveTo(5f, 10.5f),
                PathNode.LineTo(19f, 10.5f),
                PathNode.LineTo(19f, 20f),
                PathNode.LineTo(5f, 20f),
                PathNode.Close,
                PathNode.MoveTo(8f, 10.5f),
                PathNode.LineTo(8f, 8f),
                PathNode.LineTo(10f, 5.5f),
                PathNode.LineTo(14f, 5.5f),
                PathNode.LineTo(15.5f, 7f),
            ),
        )
    }

    fun batteryIconFor(pct: Int?, charging: Boolean): ImageVector {
        return try {
            when {
                charging -> BatteryChargingFull
                pct == null -> BatteryStd
                pct >= 80 -> BatteryFull
                pct >= 40 -> BatteryStd
                pct >= 15 -> Battery2Bar
                else -> BatteryAlert
            }
        } catch (e: Exception) {
            if (e is IconLoadFailure) throw e
            throw IconLoadFailure("BatteryStd", e)
        }
    }

    fun lockIconFor(locked: Boolean): ImageVector {
        return try {
            if (locked) Lock else LockOpen
        } catch (e: Exception) {
            throw IconLoadFailure(if (locked) "Lock" else "LockOpen", e)
        }
    }

    fun resolve(name: String): ImageVector {
        return try {
            assertSolarIconName(name)
            when (name) {
                "ArrowBack" -> ArrowBack
                "Language" -> Language
                "LightMode" -> LightMode
                "DarkMode" -> DarkMode
                "Headset" -> Headset
                "Notifications" -> Notifications
                "NotificationsActive" -> NotificationsActive
                "ContentPaste" -> ContentPaste
                "FileDownload" -> FileDownload
                "TouchApp" -> TouchApp
                "Add" -> Add
                "StickyNote2" -> StickyNote2
                "Close" -> Close
                "Check" -> Check
                "Delete" -> Delete
                "Settings" -> Settings
                "Laptop" -> Laptop
                "Smartphone" -> Smartphone
                "Headphones" -> Headphones
                "BatteryChargingFull" -> BatteryChargingFull
                "BatteryStd" -> BatteryStd
                "BatteryFull" -> BatteryFull
                "Battery2Bar" -> Battery2Bar
                "BatteryAlert" -> BatteryAlert
                "Cast" -> Cast
                "Lock" -> Lock
                "LockOpen" -> LockOpen
                else -> throw IconLoadFailure(name)
            }
        } catch (e: Exception) {
            if (e is IconLoadFailure) throw e
            throw IconLoadFailure(name, e)
        }
    }
}
