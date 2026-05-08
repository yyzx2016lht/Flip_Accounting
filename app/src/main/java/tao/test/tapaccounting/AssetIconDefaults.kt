package tao.test.tapaccounting

object AssetIconDefaults {
    const val OTHER_ICON_URL: String = "http://res3.qianjiapp.com/assetv2/asset_icon_other2.png"

    fun withDefault(iconUrl: String?): String {
        val trimmed = iconUrl?.trim().orEmpty()
        return if (trimmed.isNotEmpty()) trimmed else OTHER_ICON_URL
    }
}

