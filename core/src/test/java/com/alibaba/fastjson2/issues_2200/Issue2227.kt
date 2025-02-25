package com.alibaba.fastjson2.issues_2200

import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import org.junit.Test
import org.junit.jupiter.api.Assertions

class Issue2227 {
    @Test
    fun test() {
        val jsonObject = JSON.toJSON(OuterClass()) as JSONObject
        Assertions.assertTrue(jsonObject.get("nestedClass") is JSONObject)
    }

    data class OuterClass(val id: Int = 1, val nestedClass: NestedClass = NestedClass())

    data class NestedClass(val id: Int = 2)
}
