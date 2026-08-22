package com.fss.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fss.domain.entity.ReconcileTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReconcileTaskMapper extends BaseMapper<ReconcileTask> {

    /**
     * 同一业务对象是否已有<b>未关闭</b>的对账任务。
     *
     * <p>对账任务必须去重，否则每 5 分钟发现同一个差异就插一条，一天 288 条，
     * 真正的新差异被埋在里面。去重口径是"未关闭"（待处理 0 / 需人工 2）——
     * 已经人工处理过(3)或忽略(4)的差异如果再次出现，那是一个<b>新</b>事件，
     * 应该重新记一条，而不是被当成旧记录跳过。
     *
     * <p><b>为什么不加数据库唯一键</b>：{@code (task_type, biz_no)} 上加 UNIQUE 之后，
     * "同一差异第二次出现"会变成 insert 失败，而调用方只能 catch 异常再判断——
     * 那不比查一次便宜，还会在日志里留下一串 DuplicateKeyException。
     * 更重要的是死信消费者<b>每次</b>进死信都该记一条（那是独立事件），
     * 唯一键会把它的第二次记录也拦掉。对账任务本身跑在分布式锁里，串行执行，
     * 查完再插没有并发窗口。
     */
    @Select("""
            SELECT * FROM t_reconcile_task
             WHERE task_type = #{taskType} AND biz_no = #{bizNo} AND status IN (0, 2)
             ORDER BY id DESC LIMIT 1
            """)
    ReconcileTask selectOpen(@Param("taskType") int taskType, @Param("bizNo") String bizNo);

    /** 差异仍在但明细变了（比如漂移量变大）：更新明细而不是插新记录 */
    @Update("""
            UPDATE t_reconcile_task
               SET detail = #{detail}, handle_result = #{handleResult}
             WHERE id = #{id}
            """)
    int updateDetail(@Param("id") long id,
                     @Param("detail") String detail,
                     @Param("handleResult") String handleResult);

    @Update("""
            UPDATE t_reconcile_task
               SET status = #{status}, handle_result = #{handleResult}, handler = #{handler}
             WHERE id = #{id}
            """)
    int close(@Param("id") long id,
              @Param("status") int status,
              @Param("handleResult") String handleResult,
              @Param("handler") String handler);

    @Select("""
            SELECT * FROM t_reconcile_task
             WHERE task_type = #{taskType}
             ORDER BY id DESC LIMIT #{limit}
            """)
    List<ReconcileTask> selectByType(@Param("taskType") int taskType,
                                     @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM t_reconcile_task WHERE task_type = #{taskType} AND status IN (0, 2)")
    long countOpen(@Param("taskType") int taskType);
}
