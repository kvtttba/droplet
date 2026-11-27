package io.github.kvtttba.droplet.core.ui.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class DropletIconOption(
    val key: String,
    val label: String,
    val vector: ImageVector,
)

/**
 * Registry mapping persisted account/category/tag icon keys and hex color strings
 * to curated Material vector icons and Compose colors.
 */
object DropletIconRegistry {

    val allOptions: List<DropletIconOption> = listOf(
        DropletIconOption("restaurant", "餐饮", DropletIcons.Restaurant),
        DropletIconOption("directions_subway", "交通", DropletIcons.DirectionsSubway),
        DropletIconOption("local_taxi", "打车", DropletIcons.LocalTaxi),
        DropletIconOption("shopping_bag", "购物", DropletIcons.ShoppingBag),
        DropletIconOption("home", "居住", Icons.Rounded.Home),
        DropletIconOption("movie", "娱乐", DropletIcons.Movie),
        DropletIconOption("medical_services", "医疗", DropletIcons.MedicalServices),
        DropletIconOption("menu_book", "学习", DropletIcons.MenuBook),
        DropletIconOption("card_giftcard", "人情", DropletIcons.CardGiftcard),
        DropletIconOption("account_balance", "银行", DropletIcons.AccountBalance),
        DropletIconOption("account_balance_wallet", "钱包", DropletIcons.AccountBalanceWallet),
        DropletIconOption("credit_card", "信用卡", DropletIcons.CreditCard),
        DropletIconOption("payments", "现金", DropletIcons.Payments),
        DropletIconOption("work", "工作", DropletIcons.Work),
        DropletIconOption("trending_up", "投资", DropletIcons.TrendingUp),
        DropletIconOption("savings", "储蓄", DropletIcons.Savings),
        DropletIconOption("receipt_long", "票据", DropletIcons.ReceiptLong),
        DropletIconOption("flight", "旅行", DropletIcons.Flight),
        DropletIconOption("label", "标签", DropletIcons.Label),
        DropletIconOption("bolt", "快捷", DropletIcons.Bolt),
        DropletIconOption("event_repeat", "周期", DropletIcons.Repeat),
        DropletIconOption("more_horiz", "其他", DropletIcons.MoreHoriz),
    )

    val colorPalette: List<String> = listOf(
        "#006970",
        "#1565C0",
        "#2E7D32",
        "#EF6C00",
        "#D81B60",
        "#8E24AA",
        "#E53935",
        "#3949AB",
        "#00897B",
        "#546E7A",
        "#FB8C00",
        "#F4511E",
    )

    fun resolve(key: String?): ImageVector = when (key?.trim()?.lowercase()) {
        "restaurant" -> DropletIcons.Restaurant
        "directions_subway" -> DropletIcons.DirectionsSubway
        "local_taxi" -> DropletIcons.LocalTaxi
        "shopping_bag" -> DropletIcons.ShoppingBag
        "shopping_cart" -> Icons.Rounded.ShoppingCart
        "home" -> Icons.Rounded.Home
        "movie" -> DropletIcons.Movie
        "medical_services" -> DropletIcons.MedicalServices
        "menu_book" -> DropletIcons.MenuBook
        "card_giftcard" -> DropletIcons.CardGiftcard
        "account_balance" -> DropletIcons.AccountBalance
        "account_balance_wallet" -> DropletIcons.AccountBalanceWallet
        "credit_card" -> DropletIcons.CreditCard
        "payments" -> DropletIcons.Payments
        "work", "business_center" -> DropletIcons.Work
        "trending_up" -> DropletIcons.TrendingUp
        "savings" -> DropletIcons.Savings
        "receipt_long" -> DropletIcons.ReceiptLong
        "flight" -> DropletIcons.Flight
        "label" -> DropletIcons.Label
        "bolt" -> DropletIcons.Bolt
        "event_repeat", "repeat" -> DropletIcons.Repeat
        "swap_horiz" -> DropletIcons.SwapHoriz
        "call_split" -> DropletIcons.CallSplit
        "more_horiz" -> DropletIcons.MoreHoriz
        else -> DropletIcons.Category
    }

    fun parseColorOrNull(hex: String?): Color? {
        val cleaned = hex?.trim()?.removePrefix("#") ?: return null
        val argb = when (cleaned.length) {
            6 -> cleaned.toLongOrNull(16)?.or(0xFF000000L)
            8 -> cleaned.toLongOrNull(16)
            else -> null
        } ?: return null
        return Color(argb.toInt())
    }

    fun parseColor(hex: String?, fallback: Color): Color =
        parseColorOrNull(hex) ?: fallback
}
