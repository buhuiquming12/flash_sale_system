package com.fss.biz.product.service;

import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.model.SkuVO;
import com.fss.domain.entity.Product;
import com.fss.domain.entity.Sku;

import java.util.List;

public interface ProductService {

    long createProduct(ProductCreateCmd cmd, long adminId);

    long createSku(SkuCreateCmd cmd, long adminId);

    List<SkuVO> listSkus(long productId);

    /**
     * 供 activity / order 包读取商品快照。
     *
     * <p>跨包只允许通过 Service 接口，禁止直接使用别人的 Mapper——
     * 否则包边界形同虚设，将来拆模块时会发现到处都是隐式依赖。
     */
    Sku getSku(long skuId);

    Product getProduct(long productId);

    /** 下单快照用：一次取出 SKU 与其所属商品 */
    SkuSnapshot getSnapshot(long skuId);

    record SkuSnapshot(Sku sku, Product product) {
    }
}
