package io.github.kvtttba.droplet.core.database.seed

import io.github.kvtttba.droplet.core.model.Account
import io.github.kvtttba.droplet.core.model.AccountSubType
import io.github.kvtttba.droplet.core.model.AccountType
import io.github.kvtttba.droplet.core.model.Currency
import io.github.kvtttba.droplet.core.model.SystemAccounts
import io.github.kvtttba.droplet.core.model.Tag

/**
 * Deterministic pre-populated catalog for fresh Droplet installations:
 * - Built-in `Equity:Trading:{CCY}` & `Equity:OpeningBalance:{CCY}` system equity accounts
 *   reusing [SystemAccounts] canonical IDs
 * - Built-in reimbursement receivable account & common daily accounts
 * - Yimu-style 2-level expense & income categories (parent + children)
 * - Frequently used default tags
 */
object DefaultSeedCatalog {

    object AccountIds {
        // System operational account
        const val REIMBURSEMENT_CNY = "seed:account:reimbursement:cny"

        // Default daily accounts
        const val CASH = "seed:account:cash"
        const val DEBIT_CARD = "seed:account:debit_card"
        const val WECHAT = "seed:account:wechat"
        const val ALIPAY = "seed:account:alipay"
        const val CREDIT_CARD = "seed:account:credit_card"

        // Expense root categories
        const val EXP_FOOD = "seed:cat:exp:food"
        const val EXP_TRANSPORT = "seed:cat:exp:transport"
        const val EXP_SHOPPING = "seed:cat:exp:shopping"
        const val EXP_HOUSING = "seed:cat:exp:housing"
        const val EXP_ENTERTAINMENT = "seed:cat:exp:entertainment"
        const val EXP_MEDICAL = "seed:cat:exp:medical"
        const val EXP_EDUCATION = "seed:cat:exp:education"
        const val EXP_SOCIAL = "seed:cat:exp:social"
        const val EXP_FINANCE = "seed:cat:exp:finance"
        const val EXP_OTHER = "seed:cat:exp:other"

        // Expense child categories (selected deterministic constants used across domain flows)
        const val EXP_FOOD_BREAKFAST = "seed:cat:exp:food:breakfast"
        const val EXP_FOOD_LUNCH = "seed:cat:exp:food:lunch"
        const val EXP_FOOD_DINNER = "seed:cat:exp:food:dinner"
        const val EXP_FOOD_DELIVERY = "seed:cat:exp:food:delivery"
        const val EXP_FOOD_SNACKS = "seed:cat:exp:food:snacks"
        const val EXP_FOOD_GROCERY = "seed:cat:exp:food:grocery"
        const val EXP_FOOD_DINING_OUT = "seed:cat:exp:food:dining_out"

        const val EXP_TRANSPORT_TRANSIT = "seed:cat:exp:transport:transit"
        const val EXP_TRANSPORT_TAXI = "seed:cat:exp:transport:taxi"
        const val EXP_TRANSPORT_FUEL_PARKING = "seed:cat:exp:transport:fuel_parking"
        const val EXP_TRANSPORT_LONG_DISTANCE = "seed:cat:exp:transport:long_distance"
        const val EXP_TRANSPORT_MAINTENANCE = "seed:cat:exp:transport:maintenance"

        const val EXP_SHOPPING_DAILY = "seed:cat:exp:shopping:daily"
        const val EXP_SHOPPING_CLOTHING = "seed:cat:exp:shopping:clothing"
        const val EXP_SHOPPING_DIGITAL = "seed:cat:exp:shopping:digital"
        const val EXP_SHOPPING_BEAUTY = "seed:cat:exp:shopping:beauty"

        const val EXP_HOUSING_RENT_MORTGAGE = "seed:cat:exp:housing:rent_mortgage"
        const val EXP_HOUSING_UTILITIES = "seed:cat:exp:housing:utilities"
        const val EXP_HOUSING_PROPERTY_NET = "seed:cat:exp:housing:property_net"
        const val EXP_HOUSING_REPAIR = "seed:cat:exp:housing:repair"

        const val EXP_ENTERTAINMENT_MOVIE = "seed:cat:exp:entertainment:movie"
        const val EXP_ENTERTAINMENT_GAME = "seed:cat:exp:entertainment:game"
        const val EXP_ENTERTAINMENT_TRAVEL = "seed:cat:exp:entertainment:travel"
        const val EXP_ENTERTAINMENT_FITNESS = "seed:cat:exp:entertainment:fitness"

        const val EXP_MEDICAL_CLINIC = "seed:cat:exp:medical:clinic"
        const val EXP_MEDICAL_PHARMACY = "seed:cat:exp:medical:pharmacy"
        const val EXP_MEDICAL_CHECKUP = "seed:cat:exp:medical:checkup"

        const val EXP_EDUCATION_BOOKS = "seed:cat:exp:education:books"
        const val EXP_EDUCATION_OFFICE = "seed:cat:exp:education:office"
        const val EXP_EDUCATION_SUBSCRIPTION = "seed:cat:exp:education:subscription"

        const val EXP_SOCIAL_RED_PACKET = "seed:cat:exp:social:red_packet"
        const val EXP_SOCIAL_GIFT = "seed:cat:exp:social:gift"
        const val EXP_SOCIAL_FAMILY = "seed:cat:exp:social:family"

        const val EXP_FINANCE_INTEREST_FEE = "seed:cat:exp:finance:interest_fee"
        const val EXP_FINANCE_BAD_DEBT = "seed:cat:exp:finance:bad_debt"
        const val EXP_FINANCE_INVEST_LOSS = "seed:cat:exp:finance:invest_loss"
        const val EXP_FINANCE_INSURANCE = "seed:cat:exp:finance:insurance"

        const val EXP_OTHER_MISC = "seed:cat:exp:other:misc"

        // Income root categories
        const val INC_WORK = "seed:cat:inc:work"
        const val INC_INVEST = "seed:cat:inc:invest"
        const val INC_OTHER = "seed:cat:inc:other"

        // Income child categories
        const val INC_WORK_SALARY = "seed:cat:inc:work:salary"
        const val INC_WORK_BONUS = "seed:cat:inc:work:bonus"
        const val INC_WORK_PART_TIME = "seed:cat:inc:work:part_time"

        const val INC_INVEST_DIVIDEND = "seed:cat:inc:invest:dividend"
        const val INC_INVEST_INTEREST = "seed:cat:inc:invest:interest"
        const val INC_INVEST_CAPITAL_GAIN = "seed:cat:inc:invest:capital_gain"

        const val INC_OTHER_RED_PACKET = "seed:cat:inc:other:red_packet"
        const val INC_OTHER_REBATE = "seed:cat:inc:other:rebate"
        const val INC_OTHER_SECOND_HAND = "seed:cat:inc:other:second_hand"
        const val INC_OTHER_WINDFALL = "seed:cat:inc:other:windfall"
    }

    object TagIds {
        const val WORKDAY = "seed:tag:workday"
        const val TRAVEL = "seed:tag:travel"
        const val BUSINESS_TRIP = "seed:tag:business_trip"
        const val FIXED_EXPENSE = "seed:tag:fixed_expense"
        const val REIMBURSABLE = "seed:tag:reimbursable"
        const val FAMILY = "seed:tag:family"
        const val IMPULSE = "seed:tag:impulse"
    }

    fun systemEquityAccounts(nowEpochMs: Long = 0L): List<Account> =
        SystemAccounts.allSystemEquityAccounts().mapIndexed { index, account ->
            account.copy(
                sortOrder = index,
                createdAt = nowEpochMs,
                updatedAt = nowEpochMs,
            )
        }

    fun defaultOperationalAccounts(nowEpochMs: Long = 0L): List<Account> = listOf(
        Account(
            id = AccountIds.REIMBURSEMENT_CNY,
            name = "待报销",
            type = AccountType.ASSET,
            subType = AccountSubType.REIMBURSEMENT,
            currency = Currency.CNY,
            icon = "receipt_long",
            color = "#FB8C00",
            includeInNetWorth = true,
            isSystem = true,
            sortOrder = 900,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Account(
            id = AccountIds.CASH,
            name = "现金",
            type = AccountType.ASSET,
            subType = AccountSubType.CASH,
            currency = Currency.CNY,
            icon = "payments",
            color = "#2E7D32",
            includeInNetWorth = true,
            isSystem = false,
            sortOrder = 10,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Account(
            id = AccountIds.DEBIT_CARD,
            name = "储蓄卡",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            currency = Currency.CNY,
            icon = "account_balance",
            color = "#1565C0",
            includeInNetWorth = true,
            isSystem = false,
            sortOrder = 20,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Account(
            id = AccountIds.WECHAT,
            name = "微信零钱",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            currency = Currency.CNY,
            icon = "account_balance_wallet",
            color = "#07C160",
            includeInNetWorth = true,
            isSystem = false,
            sortOrder = 30,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Account(
            id = AccountIds.ALIPAY,
            name = "支付宝",
            type = AccountType.ASSET,
            subType = AccountSubType.DEBIT_CARD,
            currency = Currency.CNY,
            icon = "account_balance_wallet",
            color = "#1677FF",
            includeInNetWorth = true,
            isSystem = false,
            sortOrder = 40,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Account(
            id = AccountIds.CREDIT_CARD,
            name = "信用卡",
            type = AccountType.LIABILITY,
            subType = AccountSubType.CREDIT_CARD,
            currency = Currency.CNY,
            icon = "credit_card",
            color = "#C62828",
            includeInNetWorth = true,
            isSystem = false,
            sortOrder = 50,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
    )

    fun defaultExpenseCategories(nowEpochMs: Long = 0L): List<Account> = buildList {
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_FOOD,
            parentName = "餐饮",
            icon = "restaurant",
            color = "#EF6C00",
            parentOrder = 100,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_FOOD_BREAKFAST to "早餐",
                AccountIds.EXP_FOOD_LUNCH to "午餐",
                AccountIds.EXP_FOOD_DINNER to "晚餐",
                AccountIds.EXP_FOOD_DELIVERY to "外卖",
                AccountIds.EXP_FOOD_SNACKS to "零食饮料",
                AccountIds.EXP_FOOD_GROCERY to "水果生鲜",
                AccountIds.EXP_FOOD_DINING_OUT to "请客聚餐",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_TRANSPORT,
            parentName = "交通",
            icon = "directions_subway",
            color = "#1976D2",
            parentOrder = 200,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_TRANSPORT_TRANSIT to "地铁公交",
                AccountIds.EXP_TRANSPORT_TAXI to "打车",
                AccountIds.EXP_TRANSPORT_FUEL_PARKING to "加油停车",
                AccountIds.EXP_TRANSPORT_LONG_DISTANCE to "高铁机票",
                AccountIds.EXP_TRANSPORT_MAINTENANCE to "保养维修",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_SHOPPING,
            parentName = "购物",
            icon = "shopping_bag",
            color = "#D81B60",
            parentOrder = 300,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_SHOPPING_DAILY to "日用百货",
                AccountIds.EXP_SHOPPING_CLOTHING to "服饰鞋包",
                AccountIds.EXP_SHOPPING_DIGITAL to "数码家电",
                AccountIds.EXP_SHOPPING_BEAUTY to "美妆护肤",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_HOUSING,
            parentName = "居住",
            icon = "home",
            color = "#00897B",
            parentOrder = 400,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_HOUSING_RENT_MORTGAGE to "房租房贷",
                AccountIds.EXP_HOUSING_UTILITIES to "水电燃气",
                AccountIds.EXP_HOUSING_PROPERTY_NET to "物业宽带",
                AccountIds.EXP_HOUSING_REPAIR to "家居维修",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_ENTERTAINMENT,
            parentName = "娱乐",
            icon = "movie",
            color = "#8E24AA",
            parentOrder = 500,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_ENTERTAINMENT_MOVIE to "电影演出",
                AccountIds.EXP_ENTERTAINMENT_GAME to "游戏会员",
                AccountIds.EXP_ENTERTAINMENT_TRAVEL to "旅游度假",
                AccountIds.EXP_ENTERTAINMENT_FITNESS to "运动健身",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_MEDICAL,
            parentName = "医疗健康",
            icon = "medical_services",
            color = "#E53935",
            parentOrder = 600,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_MEDICAL_CLINIC to "门诊住院",
                AccountIds.EXP_MEDICAL_PHARMACY to "买药保健",
                AccountIds.EXP_MEDICAL_CHECKUP to "体检牙科",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_EDUCATION,
            parentName = "学习办公",
            icon = "menu_book",
            color = "#3949AB",
            parentOrder = 700,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_EDUCATION_BOOKS to "书籍课程",
                AccountIds.EXP_EDUCATION_OFFICE to "办公用品",
                AccountIds.EXP_EDUCATION_SUBSCRIPTION to "软件订阅",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_SOCIAL,
            parentName = "人情社交",
            icon = "card_giftcard",
            color = "#F4511E",
            parentOrder = 800,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_SOCIAL_RED_PACKET to "红包礼金",
                AccountIds.EXP_SOCIAL_GIFT to "送礼请客",
                AccountIds.EXP_SOCIAL_FAMILY to "孝敬长辈",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_FINANCE,
            parentName = "金融财务",
            icon = "account_balance",
            color = "#546E7A",
            parentOrder = 900,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_FINANCE_INTEREST_FEE to "利息手续费",
                AccountIds.EXP_FINANCE_BAD_DEBT to "坏账损失",
                AccountIds.EXP_FINANCE_INVEST_LOSS to "投资亏损",
                AccountIds.EXP_FINANCE_INSURANCE to "保险费",
            ),
        )
        addCategoryGroup(
            type = AccountType.EXPENSE,
            parentId = AccountIds.EXP_OTHER,
            parentName = "其他支出",
            icon = "more_horiz",
            color = "#757575",
            parentOrder = 1000,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.EXP_OTHER_MISC to "杂项支出",
            ),
        )
    }

    fun defaultIncomeCategories(nowEpochMs: Long = 0L): List<Account> = buildList {
        addCategoryGroup(
            type = AccountType.INCOME,
            parentId = AccountIds.INC_WORK,
            parentName = "职业收入",
            icon = "work",
            color = "#2E7D32",
            parentOrder = 1100,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.INC_WORK_SALARY to "工资薪金",
                AccountIds.INC_WORK_BONUS to "奖金补贴",
                AccountIds.INC_WORK_PART_TIME to "兼职外快",
            ),
        )
        addCategoryGroup(
            type = AccountType.INCOME,
            parentId = AccountIds.INC_INVEST,
            parentName = "投资理财",
            icon = "trending_up",
            color = "#00897B",
            parentOrder = 1200,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.INC_INVEST_DIVIDEND to "投资分红",
                AccountIds.INC_INVEST_INTEREST to "利息收入",
                AccountIds.INC_INVEST_CAPITAL_GAIN to "投资收益",
            ),
        )
        addCategoryGroup(
            type = AccountType.INCOME,
            parentId = AccountIds.INC_OTHER,
            parentName = "其他收入",
            icon = "savings",
            color = "#43A047",
            parentOrder = 1300,
            nowEpochMs = nowEpochMs,
            children = listOf(
                AccountIds.INC_OTHER_RED_PACKET to "红包礼金",
                AccountIds.INC_OTHER_REBATE to "优惠返现",
                AccountIds.INC_OTHER_SECOND_HAND to "二手闲置",
                AccountIds.INC_OTHER_WINDFALL to "意外所得",
            ),
        )
    }

    fun allSeedAccounts(nowEpochMs: Long = 0L): List<Account> =
        systemEquityAccounts(nowEpochMs) +
            defaultOperationalAccounts(nowEpochMs) +
            defaultExpenseCategories(nowEpochMs) +
            defaultIncomeCategories(nowEpochMs)

    fun defaultTags(nowEpochMs: Long = 0L): List<Tag> = listOf(
        Tag(
            id = TagIds.WORKDAY,
            name = "工作日",
            color = "#5C6BC0",
            icon = "work",
            sortOrder = 10,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.TRAVEL,
            name = "旅游",
            color = "#26A69A",
            icon = "flight",
            sortOrder = 20,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.BUSINESS_TRIP,
            name = "出差",
            color = "#42A5F5",
            icon = "business_center",
            sortOrder = 30,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.FIXED_EXPENSE,
            name = "固定开支",
            color = "#78909C",
            icon = "event_repeat",
            sortOrder = 40,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.REIMBURSABLE,
            name = "待报销",
            color = "#FFA726",
            icon = "receipt_long",
            sortOrder = 50,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.FAMILY,
            name = "家庭",
            color = "#EC407A",
            icon = "home",
            sortOrder = 60,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
        Tag(
            id = TagIds.IMPULSE,
            name = "冲动消费",
            color = "#EF5350",
            icon = "bolt",
            sortOrder = 70,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
        ),
    )

    private fun MutableList<Account>.addCategoryGroup(
        type: AccountType,
        parentId: String,
        parentName: String,
        icon: String,
        color: String,
        parentOrder: Int,
        nowEpochMs: Long,
        children: List<Pair<String, String>>,
    ) {
        add(
            Account(
                id = parentId,
                name = parentName,
                type = type,
                subType = AccountSubType.CATEGORY,
                parentId = null,
                currency = Currency.CNY,
                icon = icon,
                color = color,
                includeInNetWorth = false,
                isSystem = false,
                sortOrder = parentOrder,
                createdAt = nowEpochMs,
                updatedAt = nowEpochMs,
            ),
        )
        children.forEachIndexed { idx, (childId, childName) ->
            add(
                Account(
                    id = childId,
                    name = childName,
                    type = type,
                    subType = AccountSubType.CATEGORY,
                    parentId = parentId,
                    currency = Currency.CNY,
                    icon = icon,
                    color = color,
                    includeInNetWorth = false,
                    isSystem = false,
                    sortOrder = parentOrder + idx + 1,
                    createdAt = nowEpochMs,
                    updatedAt = nowEpochMs,
                ),
            )
        }
    }
}
