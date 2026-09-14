package com.example.imagetranslate.screenshot

/** Extracted from request 0c802de9-7c75-4806-aeba-075c775714b2. */
internal object ArchivedWhatsAppMachineFixture {
    const val VIEWPORT_WIDTH = 1440
    const val VIEWPORT_HEIGHT = 3200

    val rows = listOf(
        row("storage.googleapis.com.", 282, 212, 893, 269, "mlkit-0", 0),
        row("Alessio,Andrea,Antimo,Arianna,Bi..", 299, 376, 1099, 428, "mlkit-1", 0),
        row("Vero Tonti", 277, 561, 521, 611, "mlkit-2", 0),
        row("Alla prossima lezione@Mattia potresti", 279, 638, 1193, 690, "mlkit-3", 0),
        row("inventarti la condotta al passeggino", 281, 702, 1123, 753, "mlkit-3", 1),
        row("Ho un saccodo coppie al campo.", 259, 798, 1148, 859, "mlkit-5", 0),
        row("Dovranno fare la condotta matrimoniale", 259, 872, 1324, 919, "mlkit-5", 1),
        row("13:10", 1153, 1006, 1263, 1041, "mlkit-17", 0),
        row("Vero Tonti", 100, 1217, 342, 1264, "mlkit-4", 0),
        row("Cinzia Staffy", 126, 1323, 424, 1380, "mlkit-6", 0),
        row("Video(0:29)", 144, 1396, 461, 1453, "mlkit-6", 1),
        row("La bellezza", 113, 1513, 416, 1574, "mlkit-7", 0),
        row("13:10", 569, 1567, 680, 1600, "mlkit-10", 0),
        row("Salvatore Cirillo", 102, 1778, 473, 1819, "mlkit-8", 0),
        row("Maddalena Amadori", 128, 1884, 607, 1926, "mlkit-11", 0),
        row("Scusate per il mio pippone e per essere", 127, 1956, 1053, 2012, "mlkit-12", 0),
        row("intervenuta,ma mi piace confrontarmi con", 128, 2021, 1139, 2068, "mlkit-12", 1),
        row("persone che magari hanno alternative ch.", 128, 2078, 1132, 2139, "mlkit-12", 2),
        row("Buondi io ho avuto gli stessi problemi", 128, 2176, 1107, 2242, "mlkit-13", 0),
        row("con il mio Brando,ho fatto prove", 103, 2253, 973, 2312, "mlkit-13", 1),
        row("allergiche ambientali e non...Risultato", 103, 2322, 1139, 2382, "mlkit-13", 2),
        row("eallergico pure al proprietario,", 129, 2396, 917, 2459, "mlkit-13", 3),
        row("si distruggeva grattandosi in", 103, 2470, 864, 2529, "mlkit-13", 4),
        row("continuazione a sangue", 102, 2539, 739, 2600, "mlkit-13", 5),
        row("13:26", 1081, 2573, 1192, 2607, "mlkit-18", 0),
        row("Avevo il cane in condizioni pietose,", 101, 2670, 1028, 2731, "mlkit-16", 0),
        row("veterinaria e dermatologa avevano", 102, 2746, 1029, 2802, "mlkit-16", 1),
        row("consigliato il apoquel e cytopoint", 101, 2810, 983, 2880, "mlkit-16", 2),
        row("13:30", 1081, 2849, 1191, 2882, "mlkit-19", 0),
        row("Messaggio", 188, 2970, 523, 3042, "mlkit-14", 0)
    )

    private fun row(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockId: String,
        lineIndex: Int
    ) = MachineTextLine(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        blockId = blockId,
        lineIndex = lineIndex
    )
}
