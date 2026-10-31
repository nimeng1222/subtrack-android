package com.subtrack.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 检索结果解析：搜索引擎会改版，但"能拿到真实目标地址 + 标题 + 摘要"这条底线不能变。
 * 这里用真实页面结构的最小样本锁住解析，不依赖网络。
 */
class WebSearchTest {

    @Test fun duckduckgo_redirect_is_decoded_to_the_real_url() {
        val href = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.kimi.com%2Fhelp%2Fmembership%" +
            "2Fmembership-pricing&rut=029079"
        assertEquals(
            "https://www.kimi.com/help/membership/membership-pricing",
            WebSearch.decodeDuckDuckGoTarget(href),
        )
    }

    @Test fun duckduckgo_lite_results_are_paired_with_snippets() {
        val html = """
            <html><body>
            <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.kimi.com%2Fmembership%2Fpricing&amp;rut=1" class='result-link'>Kimi 价格 | 会员套餐与订阅方案</a>
            <td class='result-snippet'>Kimi 会员 Go、Plus、Pro、Max、Ultra 套餐价格。</td>
            <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.kimi.com%2Fhelp%2Fmembership%2Fmembership-pricing&amp;rut=2" class='result-link'>Kimi 会员套餐价格与权益对比 - Kimi 帮助中心</a>
            <td class='result-snippet'>Go ¥49/月，Plus ¥99/月，Pro ¥199/月。</td>
            </body></html>
        """.trimIndent()
        val results = WebSearch.parseDuckDuckGo(html)
        assertEquals(2, results.size)
        assertEquals("https://www.kimi.com/membership/pricing", results[0].url)
        assertEquals("Kimi 价格 | 会员套餐与订阅方案", results[0].title)
        assertTrue(results[0].snippet.contains("Go、Plus"))
        assertEquals("https://www.kimi.com/help/membership/membership-pricing", results[1].url)
        assertTrue(results[1].snippet.contains("¥49"))
    }

    @Test fun bing_wrapped_links_are_decoded() {
        val target = "https://www.kimi.com/help/membership/membership-pricing"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val href = "https://www.bing.com/ck/a?!&&p=abc&ptn=3&u=a1$encoded&ntb=1"
        assertEquals(target, WebSearch.decodeBingTarget(href))
    }

    @Test fun bing_result_blocks_yield_title_url_and_snippet() {
        val target = "https://www.kimi.com/help/membership/membership-pricing"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val html = """
            <html><body><ol>
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&&u=a1$encoded">Kimi 会员套餐价格</a></h2>
              <div><p>当前国内新会员套餐为 Go、Plus、Pro、Max、Ultra。</p></div></li>
            <li class="b_algo"><h2><a href="https://www.kimi.com/membership/pricing">Kimi 价格页</a></h2>
              <p>直接链接</p></li>
            </ol></body></html>
        """.trimIndent()
        val results = WebSearch.parseBing(html)
        assertEquals(2, results.size)
        assertEquals(target, results[0].url)
        assertEquals("Kimi 会员套餐价格", results[0].title)
        assertTrue(results[0].snippet.contains("Go、Plus"))
        assertEquals("https://www.kimi.com/membership/pricing", results[1].url)
    }

    @Test fun only_https_pages_are_returned() {
        assertEquals(null, WebSearch.decodeDuckDuckGoTarget("http://www.kimi.com/membership/pricing"))
        assertEquals(
            null,
            WebSearch.decodeDuckDuckGoTarget(
                "//duckduckgo.com/l/?uddg=http%3A%2F%2Fwww.kimi.com%2Fpricing",
            ),
        )
        assertEquals(null, WebSearch.decodeBingTarget("https://www.kimi.com/logo.png"))
        assertEquals(null, WebSearch.decodeBingTarget("https://www.bing.com/ck/a?u=not-base64"))
    }

    @Test fun malformed_pages_degrade_to_no_results_instead_of_crashing() {
        assertTrue(WebSearch.parseDuckDuckGo("<html><body>no results").isEmpty())
        assertTrue(WebSearch.parseBing("<html><li class='b_algo'>broken").isEmpty())
    }
}
