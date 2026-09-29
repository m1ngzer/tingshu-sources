package com.github.eprendre.sources_by_m1ngzer

import com.github.eprendre.tingshu.sources.TingShu

object SourceEntry {

    /**
     * 说明
     */
    @JvmStatic
    fun getDesc(): String {
        return "m1ngzer 的听书源"
    }

    /**
     * 内容分类标识，同名的会聚合在一起展示
     */
    @JvmStatic
    fun getCategory(): String {
        return "听书"
    }

    /**
     * 返回此包下面的源，以后新增站点就在这里加一个类
     */
    @JvmStatic
    fun getSources(): List<TingShu> {
        return listOf(
            ShuYinFm
        )
    }
}
