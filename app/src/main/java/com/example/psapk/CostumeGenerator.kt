package com.example.psapk

/**
 * 服装生成器接口（可插拔）。
 *
 * 当前默认实现 [KeywordCostumeGenerator] 走离线“关键词 → 内置道具模板”路线：
 * 输入文字描述，匹配到合适的服装道具并叠加到人物照片上。
 *
 * 若要升级为“真正按文字生成任意服装”，只需实现本接口（例如调用云端生图 API
 * 或端侧扩散模型），替换 CostumeActivity 中使用的 generator 即可，界面无需改动。
 */
interface CostumeGenerator {
    fun generate(description: String): Costume
}

class KeywordCostumeGenerator : CostumeGenerator {
    override fun generate(description: String): Costume = CostumeLibrary.match(description)
}
