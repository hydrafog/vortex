package com.vortex.a3.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
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
    "Cast", "Lock", "LockOpen", "Power", "Suspend",
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
            addPathNodes(
                "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z " +
                "M13.7654 2.15224C13.3978 2 12.9319 2 12 2C11.0681 2 10.6022 2 10.2346 2.15224C9.74457 2.35523 9.35522 2.74458 9.15223 3.23463C9.05957 3.45834 9.0233 3.7185 9.00911 4.09799C8.98826 4.65568 8.70226 5.17189 8.21894 5.45093C7.73564 5.72996 7.14559 5.71954 6.65219 5.45876C6.31645 5.2813 6.07301 5.18262 5.83294 5.15102C5.30704 5.08178 4.77518 5.22429 4.35436 5.5472C4.03874 5.78938 3.80577 6.1929 3.33983 6.99993C2.87389 7.80697 2.64092 8.21048 2.58899 8.60491C2.51976 9.1308 2.66227 9.66266 2.98518 10.0835C3.13256 10.2756 3.3397 10.437 3.66119 10.639C4.1338 10.936 4.43789 11.4419 4.43786 12C4.43783 12.5581 4.13375 13.0639 3.66118 13.3608C3.33965 13.5629 3.13248 13.7244 2.98508 13.9165C2.66217 14.3373 2.51966 14.8691 2.5889 15.395C2.64082 15.7894 2.87379 16.193 3.33973 17C3.80568 17.807 4.03865 18.2106 4.35426 18.4527C4.77508 18.7756 5.30694 18.9181 5.83284 18.8489C6.07289 18.8173 6.31632 18.7186 6.65204 18.5412C7.14547 18.2804 7.73556 18.27 8.2189 18.549C8.70224 18.8281 8.98826 19.3443 9.00911 19.9021C9.02331 20.2815 9.05957 20.5417 9.15223 20.7654C9.35522 21.2554 9.74457 21.6448 10.2346 21.8478C10.6022 22 11.0681 22 12 22C12.9319 22 13.3978 22 13.7654 21.8478C14.2554 21.6448 14.6448 21.2554 14.8477 20.7654C14.9404 20.5417 14.9767 20.2815 14.9909 19.902C15.0117 19.3443 15.2977 18.8281 15.781 18.549C16.2643 18.2699 16.8544 18.2804 17.3479 18.5412C17.6836 18.7186 17.927 18.8172 18.167 18.8488C18.6929 18.9181 19.2248 18.7756 19.6456 18.4527C19.9612 18.2105 20.1942 17.807 20.6601 16.9999C21.1261 16.1929 21.3591 15.7894 21.411 15.395C21.4802 14.8691 21.3377 14.3372 21.0148 13.9164C20.8674 13.7243 20.6602 13.5628 20.3387 13.3608C19.8662 13.0639 19.5621 12.558 19.5621 11.9999C19.5621 11.4418 19.8662 10.9361 20.3387 10.6392C20.6603 10.4371 20.8675 10.2757 21.0149 10.0835C21.3378 9.66273 21.4803 9.13087 21.4111 8.60497C21.3592 8.21055 21.1262 7.80703 20.6602 7C20.1943 6.19297 19.9613 5.78945 19.6457 5.54727C19.2249 5.22436 18.693 5.08185 18.1671 5.15109C17.9271 5.18269 17.6837 5.28136 17.3479 5.4588C16.8545 5.71959 16.2644 5.73002 15.7811 5.45096C15.2977 5.17191 15.0117 4.65566 14.9909 4.09794C14.9767 3.71848 14.9404 3.45833 14.8477 3.23463C14.6448 2.74458 14.2554 2.35523 13.7654 2.15224Z"
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
    val Power: ImageVector by lazy {
        solarVector(
            "Power",
            addPathNodes("M12 3v7m6.364-3.636a9 9 0 1 1-12.728 0"),
        )
    }
    val Suspend: ImageVector by lazy {
        solarVector(
            "Suspend",
            addPathNodes("M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z"),
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
                "Power" -> Power
                "Suspend" -> Suspend
                else -> throw IconLoadFailure(name)
            }
        } catch (e: Exception) {
            if (e is IconLoadFailure) throw e
            throw IconLoadFailure(name, e)
        }
    }
}
