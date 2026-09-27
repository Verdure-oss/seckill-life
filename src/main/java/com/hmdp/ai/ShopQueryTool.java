package com.hmdp.ai;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * AI Function Tool: Search for shops by name.
 */
@Component
public class ShopQueryTool {

    @Resource
    private IShopService shopService;

    /**
     * 根据名称查询店铺信息
     * @param name 店铺名称关键字
     * @return 匹配的店铺列表
     */
    @Tool(name = "searchShopsByName", value = "根据名称搜索店铺信息。参数name是店铺名称关键字，返回店铺ID、名称、地址、评分等信息。")
    public List<Shop> searchShopsByName(String name) {
        List<Shop> shops = shopService.query()
                .like(true, "name", name)
                .list();

        return shops;
    }
}