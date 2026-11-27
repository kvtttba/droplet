package io.github.kvtttba.droplet.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Curated Material vector icons bundled directly to keep Debug & Release APKs lean.
 */
object DropletIcons {
    val Visibility: ImageVector by lazy {
        ImageVector.Builder(
            name = "Visibility",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4f)
                curveTo(7f, 4f, 2.73f, 7.11f, 1f, 11.5f)
                curveTo(2.73f, 15.89f, 7f, 19f, 12f, 19f)
                reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
                curveTo(21.27f, 7.11f, 17f, 4f, 12f, 4f)
                close()
                moveTo(12f, 16.5f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
                reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
                close()
                moveTo(12f, 8.5f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                close()
            }
        }.build()
    }

    val VisibilityOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "VisibilityOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 6.5f)
                curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
                curveToRelative(0f, 0.51f, -0.1f, 1f, -0.24f, 1.46f)
                lineToRelative(3.06f, 3.06f)
                curveToRelative(1.39f, -1.23f, 2.49f, -2.77f, 3.18f, -4.53f)
                curveTo(21.27f, 7.11f, 17f, 4f, 12f, 4f)
                curveToRelative(-1.27f, 0f, -2.49f, 0.2f, -3.64f, 0.57f)
                lineToRelative(2.17f, 2.17f)
                curveToRelative(0.47f, -0.14f, 0.96f, -0.24f, 1.47f, -0.24f)
                close()
                moveTo(2.71f, 3.16f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0f, 1.41f)
                lineToRelative(1.97f, 1.97f)
                curveTo(3.06f, 7.83f, 1.77f, 9.53f, 1f, 11.5f)
                curveTo(2.73f, 15.89f, 7f, 19f, 12f, 19f)
                curveToRelative(1.52f, 0f, 2.97f, -0.3f, 4.31f, -0.82f)
                lineToRelative(2.72f, 2.72f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                lineTo(4.13f, 3.16f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.42f, 0f)
                close()
                moveTo(12f, 16.5f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                curveToRelative(0f, -0.77f, 0.18f, -1.5f, 0.49f, -2.14f)
                lineToRelative(1.57f, 1.57f)
                curveToRelative(-0.03f, 0.18f, -0.06f, 0.37f, -0.06f, 0.57f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
                curveToRelative(0.2f, 0f, 0.38f, -0.03f, 0.57f, -0.07f)
                lineTo(14.14f, 16f)
                curveToRelative(-0.65f, 0.32f, -1.37f, 0.5f, -2.14f, 0.5f)
                close()
                moveTo(14.97f, 11.17f)
                curveToRelative(-0.15f, -1.4f, -1.25f, -2.49f, -2.64f, -2.64f)
                lineToRelative(2.64f, 2.64f)
                close()
            }
        }.build()
    }

    val ReceiptLong: ImageVector by lazy {
        ImageVector.Builder(
            name = "ReceiptLong",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 9f)
                horizontalLineToRelative(-4f)
                curveTo(9.45f, 9f, 9f, 8.55f, 9f, 8f)
                verticalLineToRelative(0f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                horizontalLineToRelative(4f)
                curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
                verticalLineToRelative(0f)
                curveTo(15f, 8.55f, 14.55f, 9f, 14f, 9f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 12f)
                horizontalLineToRelative(-4f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                verticalLineToRelative(0f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                horizontalLineToRelative(4f)
                curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f)
                verticalLineToRelative(0f)
                curveTo(15f, 11.55f, 14.55f, 12f, 14f, 12f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.5f, 3.5f)
                lineTo(18f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(15f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(12f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(9f, 2f)
                lineTo(7.5f, 3.5f)
                lineTo(6f, 2f)
                verticalLineToRelative(14f)
                horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(2f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
                horizontalLineToRelative(12f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                verticalLineTo(2f)
                lineTo(19.5f, 3.5f)
                close()
                moveTo(15f, 20f)
                horizontalLineTo(6f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(3f)
                horizontalLineToRelative(4f)
                horizontalLineToRelative(3f)
                verticalLineTo(20f)
                close()
                moveTo(19f, 19f)
                curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f)
                verticalLineToRelative(-2f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineToRelative(-2f)
                horizontalLineToRelative(-2f)
                horizontalLineTo(8f)
                verticalLineTo(5f)
                horizontalLineToRelative(11f)
                verticalLineTo(19f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 8f)
                moveToRelative(-1f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 11f)
                moveToRelative(-1f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            }
        }.build()
    }

    val ReceiptLongOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "ReceiptLongOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.5f, 3.5f)
                lineTo(18f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(15f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(12f, 2f)
                lineToRelative(-1.5f, 1.5f)
                lineTo(9f, 2f)
                lineTo(7.5f, 3.5f)
                lineTo(6f, 2f)
                verticalLineToRelative(14f)
                horizontalLineTo(3f)
                verticalLineToRelative(3f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
                horizontalLineToRelative(12f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                verticalLineTo(2f)
                lineTo(19.5f, 3.5f)
                close()
                moveTo(15f, 20f)
                horizontalLineTo(6f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(10f)
                verticalLineTo(20f)
                close()
                moveTo(19f, 19f)
                curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f)
                verticalLineToRelative(-3f)
                horizontalLineTo(8f)
                verticalLineTo(5f)
                horizontalLineToRelative(11f)
                verticalLineTo(19f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 7f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-6f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 7f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-2f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 10f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-6f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 10f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val AccountBalanceWallet: ImageVector by lazy {
        ImageVector.Builder(
            name = "AccountBalanceWallet",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 16f)
                lineTo(10f, 8f)
                curveToRelative(0f, -1.1f, 0.89f, -2f, 2f, -2f)
                horizontalLineToRelative(9f)
                lineTo(21f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                lineTo(5f, 3f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(-9f)
                curveToRelative(-1.11f, 0f, -2f, -0.9f, -2f, -2f)
                close()
                moveTo(13f, 8f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(9f)
                lineTo(22f, 8f)
                horizontalLineToRelative(-9f)
                close()
                moveTo(16f, 13.5f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
            }
        }.build()
    }

    val AccountBalanceWalletOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "AccountBalanceWalletOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 7.28f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-2.28f)
                curveToRelative(0.59f, -0.35f, 1f, -0.98f, 1f, -1.72f)
                verticalLineTo(9f)
                curveToRelative(0f, -0.74f, -0.41f, -1.37f, -1f, -1.72f)
                close()
                moveTo(20f, 9f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-7f)
                verticalLineTo(9f)
                horizontalLineToRelative(7f)
                close()
                moveTo(5f, 19f)
                verticalLineTo(5f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(-6f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                horizontalLineTo(5f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 12f)
                moveToRelative(-1.5f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, 3f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, true, true, -3f, 0f)
            }
        }.build()
    }

    val BarChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "BarChart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 20f)
                lineTo(6f, 20f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-7f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(0f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(7f)
                curveTo(4f, 19.1f, 4.9f, 20f, 6f, 20f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 15f)
                verticalLineToRelative(3f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(0f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-3f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(0f)
                curveTo(16.9f, 13f, 16f, 13.9f, 16f, 15f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 20f)
                lineTo(12f, 20f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(0f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(12f)
                curveTo(10f, 19.1f, 10.9f, 20f, 12f, 20f)
                close()
            }
        }.build()
    }

    val BarChartOutlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "BarChartOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 9f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(11f)
                horizontalLineToRelative(-4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 13f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(7f)
                horizontalLineToRelative(-4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 4f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(16f)
                horizontalLineToRelative(-4f)
                close()
            }
        }.build()
    }

    val CalendarMonth: ImageVector by lazy {
        ImageVector.Builder(
            name = "CalendarMonth",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 2f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(1f)
                horizontalLineTo(8f)
                verticalLineTo(3f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveTo(6f, 2.45f, 6f, 3f)
                verticalLineToRelative(1f)
                horizontalLineTo(5f)
                curveTo(3.89f, 4f, 3.01f, 4.9f, 3.01f, 6f)
                lineTo(3f, 20f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-1f)
                verticalLineTo(3f)
                curveTo(18f, 2.45f, 17.55f, 2f, 17f, 2f)
                close()
                moveTo(19f, 20f)
                horizontalLineTo(5f)
                verticalLineTo(10f)
                horizontalLineToRelative(14f)
                verticalLineTo(20f)
                close()
                moveTo(11f, 13f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(11f, 13.55f, 11f, 13f)
                close()
                moveTo(7f, 13f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(7f, 13.55f, 7f, 13f)
                close()
                moveTo(15f, 13f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(15f, 13.55f, 15f, 13f)
                close()
                moveTo(11f, 17f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(11f, 17.55f, 11f, 17f)
                close()
                moveTo(7f, 17f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(7f, 17.55f, 7f, 17f)
                close()
                moveTo(15f, 17f)
                curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
                reflectiveCurveToRelative(1f, 0.45f, 1f, 1f)
                reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveTo(15f, 17.55f, 15f, 17f)
                close()
            }
        }.build()
    }

    val Restaurant: ImageVector by lazy {
        ImageVector.Builder(
            name = "Restaurant",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 6f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(7f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                lineTo(21f, 3.13f)
                curveToRelative(0f, -0.65f, -0.61f, -1.13f, -1.24f, -0.98f)
                curveTo(17.6f, 2.68f, 16f, 4.51f, 16f, 6f)
                close()
                moveTo(11f, 9f)
                lineTo(9f, 9f)
                lineTo(9f, 3f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
                verticalLineToRelative(6f)
                lineTo(5f, 9f)
                lineTo(5f, 3f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 2.21f, 1.79f, 4f, 4f, 4f)
                verticalLineToRelative(8f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-8f)
                curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f)
                lineTo(13f, 3f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
                verticalLineToRelative(6f)
                close()
            }
        }.build()
    }

    val DirectionsSubway: ImageVector by lazy {
        ImageVector.Builder(
            name = "DirectionsSubway",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveToRelative(-4.42f, 0f, -8f, 0.5f, -8f, 4f)
                verticalLineToRelative(9.5f)
                curveTo(4f, 17.43f, 5.57f, 19f, 7.5f, 19f)
                lineToRelative(-1.15f, 1.15f)
                curveToRelative(-0.31f, 0.31f, -0.09f, 0.85f, 0.36f, 0.85f)
                lineTo(17.3f, 21f)
                curveToRelative(0.45f, 0f, 0.67f, -0.54f, 0.35f, -0.85f)
                lineTo(16.5f, 19f)
                curveToRelative(1.93f, 0f, 3.5f, -1.57f, 3.5f, -3.5f)
                lineTo(20f, 6f)
                curveToRelative(0f, -3.5f, -3.58f, -4f, -8f, -4f)
                close()
                moveTo(7.5f, 17f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(6.67f, 14f, 7.5f, 14f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(8.33f, 17f, 7.5f, 17f)
                close()
                moveTo(11f, 11f)
                lineTo(6f, 11f)
                lineTo(6f, 6f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(5f)
                close()
                moveTo(16.5f, 17f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
                moveTo(18f, 11f)
                horizontalLineToRelative(-5f)
                lineTo(13f, 6f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(5f)
                close()
            }
        }.build()
    }

    val LocalTaxi: ImageVector by lazy {
        ImageVector.Builder(
            name = "LocalTaxi",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.92f, 6.01f)
                curveTo(18.72f, 5.42f, 18.16f, 5f, 17.5f, 5f)
                lineTo(15f, 5f)
                lineTo(15f, 4f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineToRelative(-4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(1f)
                lineTo(6.5f, 5f)
                curveToRelative(-0.66f, 0f, -1.21f, 0.42f, -1.42f, 1.01f)
                lineToRelative(-1.97f, 5.67f)
                curveToRelative(-0.07f, 0.21f, -0.11f, 0.43f, -0.11f, 0.66f)
                verticalLineToRelative(7.16f)
                curveToRelative(0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveTo(6f, 20.33f, 6f, 19.5f)
                lineTo(6f, 19f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(0.5f)
                curveToRelative(0f, 0.82f, 0.67f, 1.5f, 1.5f, 1.5f)
                curveToRelative(0.82f, 0f, 1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineToRelative(-7.16f)
                curveToRelative(0f, -0.22f, -0.04f, -0.45f, -0.11f, -0.66f)
                lineToRelative(-1.97f, -5.67f)
                close()
                moveTo(6.5f, 16f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveTo(5.67f, 13f, 6.5f, 13f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(7.33f, 16f, 6.5f, 16f)
                close()
                moveTo(17.5f, 16f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f)
                close()
                moveTo(5f, 11f)
                lineToRelative(1.5f, -4.5f)
                horizontalLineToRelative(11f)
                lineTo(19f, 11f)
                lineTo(5f, 11f)
                close()
            }
        }.build()
    }

    val TrendingUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "TrendingUp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = true,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.85f, 6.85f)
                lineToRelative(1.44f, 1.44f)
                lineToRelative(-4.88f, 4.88f)
                lineToRelative(-3.29f, -3.29f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                lineToRelative(-6f, 6.01f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0f, 1.41f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                lineTo(9.41f, 12f)
                lineToRelative(3.29f, 3.29f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                lineToRelative(5.59f, -5.58f)
                lineToRelative(1.44f, 1.44f)
                curveToRelative(0.31f, 0.31f, 0.85f, 0.09f, 0.85f, -0.35f)
                verticalLineTo(6.5f)
                curveToRelative(0.01f, -0.28f, -0.21f, -0.5f, -0.49f, -0.5f)
                horizontalLineToRelative(-4.29f)
                curveToRelative(-0.45f, 0f, -0.67f, 0.54f, -0.36f, 0.85f)
                close()
            }
        }.build()
    }

    val AccountBalance: ImageVector by lazy {
        ImageVector.Builder(
            name = "AccountBalance",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 11.5f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveTo(7f, 16.33f, 7f, 15.5f)
                verticalLineToRelative(-4f)
                curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveTo(4f, 10.67f, 4f, 11.5f)
                close()
                moveTo(10f, 11.5f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineToRelative(-4f)
                curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                close()
                moveTo(3.5f, 22f)
                horizontalLineToRelative(16f)
                curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f)
                reflectiveCurveToRelative(-0.67f, -1.5f, -1.5f, -1.5f)
                horizontalLineToRelative(-16f)
                curveToRelative(-0.83f, 0f, -1.5f, 0.67f, -1.5f, 1.5f)
                reflectiveCurveTo(2.67f, 22f, 3.5f, 22f)
                close()
                moveTo(16f, 11.5f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                reflectiveCurveToRelative(1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineToRelative(-4f)
                curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                close()
                moveTo(10.57f, 1.49f)
                lineToRelative(-7.9f, 4.16f)
                curveToRelative(-0.41f, 0.21f, -0.67f, 0.64f, -0.67f, 1.1f)
                curveTo(2f, 7.44f, 2.56f, 8f, 3.25f, 8f)
                horizontalLineToRelative(16.51f)
                curveTo(20.44f, 8f, 21f, 7.44f, 21f, 6.75f)
                curveToRelative(0f, -0.46f, -0.26f, -0.89f, -0.67f, -1.1f)
                lineToRelative(-7.9f, -4.16f)
                curveToRelative(-0.58f, -0.31f, -1.28f, -0.31f, -1.86f, 0f)
                close()
            }
        }.build()
    }

    val CreditCard: ImageVector by lazy {
        ImageVector.Builder(
            name = "CreditCard",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 4f)
                lineTo(4f, 4f)
                curveToRelative(-1.11f, 0f, -1.99f, 0.89f, -1.99f, 2f)
                lineTo(2f, 18f)
                curveToRelative(0f, 1.11f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.11f, 0f, 2f, -0.89f, 2f, -2f)
                lineTo(22f, 6f)
                curveToRelative(0f, -1.11f, -0.89f, -2f, -2f, -2f)
                close()
                moveTo(19f, 18f)
                lineTo(5f, 18f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                verticalLineToRelative(-5f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(5f)
                curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                close()
                moveTo(20f, 8f)
                lineTo(4f, 8f)
                lineTo(4f, 6f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val CandlestickChart: ImageVector by lazy {
        ImageVector.Builder(
            name = "CandlestickChart",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 4f)
                lineTo(8f, 4f)
                curveTo(7.45f, 4f, 7f, 4.45f, 7f, 5f)
                verticalLineToRelative(1f)
                horizontalLineTo(6f)
                curveTo(5.45f, 6f, 5f, 6.45f, 5f, 7f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(1f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(0f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineToRelative(1f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineTo(7f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineTo(9f)
                verticalLineTo(5f)
                curveTo(9f, 4.45f, 8.55f, 4f, 8f, 4f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 8f)
                horizontalLineToRelative(-1f)
                verticalLineTo(5f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineToRelative(0f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-1f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(5f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(0f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(1f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineTo(9f)
                curveTo(19f, 8.45f, 18.55f, 8f, 18f, 8f)
                close()
            }
        }.build()
    }

    val AutoAwesome: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoAwesome",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.46f, 8f)
                lineToRelative(0.79f, -1.75f)
                lineTo(22f, 5.46f)
                curveToRelative(0.39f, -0.18f, 0.39f, -0.73f, 0f, -0.91f)
                lineToRelative(-1.75f, -0.79f)
                lineTo(19.46f, 2f)
                curveToRelative(-0.18f, -0.39f, -0.73f, -0.39f, -0.91f, 0f)
                lineToRelative(-0.79f, 1.75f)
                lineTo(16f, 4.54f)
                curveToRelative(-0.39f, 0.18f, -0.39f, 0.73f, 0f, 0.91f)
                lineToRelative(1.75f, 0.79f)
                lineTo(18.54f, 8f)
                curveTo(18.72f, 8.39f, 19.28f, 8.39f, 19.46f, 8f)
                close()
                moveTo(11.5f, 9.5f)
                lineTo(9.91f, 6f)
                curveTo(9.56f, 5.22f, 8.44f, 5.22f, 8.09f, 6f)
                lineTo(6.5f, 9.5f)
                lineTo(3f, 11.09f)
                curveToRelative(-0.78f, 0.36f, -0.78f, 1.47f, 0f, 1.82f)
                lineToRelative(3.5f, 1.59f)
                lineTo(8.09f, 18f)
                curveToRelative(0.36f, 0.78f, 1.47f, 0.78f, 1.82f, 0f)
                lineToRelative(1.59f, -3.5f)
                lineToRelative(3.5f, -1.59f)
                curveToRelative(0.78f, -0.36f, 0.78f, -1.47f, 0f, -1.82f)
                lineTo(11.5f, 9.5f)
                close()
                moveTo(18.54f, 16f)
                lineToRelative(-0.79f, 1.75f)
                lineTo(16f, 18.54f)
                curveToRelative(-0.39f, 0.18f, -0.39f, 0.73f, 0f, 0.91f)
                lineToRelative(1.75f, 0.79f)
                lineTo(18.54f, 22f)
                curveToRelative(0.18f, 0.39f, 0.73f, 0.39f, 0.91f, 0f)
                lineToRelative(0.79f, -1.75f)
                lineTo(22f, 19.46f)
                curveToRelative(0.39f, -0.18f, 0.39f, -0.73f, 0f, -0.91f)
                lineToRelative(-1.75f, -0.79f)
                lineTo(19.46f, 16f)
                curveTo(19.28f, 15.61f, 18.72f, 15.61f, 18.54f, 16f)
                close()
            }
        }.build()
    }

    val Category: ImageVector by lazy {
        ImageVector.Builder(
            name = "Category",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11.15f, 3.4f)
                lineTo(7.43f, 9.48f)
                curveToRelative(-0.41f, 0.66f, 0.07f, 1.52f, 0.85f, 1.52f)
                horizontalLineToRelative(7.43f)
                curveToRelative(0.78f, 0f, 1.26f, -0.86f, 0.85f, -1.52f)
                lineTo(12.85f, 3.4f)
                curveToRelative(-0.39f, -0.64f, -1.31f, -0.64f, -1.7f, 0f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.5f, 17.5f)
                moveToRelative(-4.5f, 0f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, 9f, 0f)
                arcToRelative(4.5f, 4.5f, 0f, true, true, -9f, 0f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 21.5f)
                horizontalLineToRelative(6f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-6f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                close()
            }
        }.build()
    }

    val CloudSync: ImageVector by lazy {
        ImageVector.Builder(
            name = "CloudSync",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(24f, 17.48f)
                curveToRelative(0f, 1.38f, -1.12f, 2.5f, -2.5f, 2.5f)
                lineTo(15f, 20f)
                curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
                curveToRelative(0f, -1.6f, 1.26f, -2.9f, 2.84f, -2.98f)
                curveTo(15.4f, 12.83f, 16.6f, 12f, 18f, 12f)
                curveToRelative(1.76f, 0f, 3.2f, 1.3f, 3.45f, 2.99f)
                curveToRelative(0.02f, 0f, 0.03f, -0.01f, 0.05f, -0.01f)
                curveTo(22.88f, 14.98f, 24f, 16.1f, 24f, 17.48f)
                close()
                moveTo(10f, 15f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
                verticalLineToRelative(1.44f)
                curveToRelative(-1.22f, -1.1f, -2f, -2.67f, -2f, -4.44f)
                curveToRelative(0f, -2.38f, 1.39f, -4.43f, 3.4f, -5.4f)
                curveTo(9.77f, 6.42f, 10f, 6.04f, 10f, 5.63f)
                curveToRelative(0f, -0.71f, -0.73f, -1.18f, -1.37f, -0.88f)
                curveTo(5.89f, 6.03f, 4f, 8.79f, 4f, 12f)
                curveToRelative(0f, 2.4f, 1.06f, 4.54f, 2.73f, 6f)
                horizontalLineTo(5f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                reflectiveCurveToRelative(0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(4f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineTo(15f)
                close()
                moveTo(19f, 6f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                horizontalLineToRelative(-4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                verticalLineTo(7.56f)
                curveToRelative(0.98f, 0.89f, 1.68f, 2.08f, 1.92f, 3.44f)
                lineToRelative(2.02f, 0f)
                curveToRelative(-0.25f, -1.99f, -1.23f, -3.74f, -2.66f, -5f)
                horizontalLineTo(19f)
                close()
            }
        }.build()
    }

    val FileDownload: ImageVector by lazy {
        ImageVector.Builder(
            name = "FileDownload",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.59f, 9f)
                horizontalLineTo(15f)
                verticalLineTo(4f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineToRelative(-4f)
                curveTo(9.45f, 3f, 9f, 3.45f, 9f, 4f)
                verticalLineToRelative(5f)
                horizontalLineTo(7.41f)
                curveToRelative(-0.89f, 0f, -1.34f, 1.08f, -0.71f, 1.71f)
                lineToRelative(4.59f, 4.59f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0f)
                lineToRelative(4.59f, -4.59f)
                curveTo(17.92f, 10.08f, 17.48f, 9f, 16.59f, 9f)
                close()
                moveTo(5f, 19f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(12f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                horizontalLineTo(6f)
                curveTo(5.45f, 18f, 5f, 18.45f, 5f, 19f)
                close()
            }
        }.build()
    }

    val Fingerprint: ImageVector by lazy {
        ImageVector.Builder(
            name = "Fingerprint",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.81f, 4.47f)
                curveToRelative(-0.08f, 0f, -0.16f, -0.02f, -0.23f, -0.06f)
                curveTo(15.66f, 3.42f, 14f, 3f, 12.01f, 3f)
                curveToRelative(-1.98f, 0f, -3.86f, 0.47f, -5.57f, 1.41f)
                curveToRelative(-0.24f, 0.13f, -0.54f, 0.04f, -0.68f, -0.2f)
                curveToRelative(-0.13f, -0.24f, -0.04f, -0.55f, 0.2f, -0.68f)
                curveTo(7.82f, 2.52f, 9.86f, 2f, 12.01f, 2f)
                curveToRelative(2.13f, 0f, 3.99f, 0.47f, 6.03f, 1.52f)
                curveToRelative(0.25f, 0.13f, 0.34f, 0.43f, 0.21f, 0.67f)
                curveToRelative(-0.09f, 0.18f, -0.26f, 0.28f, -0.44f, 0.28f)
                close()
                moveTo(3.5f, 9.72f)
                curveToRelative(-0.1f, 0f, -0.2f, -0.03f, -0.29f, -0.09f)
                curveToRelative(-0.23f, -0.16f, -0.28f, -0.47f, -0.12f, -0.7f)
                curveToRelative(0.99f, -1.4f, 2.25f, -2.5f, 3.75f, -3.27f)
                curveTo(9.98f, 4.04f, 14f, 4.03f, 17.15f, 5.65f)
                curveToRelative(1.5f, 0.77f, 2.76f, 1.86f, 3.75f, 3.25f)
                curveToRelative(0.16f, 0.22f, 0.11f, 0.54f, -0.12f, 0.7f)
                curveToRelative(-0.23f, 0.16f, -0.54f, 0.11f, -0.7f, -0.12f)
                curveToRelative(-0.9f, -1.26f, -2.04f, -2.25f, -3.39f, -2.94f)
                curveToRelative(-2.87f, -1.47f, -6.54f, -1.47f, -9.4f, 0.01f)
                curveToRelative(-1.36f, 0.7f, -2.5f, 1.7f, -3.4f, 2.96f)
                curveToRelative(-0.08f, 0.14f, -0.23f, 0.21f, -0.39f, 0.21f)
                close()
                moveTo(9.75f, 21.79f)
                curveToRelative(-0.13f, 0f, -0.26f, -0.05f, -0.35f, -0.15f)
                curveToRelative(-0.87f, -0.87f, -1.34f, -1.43f, -2.01f, -2.64f)
                curveToRelative(-0.69f, -1.23f, -1.05f, -2.73f, -1.05f, -4.34f)
                curveToRelative(0f, -2.97f, 2.54f, -5.39f, 5.66f, -5.39f)
                reflectiveCurveToRelative(5.66f, 2.42f, 5.66f, 5.39f)
                curveToRelative(0f, 0.28f, -0.22f, 0.5f, -0.5f, 0.5f)
                reflectiveCurveToRelative(-0.5f, -0.22f, -0.5f, -0.5f)
                curveToRelative(0f, -2.42f, -2.09f, -4.39f, -4.66f, -4.39f)
                reflectiveCurveToRelative(-4.66f, 1.97f, -4.66f, 4.39f)
                curveToRelative(0f, 1.44f, 0.32f, 2.77f, 0.93f, 3.85f)
                curveToRelative(0.64f, 1.15f, 1.08f, 1.64f, 1.85f, 2.42f)
                curveToRelative(0.19f, 0.2f, 0.19f, 0.51f, 0f, 0.71f)
                curveToRelative(-0.11f, 0.1f, -0.24f, 0.15f, -0.37f, 0.15f)
                close()
                moveTo(16.92f, 19.94f)
                curveToRelative(-1.19f, 0f, -2.24f, -0.3f, -3.1f, -0.89f)
                curveToRelative(-1.49f, -1.01f, -2.38f, -2.65f, -2.38f, -4.39f)
                curveToRelative(0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                reflectiveCurveToRelative(0.5f, 0.22f, 0.5f, 0.5f)
                curveToRelative(0f, 1.41f, 0.72f, 2.74f, 1.94f, 3.56f)
                curveToRelative(0.71f, 0.48f, 1.54f, 0.71f, 2.54f, 0.71f)
                curveToRelative(0.24f, 0f, 0.64f, -0.03f, 1.04f, -0.1f)
                curveToRelative(0.27f, -0.05f, 0.53f, 0.13f, 0.58f, 0.41f)
                curveToRelative(0.05f, 0.27f, -0.13f, 0.53f, -0.41f, 0.58f)
                curveToRelative(-0.57f, 0.11f, -1.07f, 0.12f, -1.21f, 0.12f)
                close()
                moveTo(14.91f, 22f)
                curveToRelative(-0.04f, 0f, -0.09f, -0.01f, -0.13f, -0.02f)
                curveToRelative(-1.59f, -0.44f, -2.63f, -1.03f, -3.72f, -2.1f)
                curveToRelative(-1.4f, -1.39f, -2.17f, -3.24f, -2.17f, -5.22f)
                curveToRelative(0f, -1.62f, 1.38f, -2.94f, 3.08f, -2.94f)
                reflectiveCurveToRelative(3.08f, 1.32f, 3.08f, 2.94f)
                curveToRelative(0f, 1.07f, 0.93f, 1.94f, 2.08f, 1.94f)
                reflectiveCurveToRelative(2.08f, -0.87f, 2.08f, -1.94f)
                curveToRelative(0f, -3.77f, -3.25f, -6.83f, -7.25f, -6.83f)
                curveToRelative(-2.84f, 0f, -5.44f, 1.58f, -6.61f, 4.03f)
                curveToRelative(-0.39f, 0.81f, -0.59f, 1.76f, -0.59f, 2.8f)
                curveToRelative(0f, 0.78f, 0.07f, 2.01f, 0.67f, 3.61f)
                curveToRelative(0.1f, 0.26f, -0.03f, 0.55f, -0.29f, 0.64f)
                curveToRelative(-0.26f, 0.1f, -0.55f, -0.04f, -0.64f, -0.29f)
                curveToRelative(-0.49f, -1.31f, -0.73f, -2.61f, -0.73f, -3.96f)
                curveToRelative(0f, -1.2f, 0.23f, -2.29f, 0.68f, -3.24f)
                curveToRelative(1.33f, -2.79f, 4.28f, -4.6f, 7.51f, -4.6f)
                curveToRelative(4.55f, 0f, 8.25f, 3.51f, 8.25f, 7.83f)
                curveToRelative(0f, 1.62f, -1.38f, 2.94f, -3.08f, 2.94f)
                reflectiveCurveToRelative(-3.08f, -1.32f, -3.08f, -2.94f)
                curveToRelative(0f, -1.07f, -0.93f, -1.94f, -2.08f, -1.94f)
                reflectiveCurveToRelative(-2.08f, 0.87f, -2.08f, 1.94f)
                curveToRelative(0f, 1.71f, 0.66f, 3.31f, 1.87f, 4.51f)
                curveToRelative(0.95f, 0.94f, 1.86f, 1.46f, 3.27f, 1.85f)
                curveToRelative(0.27f, 0.07f, 0.42f, 0.35f, 0.35f, 0.61f)
                curveToRelative(-0.05f, 0.23f, -0.26f, 0.38f, -0.47f, 0.38f)
                close()
            }
        }.build()
    }

    val Palette: ImageVector by lazy {
        ImageVector.Builder(
            name = "Palette",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.49f, 2f, 2f, 6.49f, 2f, 12f)
                reflectiveCurveToRelative(4.49f, 10f, 10f, 10f)
                curveToRelative(1.38f, 0f, 2.5f, -1.12f, 2.5f, -2.5f)
                curveToRelative(0f, -0.61f, -0.23f, -1.2f, -0.64f, -1.67f)
                curveToRelative(-0.08f, -0.1f, -0.13f, -0.21f, -0.13f, -0.33f)
                curveToRelative(0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                horizontalLineTo(16f)
                curveToRelative(3.31f, 0f, 6f, -2.69f, 6f, -6f)
                curveTo(22f, 6.04f, 17.51f, 2f, 12f, 2f)
                close()
                moveTo(17.5f, 13f)
                curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f)
                curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                curveTo(19f, 12.33f, 18.33f, 13f, 17.5f, 13f)
                close()
                moveTo(14.5f, 9f)
                curveTo(13.67f, 9f, 13f, 8.33f, 13f, 7.5f)
                curveTo(13f, 6.67f, 13.67f, 6f, 14.5f, 6f)
                reflectiveCurveTo(16f, 6.67f, 16f, 7.5f)
                curveTo(16f, 8.33f, 15.33f, 9f, 14.5f, 9f)
                close()
                moveTo(5f, 11.5f)
                curveTo(5f, 10.67f, 5.67f, 10f, 6.5f, 10f)
                reflectiveCurveTo(8f, 10.67f, 8f, 11.5f)
                curveTo(8f, 12.33f, 7.33f, 13f, 6.5f, 13f)
                reflectiveCurveTo(5f, 12.33f, 5f, 11.5f)
                close()
                moveTo(11f, 7.5f)
                curveTo(11f, 8.33f, 10.33f, 9f, 9.5f, 9f)
                reflectiveCurveTo(8f, 8.33f, 8f, 7.5f)
                curveTo(8f, 6.67f, 8.67f, 6f, 9.5f, 6f)
                reflectiveCurveTo(11f, 6.67f, 11f, 7.5f)
                close()
            }
        }.build()
    }

    val Repeat: ImageVector by lazy {
        ImageVector.Builder(
            name = "Repeat",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 7f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(1.79f)
                curveToRelative(0f, 0.45f, 0.54f, 0.67f, 0.85f, 0.35f)
                lineToRelative(2.79f, -2.79f)
                curveToRelative(0.2f, -0.2f, 0.2f, -0.51f, 0f, -0.71f)
                lineToRelative(-2.79f, -2.79f)
                curveToRelative(-0.31f, -0.31f, -0.85f, -0.09f, -0.85f, 0.36f)
                lineTo(17f, 5f)
                lineTo(6f, 5f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                lineTo(7f, 7f)
                close()
                moveTo(17f, 17f)
                lineTo(7f, 17f)
                verticalLineToRelative(-1.79f)
                curveToRelative(0f, -0.45f, -0.54f, -0.67f, -0.85f, -0.35f)
                lineToRelative(-2.79f, 2.79f)
                curveToRelative(-0.2f, 0.2f, -0.2f, 0.51f, 0f, 0.71f)
                lineToRelative(2.79f, 2.79f)
                curveToRelative(0.31f, 0.31f, 0.85f, 0.09f, 0.85f, -0.36f)
                lineTo(7f, 19f)
                horizontalLineToRelative(11f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-4f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveToRelative(-1f, 0.45f, -1f, 1f)
                verticalLineToRelative(3f)
                close()
            }
        }.build()
    }

    val WaterDrop: ImageVector by lazy {
        ImageVector.Builder(
            name = "WaterDrop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.66f, 2.58f)
                curveToRelative(-0.38f, -0.33f, -0.95f, -0.33f, -1.33f, 0f)
                curveTo(6.45f, 6.88f, 4f, 10.62f, 4f, 13.8f)
                curveToRelative(0f, 4.98f, 3.8f, 8.2f, 8f, 8.2f)
                reflectiveCurveToRelative(8f, -3.22f, 8f, -8.2f)
                curveTo(20f, 10.62f, 17.55f, 6.88f, 12.66f, 2.58f)
                close()
                moveTo(7.83f, 14f)
                curveToRelative(0.37f, 0f, 0.67f, 0.26f, 0.74f, 0.62f)
                curveToRelative(0.41f, 2.22f, 2.28f, 2.98f, 3.64f, 2.87f)
                curveToRelative(0.43f, -0.02f, 0.79f, 0.32f, 0.79f, 0.75f)
                curveToRelative(0f, 0.4f, -0.32f, 0.73f, -0.72f, 0.75f)
                curveToRelative(-2.13f, 0.13f, -4.62f, -1.09f, -5.19f, -4.12f)
                curveTo(7.01f, 14.42f, 7.37f, 14f, 7.83f, 14f)
                close()
            }
        }.build()
    }

    val EditNote: ImageVector by lazy {
        ImageVector.Builder(
            name = "EditNote",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 11f)
                curveToRelative(0f, 0.55f, -0.45f, 1f, -1f, 1f)
                horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
                reflectiveCurveToRelative(0.45f, -1f, 1f, -1f)
                horizontalLineToRelative(9f)
                curveTo(13.55f, 10f, 14f, 10.45f, 14f, 11f)
                close()
                moveTo(3f, 7f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(9f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                horizontalLineTo(4f)
                curveTo(3.45f, 6f, 3f, 6.45f, 3f, 7f)
                close()
                moveTo(10f, 15f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                reflectiveCurveToRelative(0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(5f)
                curveTo(9.55f, 16f, 10f, 15.55f, 10f, 15f)
                close()
                moveTo(18.01f, 12.87f)
                lineToRelative(0.71f, -0.71f)
                curveToRelative(0.39f, -0.39f, 1.02f, -0.39f, 1.41f, 0f)
                lineToRelative(0.71f, 0.71f)
                curveToRelative(0.39f, 0.39f, 0.39f, 1.02f, 0f, 1.41f)
                lineToRelative(-0.71f, 0.71f)
                lineTo(18.01f, 12.87f)
                close()
                moveTo(17.3f, 13.58f)
                lineToRelative(-5.16f, 5.16f)
                curveTo(12.05f, 18.83f, 12f, 18.95f, 12f, 19.09f)
                verticalLineToRelative(1.41f)
                curveToRelative(0f, 0.28f, 0.22f, 0.5f, 0.5f, 0.5f)
                horizontalLineToRelative(1.41f)
                curveToRelative(0.13f, 0f, 0.26f, -0.05f, 0.35f, -0.15f)
                lineToRelative(5.16f, -5.16f)
                lineTo(17.3f, 13.58f)
                close()
            }
        }.build()
    }

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 14f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                lineTo(15f, 5f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                reflectiveCurveTo(9f, 3.34f, 9f, 5f)
                verticalLineToRelative(6f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
                close()
                moveTo(17.91f, 11f)
                curveToRelative(-0.49f, 0f, -0.9f, 0.36f, -0.98f, 0.85f)
                curveTo(16.52f, 14.2f, 14.47f, 16f, 12f, 16f)
                reflectiveCurveToRelative(-4.52f, -1.8f, -4.93f, -4.15f)
                curveToRelative(-0.08f, -0.49f, -0.49f, -0.85f, -0.98f, -0.85f)
                curveToRelative(-0.61f, 0f, -1.09f, 0.54f, -1f, 1.14f)
                curveToRelative(0.49f, 3f, 2.89f, 5.35f, 5.91f, 5.78f)
                lineTo(11f, 20f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-2.08f)
                curveToRelative(3.02f, -0.43f, 5.42f, -2.78f, 5.91f, -5.78f)
                curveToRelative(0.1f, -0.6f, -0.39f, -1.14f, -1f, -1.14f)
                close()
            }
        }.build()
    }

    val PhotoCamera: ImageVector by lazy {
        ImageVector.Builder(
            name = "PhotoCamera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = false,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 12f)
                moveToRelative(-3f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 4f)
                horizontalLineToRelative(-3.17f)
                lineToRelative(-1.24f, -1.35f)
                curveToRelative(-0.37f, -0.41f, -0.91f, -0.65f, -1.47f, -0.65f)
                lineTo(9.88f, 2f)
                curveToRelative(-0.56f, 0f, -1.1f, 0.24f, -1.48f, 0.65f)
                lineTo(7.17f, 4f)
                lineTo(4f, 4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(22f, 6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
                reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
                close()
            }
        }.build()
    }

}
