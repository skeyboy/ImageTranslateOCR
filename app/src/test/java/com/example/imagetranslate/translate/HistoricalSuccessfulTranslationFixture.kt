package com.example.imagetranslate.translate

internal enum class HistoricalTextLengthBucket(val range: IntRange) {
    SHORT(1..20),
    MEDIUM(21..80),
    LONG(81..200),
    EXTRA_LONG(201..Int.MAX_VALUE);

    companion object {
        fun classify(text: String): HistoricalTextLengthBucket {
            val length = text.replace(Regex("\\s+"), " ").length
            return entries.first { length in it.range }
        }
    }
}

internal data class HistoricalTranslationCase(
    val requestId: String,
    val bucket: HistoricalTextLengthBucket,
    val source: String,
    val aiTranslation: String,
    val sourceLanguage: String = "en",
    val targetLanguage: String = "zh"
)

/** Successful archive samples selected near each length bucket boundary and midpoint. */
internal object HistoricalSuccessfulTranslationFixture {
    val cases = listOf(
        HistoricalTranslationCase(
            "99e64b2a-213a-4a8b-b172-1cbeb35722ed",
            HistoricalTextLengthBucket.SHORT,
            "reply",
            "回复"
        ),
        HistoricalTranslationCase(
            "6c7fa5c4-cff7-45ef-8ceb-9f9185277ecf",
            HistoricalTextLengthBucket.SHORT,
            "Article Talk",
            "条目 讨论"
        ),
        HistoricalTranslationCase(
            "3e211d17-5e0e-428b-8933-b7487432f979",
            HistoricalTextLengthBucket.SHORT,
            "How to Use This Book",
            "如何阅读本书"
        ),
        HistoricalTranslationCase(
            "b3e2dd70-58b7-476c-8981-eafdad7b884e",
            HistoricalTextLengthBucket.MEDIUM,
            "symptom,not the cause.The",
            "症状，而非原因。"
        ),
        HistoricalTranslationCase(
            "99e64b2a-213a-4a8b-b172-1cbeb35722ed",
            HistoricalTextLengthBucket.MEDIUM,
            "nightpool 5 hours ago Iroot I parent I prev I next",
            "nightpool 5小时前 | 根节点 | 上级 | 上一个 | 下一个"
        ),
        HistoricalTranslationCase(
            "89ecfd04-6508-464c-8d8b-2942e06197bf",
            HistoricalTextLengthBucket.MEDIUM,
            "easy to replicate(no fancy equipment needed,just ask\nSome people to proofread)",
            "容易复现（不需要什么高端设备，只要找几个人帮忙校对即可）"
        ),
        HistoricalTranslationCase(
            "6c0e7fc9-dc1d-4fe6-8dd3-67beb4fd5191",
            HistoricalTextLengthBucket.LONG,
            "It's highly unlikely that fraud at that level is being\n" +
                "perpetuated now by these companies.",
            "这些公司现在极不可能在进行那种程度的欺诈。"
        ),
        HistoricalTranslationCase(
            "5b6d77d9-7f67-4ba5-9d63-cb9d2dbd00e4",
            HistoricalTextLengthBucket.LONG,
            "I'm also pretty sure I would fall in the camp of saying\n" +
                "nope don't understand values as I can't remember\n" +
                "anything else about them.",
            "我也很确定我会属于那一类，即说我不懂这些值，因为我不记得其他内容了。"
        ),
        HistoricalTranslationCase(
            "0c982114-9768-4ba2-a8bc-f207c05a063c",
            HistoricalTextLengthBucket.LONG,
            "strategy centered on Hohhot and Ulangab.\n" +
                "The two centers operate in synergy and complement each other,efficiently\n" +
                "handling high-intensity AI computing tasks for industrial and public sectors.",
            "以呼和浩特和乌兰察布为中心的战略。两个中心协同联动、优势互补，高效处理工业和公共领域的高强度人工智能计算任务。"
        ),
        HistoricalTranslationCase(
            "b34012fb-fcba-44a3-9893-1850567dc864",
            HistoricalTextLengthBucket.EXTRA_LONG,
            "But the volume is\nsymptom,not the cause.The\n" +
                "real problem is an academic reward system that leans\n" +
                "on publication records,the number of papers and the prestige of the venues " +
                "they appear in,as a proxy for a researcher's worth.",
            "但数量只是表象，而非根源。真正的问题在于学术评价机制，它依赖发表记录、论文数量以及发表平台的声誉，并以此衡量研究人员的价值。"
        ),
        HistoricalTranslationCase(
            "e63ded93-2e61-4392-8fc0-a9dbd4d6f406",
            HistoricalTextLengthBucket.EXTRA_LONG,
            "Preferentially treat one group of people(non white/women/Americans) even if " +
                "in the short term the revealed preference of businesses is to hire a " +
                "different group of people(white/men/immigrants), such that in the long term " +
                "the first group can develop the skills to compete with the second group.",
            "优先对待某一群体，即使企业短期内偏好雇用另一群体，从长远来看，第一批人也可以培养出与第二批人竞争所需的技能。"
        ),
        HistoricalTranslationCase(
            "8adf4fa5-8eac-4459-a0bc-3a7e832c6364",
            HistoricalTextLengthBucket.EXTRA_LONG,
            "One thing that happened is The Copenhagen Interpretation of Ethics. " +
                "Mark Zuckerberg was criticized for having defunded a school, although he " +
                "paid for it for a while and then stopped in a planned manner. Those who did " +
                "not ever pay were better off. Jordan Henderson spoke for gay rights and was " +
                "criticized when he moved to Saudi Arabia, while players who never spoke out " +
                "received no censure.",
            "发生的一件事是伦理的哥本哈根诠释。马克·扎克伯格因停止资助一所学校而受到批评，尽管他曾资助一段时间并按计划停止。乔丹·亨德森曾为同性恋权利发声，但转会沙特阿拉伯时受到批评。"
        )
    )
}
