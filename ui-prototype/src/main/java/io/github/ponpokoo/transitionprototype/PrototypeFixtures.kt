package io.github.ponpokoo.transitionprototype

internal data class PrototypeAuthor(val id: String, val name: String, val account: String, val avatar: Int)
internal data class PrototypeMedia(val id: String, val resource: Int, val description: String, val aspectRatio: Float)
internal data class PrototypeToot(
    val id: String,
    val author: PrototypeAuthor,
    val body: String,
    val time: String,
    val media: List<PrototypeMedia>,
    val replies: Int,
    val boosts: Int,
    val favourites: Int,
)

internal val PrototypeOwnAuthor = PrototypeAuthor("nagisa", "なぎさ", "@nagisa@example.test", R.drawable.sample_square)
private val GalleryAuthor = PrototypeAuthor("haru", "はる", "@haru@example.test", R.drawable.sample_portrait)
internal val PrototypeToots = listOf(
    PrototypeToot(
        id = "single", author = PrototypeOwnAuthor, time = "5分",
        body = "今日は湖のほとりを散歩してきました。\n水面に映る山がきれいだったので、写真を一枚。",
        media = listOf(PrototypeMedia("lake", R.drawable.sample_landscape, "山と湖のサンプル画像", 1.5f)),
        replies = 3, boosts = 2, favourites = 12,
    ),
    PrototypeToot(
        id = "gallery", author = GalleryAuthor, time = "12分",
        body = "旅の写真をまとめました。\n山の景色、夕暮れの塔、海辺の島。どの場所もまた訪れたいです。",
        media = listOf(
            PrototypeMedia("mountain", R.drawable.sample_landscape, "山の景色のサンプル画像", 1.5f),
            PrototypeMedia("tower", R.drawable.sample_portrait, "夕暮れの塔のサンプル画像", 0.6f),
            PrototypeMedia("island", R.drawable.sample_square, "海辺の島のサンプル画像", 1f),
        ),
        replies = 1, boosts = 4, favourites = 8,
    ),
)
