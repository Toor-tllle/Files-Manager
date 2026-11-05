package com.rui.filesmanager

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder

private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    materialIcon(name = name) {
        materialPath(pathBuilder = block)
    }

private fun iconEvenOdd(name: String, block: PathBuilder.() -> Unit): ImageVector =
    materialIcon(name = name) {
        materialPath(pathFillType = PathFillType.EvenOdd, pathBuilder = block)
    }

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcTo(r, r, 0f, false, true, cx - r, cy)
    arcTo(r, r, 0f, false, true, cx + r, cy)
    close()
}

private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y)
    lineTo(x + w, y)
    lineTo(x + w, y + h)
    lineTo(x, y + h)
    close()
}

/** material-icons-extended の代わりに自前で用意したアイコン群（APK肥大化回避） */
object AppIcons {
    val Folder: ImageVector by lazy {
        icon("Folder") {
            moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12.5f, 7.5f); lineTo(21f, 7.5f)
            lineTo(21f, 19f); lineTo(3f, 19f); close()
        }
    }
    val FolderOpen: ImageVector by lazy {
        icon("FolderOpen") {
            moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12.5f, 7.5f); lineTo(21f, 7.5f)
            lineTo(18.8f, 19f); lineTo(5.2f, 19f); close()
        }
    }
    val Movie: ImageVector by lazy {
        iconEvenOdd("Movie") {
            rect(3f, 4f, 18f, 16f); rect(5f, 6f, 14f, 12f)
            moveTo(10f, 9f); lineTo(15.5f, 12f); lineTo(10f, 15f); close()
        }
    }
    val MusicNote: ImageVector by lazy {
        icon("MusicNote") {
            circle(10f, 17f, 3.6f)
            circle(16.5f, 15.5f, 3.3f)
            rect(13.4f, 3f, 2f, 11.5f)
            rect(19.3f, 5.5f, 2f, 8.5f)
            moveTo(13.4f, 3f); lineTo(21.3f, 5.5f); lineTo(21.3f, 9f); lineTo(13.4f, 6.5f); close()
        }
    }
    val Image: ImageVector by lazy {
        iconEvenOdd("Image") {
            rect(3f, 3f, 18f, 18f); rect(5f, 5f, 14f, 14f)
            circle(8.5f, 8.5f, 1.6f)
            moveTo(5f, 19f); lineTo(9.5f, 13f); lineTo(12.5f, 16f); lineTo(15.5f, 12.5f); lineTo(19f, 19f); close()
        }
    }
    val InsertDriveFile: ImageVector by lazy {
        iconEvenOdd("InsertDriveFile") {
            moveTo(6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 22f); lineTo(6f, 22f); close()
            moveTo(13f, 3.5f); lineTo(13f, 9f); lineTo(18.5f, 9f); close()
        }
    }
    val Description: ImageVector by lazy {
        iconEvenOdd("Description") {
            moveTo(6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 22f); lineTo(6f, 22f); close()
            moveTo(13f, 3.5f); lineTo(13f, 9f); lineTo(18.5f, 9f); close()
            rect(8f, 13f, 8f, 1.8f); rect(8f, 16.5f, 8f, 1.8f)
        }
    }
    val Download: ImageVector by lazy {
        icon("Download") {
            moveTo(11f, 3f); lineTo(13f, 3f); lineTo(13f, 10f); lineTo(16f, 10f); lineTo(12f, 14.5f); lineTo(8f, 10f); lineTo(11f, 10f); close()
            moveTo(5f, 15f); lineTo(7f, 15f); lineTo(7f, 18f); lineTo(17f, 18f); lineTo(17f, 15f); lineTo(19f, 15f); lineTo(19f, 20f); lineTo(5f, 20f); close()
        }
    }
    val Archive: ImageVector by lazy {
        iconEvenOdd("Archive") {
            rect(3f, 3f, 18f, 4f)
            rect(4.5f, 8.5f, 15f, 12.5f); rect(6.5f, 10.5f, 11f, 8.5f)
            rect(10f, 12f, 4f, 2f)
        }
    }
    val Cloud: ImageVector by lazy {
        icon("Cloud") {
            circle(8f, 14f, 4.2f)
            circle(14.5f, 11f, 5.2f)
            rect(5f, 13f, 15.5f, 5.5f)
        }
    }
    val History: ImageVector by lazy {
        iconEvenOdd("History") {
            circle(13f, 12.5f, 8.5f); circle(13f, 12.5f, 6.2f)
            moveTo(13f, 8f); lineTo(15f, 8f); lineTo(15f, 12f); lineTo(18f, 13.8f); lineTo(17f, 15.5f); lineTo(13f, 13.5f); close()
            moveTo(3f, 4f); lineTo(8f, 5.5f); lineTo(5.5f, 9.5f); close()
        }
    }
    val Android: ImageVector by lazy {
        icon("Android") {
            moveTo(6.5f, 10f); lineTo(6.5f, 7.5f)
            arcTo(5.5f, 4.5f, 0f, false, true, 17.5f, 7.5f)
            lineTo(17.5f, 10f); close()
            rect(6.5f, 11f, 11f, 7f)
            rect(4f, 11f, 1.8f, 6f)
            rect(18.2f, 11f, 1.8f, 6f)
            rect(8f, 18.5f, 2.4f, 3.5f)
            rect(13.6f, 18.5f, 2.4f, 3.5f)
            moveTo(8f, 3.2f); lineTo(9.2f, 4.6f); lineTo(8.2f, 5.4f); lineTo(7f, 4f); close()
            moveTo(16f, 3.2f); lineTo(17f, 4f); lineTo(15.8f, 5.4f); lineTo(14.8f, 4.6f); close()
        }
    }
    val StarBorder: ImageVector by lazy {
        icon("StarBorder") {
            moveTo(12f, 2.5f); lineTo(14.9f, 8.6f); lineTo(21.5f, 9.5f); lineTo(16.7f, 14.1f)
            lineTo(17.8f, 20.7f); lineTo(12f, 17.6f); lineTo(6.2f, 20.7f); lineTo(7.3f, 14.1f)
            lineTo(2.5f, 9.5f); lineTo(9.1f, 8.6f); close()
        }
    }
    val Pause: ImageVector by lazy {
        icon("Pause") {
            rect(6f, 5f, 4f, 14f)
            rect(14f, 5f, 4f, 14f)
        }
    }
}
