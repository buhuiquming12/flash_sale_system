package com.fss.biz.audit;

import com.fss.domain.entity.AdminLog;
import com.fss.domain.mapper.AdminLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理操作审计。
 *
 * <p>与业务操作<b>同事务</b>写入：如果审计独立事务，业务成功而审计失败就会出现
 * "库存被改了但查不到是谁改的"，这恰好是最需要审计的情况。
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private final AdminLogMapper mapper;

    public void record(long adminId, String action, String targetType, Object targetId,
                       String before, String after) {
        mapper.insert(AdminLog.of(adminId, action, targetType, targetId, before, after));
    }
}
