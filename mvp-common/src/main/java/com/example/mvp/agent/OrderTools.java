package com.example.mvp.agent;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 一个极简的业务工具：查订单状态。
 *
 * <p><b>站 0 的边界判定</b>：框架<b>直接给</b>了整套工具机制 ——
 * {@code @Tool} / {@code @ToolParam} 注解、参数 Schema 自动生成、
 * {@code Toolkit.registerTool(Object)} 反射注册、{@code ToolResultBlock} 包装、
 * 甚至「模型返回 tool_call → 框架执行 → 结果回灌 → 模型再推理」这条 ReAct 闭环。
 *
 * <p>我们只写了<b>业务逻辑本身</b>（查一张假表）。
 *
 * <p>对照 customer_work：那边有 7 组业务工具 + MCP + 工具后端 SPI + 权限门 + 工具护栏，
 * 但那些里「工具怎么被调用」的部分全是框架给的。
 */
public class OrderTools {

    /**
     * 查询订单状态。
     *
     * @param orderId 订单号
     * @return 订单状态描述
     */
    @Tool(
            name = "query_order_status",
            description = "根据订单号查询订单的当前状态（待付款/已付款/已发货/已完成/已退款）")
    public String queryOrderStatus(
            @ToolParam(name = "orderId", description = "订单号，形如 SO202611250001") String orderId) {
        // 假实现：真接业务时换成 Repository / Feign 调用
        if (orderId == null || orderId.isBlank()) {
            return "订单号为空，无法查询";
        }
        if (orderId.endsWith("1")) {
            return "订单 " + orderId + " 当前状态：已发货，预计明天送达";
        }
        if (orderId.endsWith("2")) {
            return "订单 " + orderId + " 当前状态：已退款，退款金额将在 3 个工作日内原路返回";
        }
        return "订单 " + orderId + " 当前状态：已付款，等待仓库发货";
    }
}
