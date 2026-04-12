package tao.test.flipaccounting

/**
 * 分类模型
 * @param name 一级分类名称 (如：餐饮)
 * @param subCategories 二级分类列表 (如：早餐, 午餐, 晚餐)
 */
data class Category(
    val name: String, // 一级分类，如 "三餐"
    val subCategories: List<String> // 二级分类，如 ["早餐", "午餐", "晚餐"]
)
