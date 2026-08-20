package com.fss.biz.product.service.impl;

import com.fss.biz.audit.AdminAuditService;
import com.fss.biz.product.model.ProductCreateCmd;
import com.fss.biz.product.model.SkuCreateCmd;
import com.fss.biz.product.model.SkuVO;
import com.fss.biz.product.service.ProductService;
import com.fss.common.error.Assert;
import com.fss.common.error.ErrorCode;
import com.fss.common.util.JsonUtil;
import com.fss.domain.entity.Product;
import com.fss.domain.entity.Sku;
import com.fss.domain.mapper.ProductMapper;
import com.fss.domain.mapper.SkuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper     productMapper;
    private final SkuMapper         skuMapper;
    private final AdminAuditService audit;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createProduct(ProductCreateCmd cmd, long adminId) {
        Product p = Product.builder()
                .title(cmd.getTitle())
                .subTitle(cmd.getSubTitle())
                .detail(cmd.getDetail())
                .mainImage(cmd.getMainImage())
                .status(cmd.getStatus() == null ? 1 : cmd.getStatus())
                .build();
        productMapper.insert(p);
        audit.record(adminId, "PRODUCT_CREATE", "PRODUCT", p.getId(), null, JsonUtil.toJson(cmd));
        return p.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createSku(SkuCreateCmd cmd, long adminId) {
        Product p = productMapper.selectById(cmd.getProductId());
        Assert.requireFound(p, ErrorCode.PRODUCT_NOT_FOUND);

        Sku sku = Sku.builder()
                .productId(cmd.getProductId())
                .spec(cmd.getSpec())
                .specJson(cmd.getSpecJson())
                .price(cmd.getPrice())
                .stock(cmd.getStock())
                .status(cmd.getStatus() == null ? 1 : cmd.getStatus())
                .build();
        skuMapper.insert(sku);
        audit.record(adminId, "SKU_CREATE", "SKU", sku.getId(), null, JsonUtil.toJson(cmd));
        return sku.getId();
    }

    @Override
    public List<SkuVO> listSkus(long productId) {
        Product p = productMapper.selectById(productId);
        Assert.requireFound(p, ErrorCode.PRODUCT_NOT_FOUND);
        return skuMapper.selectByProduct(productId).stream()
                .map(s -> new SkuVO(s.getId(), p.getId(), p.getTitle(), s.getSpec(),
                        s.getPrice(), s.getStock(), s.getStatus(), p.getMainImage()))
                .toList();
    }

    @Override
    public Sku getSku(long skuId) {
        return skuMapper.selectById(skuId);
    }

    @Override
    public Product getProduct(long productId) {
        return productMapper.selectById(productId);
    }

    @Override
    public SkuSnapshot getSnapshot(long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        Assert.requireFound(sku, ErrorCode.SKU_NOT_FOUND);
        Product p = productMapper.selectById(sku.getProductId());
        Assert.requireFound(p, ErrorCode.PRODUCT_NOT_FOUND);
        return new SkuSnapshot(sku, p);
    }
}
