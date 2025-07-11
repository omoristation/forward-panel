package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.FlowDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 流量上报控制器
 * 处理节点上报的流量数据，更新用户和隧道的流量统计
 * 
 * 主要功能：
 * 1. 接收并处理节点上报的流量数据
 * 2. 更新转发、用户和隧道的流量统计
 * 3. 检查用户总流量限制，超限时暂停所有服务
 * 4. 检查隧道流量限制，超限时暂停对应服务
 * 5. 检查用户到期时间，到期时暂停所有服务
 * 6. 检查隧道权限到期时间，到期时暂停对应服务
 * 7. 检查用户状态，状态不为1时暂停所有服务
 * 8. 检查转发状态，状态不为1时暂停对应转发
 * 9. 检查用户隧道权限状态，状态不为1时暂停对应转发
 * 
 * 并发安全解决方案：
 * 1. 使用UpdateWrapper进行数据库层面的原子更新操作，避免读取-修改-写入的竞态条件
 * 2. 使用synchronized锁确保同一用户/隧道的流量更新串行执行
 * 3. 这样可以避免相同用户相同隧道不同转发同时上报时流量统计丢失的问题
 */
@RestController
@RequestMapping("/flow")
@CrossOrigin
public class FlowController extends BaseController {

    // 常量定义
    private static final String SUCCESS_RESPONSE = "ok";
    private static final String ERROR_RESPONSE = "err1";
    private static final String DEFAULT_USER_TUNNEL_ID = "0";
    private static final int FLOW_TYPE_UPLOAD_ONLY = 1;
    private static final int FLOW_TYPE_BIDIRECTIONAL = 2;
    private static final long BYTES_TO_GB = 1024L * 1024L * 1024L;

    // 用于同步相同用户和隧道的流量更新操作
    private static final ConcurrentHashMap<String, Object> USER_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> TUNNEL_LOCKS = new ConcurrentHashMap<>();


    @RequestMapping("/test")
    @LogAnnotation
    public String test() {
        return "test";
    }
    /**
     * 处理流量数据上报
     * @param flowDataList 流量数据列表
     * @param secret 节点密钥
     * @return 处理结果
     */
    @RequestMapping("/upload")
    @LogAnnotation
    public String uploadFlowData(@RequestBody List<FlowDto> flowDataList, String secret) {
        // 1. 验证节点权限
        if (!isValidNode(secret)) {
            return ERROR_RESPONSE;
        }

        // 2. 过滤有效流量数据
        List<FlowDto> validFlowData = filterValidFlowData(flowDataList);
        if (validFlowData.isEmpty()) {
            return SUCCESS_RESPONSE;
        }

        // 3. 解析服务名称获取ID信息
        String[] serviceIds = parseServiceName(validFlowData.get(0).getN());
        String forwardId = serviceIds[0];
        String userId = serviceIds[1];
        String userTunnelId = serviceIds[2];

        // 4. 计算总流量
        FlowStatistics flowStats = calculateTotalFlow(validFlowData);

        // 5. 一次性查询相关实体，避免后续重复查询
        Forward forward = forwardService.getById(forwardId);
        User user = userService.getById(userId);
        UserTunnel userTunnel = null;
        if (!Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            userTunnel = userTunnelService.getById(userTunnelId);
        }

        // 6. 获取流量计费类型
        int flowType = getFlowType(forward);

        // 7. 更新流量统计：转发存在就更新转发流量
        if (forward != null) {
            updateForwardFlow(forwardId, flowStats);
        }

        // 8. 用户存在就更新用户流量和进行用户相关检查
        if (user != null) {
            updateUserFlow(userId, flowStats, flowType);
            checkUserRelatedLimits(user, userTunnelId, forward);
        }

        // 9. 隧道权限存在就更新隧道权限流量和进行隧道相关检查
        if (userTunnel != null) {
            updateUserTunnelFlow(userTunnelId, flowStats, flowType, forwardId, userId);
            checkUserTunnelRelatedLimits(userTunnel, forwardId, userId, userTunnelId, forward);
        }

        // 10. 转发存在就进行转发状态检查
        if (forward != null) {
            checkForwardStatus(forward, userId, userTunnelId);
        }

        return SUCCESS_RESPONSE;
    }

    /**
     * 验证节点是否有效
     */
    private boolean isValidNode(String secret) {
        int nodeCount = nodeService.count(new QueryWrapper<Node>().eq("secret", secret));
        return nodeCount > 0;
    }

    /**
     * 过滤有效的流量数据
     */
    private List<FlowDto> filterValidFlowData(List<FlowDto> flowDataList) {
        return flowDataList.stream()
                .filter(flow -> flow.getU() != null && flow.getD() != null)
                .filter(flow -> flow.getU() > 0 && flow.getD() > 0)
                .collect(Collectors.toList());
    }

    /**
     * 解析服务名称获取ID信息
     */
    private String[] parseServiceName(String serviceName) {
        return serviceName.split("_");
    }

    /**
     * 计算总流量统计
     */
    private FlowStatistics calculateTotalFlow(List<FlowDto> validFlowData) {
        long totalUpload = 0L;
        long totalDownload = 0L;
        
        for (FlowDto flow : validFlowData) {
            totalUpload += flow.getU();
            totalDownload += flow.getD();
        }
        
        return new FlowStatistics(totalUpload, totalDownload);
    }

    /**
     * 获取流量计费类型 - 优化版本，使用传入的Forward实体
     */
    private int getFlowType(Forward forward) {
        int defaultFlowType = FLOW_TYPE_BIDIRECTIONAL;
        
        if (forward != null) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                return tunnel.getFlow();
            }
        }
        
        return defaultFlowType;
    }

    /**
     * 更新转发流量统计 - 使用原子操作避免并发问题
     */
    private void updateForwardFlow(String forwardId, FlowStatistics flowStats) {
        UpdateWrapper<Forward> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", forwardId);
        updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
        updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
        
        forwardService.update(null, updateWrapper);
    }

    /**
     * 更新用户流量统计 - 使用原子操作避免并发问题
     */
    private void updateUserFlow(String userId, FlowStatistics flowStats, int flowType) {
        // 对相同用户的流量更新进行同步，避免并发覆盖
        synchronized (getUserLock(userId)) {
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId);
            
            // 使用SQL的原子更新操作，避免读取-修改-写入的并发问题
            if (flowType == FLOW_TYPE_BIDIRECTIONAL) {
                // 双向计费：同时更新上传和下载流量
                updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
                updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
            } else {
                // 仅上传计费：只更新上传流量
                updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
            }
            
            userService.update(null, updateWrapper);
        }
    }

    /**
     * 更新用户隧道流量统计并检查限制 - 优化版本，避免重复查询
     */
    private void updateUserTunnelFlow(String userTunnelId, FlowStatistics flowStats, 
                                    int flowType, String forwardId, String userId) {
        if (Objects.equals(userTunnelId, DEFAULT_USER_TUNNEL_ID)) {
            return;
        }

        // 对相同用户隧道的流量更新进行同步，避免并发覆盖
        synchronized (getTunnelLock(userTunnelId)) {
            UpdateWrapper<UserTunnel> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userTunnelId);
            updateWrapper.setSql("in_flow = in_flow + " + flowStats.getDownload());
            updateWrapper.setSql("out_flow = out_flow + " + flowStats.getUpload());
            
            boolean updateSuccess = userTunnelService.update(null, updateWrapper);
            if (!updateSuccess) {
                return; // 更新失败，可能记录不存在
            }
        }

        // 重新获取最新的流量数据进行限制检查
        UserTunnel updatedUserTunnel = userTunnelService.getById(userTunnelId);
        if (updatedUserTunnel != null) {
            checkUserTunnelFlowLimit(updatedUserTunnel, flowType, forwardId, userId, userTunnelId);
        }
    }

    /**
     * 检查用户隧道流量限制 - 优化版本，使用传入的UserTunnel实体
     */
    private void checkUserTunnelFlowLimit(UserTunnel userTunnel, int flowType, 
                                        String forwardId, String userId, String userTunnelId) {
        long currentFlow = (flowType == FLOW_TYPE_UPLOAD_ONLY) ? 
                          userTunnel.getOutFlow() : 
                          userTunnel.getInFlow() + userTunnel.getOutFlow();

        long flowLimit = userTunnel.getFlow() * BYTES_TO_GB;
        
        if (flowLimit < currentFlow) {
            pauseServiceDueToTunnelLimit(userTunnel.getTunnelId(), forwardId, userId, userTunnelId);
        }
    }

    /**
     * 因隧道流量超限暂停服务
     */
    private void pauseServiceDueToTunnelLimit(Integer tunnelId, String forwardId, 
                                            String userId, String userTunnelId) {
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(forwardId, userId, userTunnelId);
                GostUtil.PauseService(node.getIp() + ":" + node.getPort(), serviceName, node.getSecret());
                
                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getIp() + ":" + outNode.getPort(), serviceName, outNode.getSecret());
                    }
                }
            }
        }

        // 更新转发状态为暂停
        Forward forward = forwardService.getById(forwardId);
        if (forward != null) {
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    /**
     * 因隧道权限到期暂停服务
     */
    private void pauseServiceDueToTunnelExpiration(Integer tunnelId, String forwardId, 
                                                  String userId, String userTunnelId) {
        Tunnel tunnel = tunnelService.getById(tunnelId);
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(forwardId, userId, userTunnelId);
                GostUtil.PauseService(node.getIp() + ":" + node.getPort(), serviceName, node.getSecret());
                
                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getIp() + ":" + outNode.getPort(), serviceName, outNode.getSecret());
                    }
                }
            }
        }

        // 更新转发状态为暂停
        Forward forward = forwardService.getById(forwardId);
        if (forward != null) {
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    /**
     * 检查用户相关的所有限制 - 用户存在时统一处理
     */
    private void checkUserRelatedLimits(User user, String userTunnelId, Forward forward) {
        // 检查用户总流量限制
        long userFlowLimit = user.getFlow() * BYTES_TO_GB;
        long userCurrentFlow = user.getInFlow() + user.getOutFlow();
        if (userFlowLimit < userCurrentFlow) {
            pauseAllUserServices(user.getId().toString(), userTunnelId);
            return; // 用户流量超限，直接返回，不需要再检查其他项
        }

        // 检查用户到期时间
        if (user.getExpTime() != null && user.getExpTime() <= System.currentTimeMillis()) {
            pauseAllUserServices(user.getId().toString(), userTunnelId);
            return; // 用户到期，直接返回
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            pauseAllUserServices(user.getId().toString(), userTunnelId);
        }
    }

    /**
     * 检查用户隧道权限相关的所有限制 - 隧道权限存在时统一处理
     */
    private void checkUserTunnelRelatedLimits(UserTunnel userTunnel, String forwardId, String userId, String userTunnelId, Forward forward) {
        // 检查隧道权限到期时间
        if (userTunnel.getExpTime() != null && userTunnel.getExpTime() <= System.currentTimeMillis()) {
            pauseServiceDueToTunnelExpiration(userTunnel.getTunnelId(), forwardId, userId, userTunnelId);
            return; // 隧道权限到期，直接返回
        }

        // 检查用户隧道权限状态
        if (userTunnel.getStatus() != 1) {
            if (forward != null) {
                pauseSpecificForward(forward, userId, userTunnelId);
            }
        }
    }

    /**
     * 检查转发状态 - 优化版本，使用传入的Forward实体
     */
    private void checkForwardStatus(Forward forward, String userId, String userTunnelId) {
        // 检查转发状态是否为正常（1）
        if (forward.getStatus() != 1) {
            pauseSpecificForward(forward, userId, userTunnelId);
        }
    }

    /**
     * 暂停指定的转发服务
     */
    private void pauseSpecificForward(Forward forward, String userId, String userTunnelId) {
        Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
        if (tunnel != null) {
            Node node = nodeService.getNodeById(tunnel.getInNodeId());
            if (node != null) {
                String serviceName = buildServiceName(String.valueOf(forward.getId()), userId, userTunnelId);
                GostUtil.PauseService(node.getIp() + ":" + node.getPort(), serviceName, node.getSecret());
                
                // 隧道转发需要同时暂停远端服务
                if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                    Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                    if (outNode != null) {
                        GostUtil.PauseRemoteService(outNode.getIp() + ":" + outNode.getPort(), serviceName, outNode.getSecret());
                    }
                }
            }
        }
        
        // 更新转发状态为暂停
        forward.setStatus(0);
        forwardService.updateById(forward);
    }

    /**
     * 暂停用户所有服务
     */
    private void pauseAllUserServices(String userId, String userTunnelId) {
        List<Forward> userForwards = forwardService.list(new QueryWrapper<Forward>().eq("user_id", userId));
        
        for (Forward forward : userForwards) {
            Tunnel tunnel = tunnelService.getById(forward.getTunnelId());
            if (tunnel != null) {
                Node node = nodeService.getNodeById(tunnel.getInNodeId());
                if (node != null) {
                    // 查找该转发对应的正确userTunnelId
                    String actualUserTunnelId = findActualUserTunnelId(userId, forward.getTunnelId().toString());
                    String serviceName = buildServiceName(String.valueOf(forward.getId()), userId, actualUserTunnelId);
                    GostUtil.PauseService(node.getIp() + ":" + node.getPort(), serviceName, node.getSecret());
                    
                    // 隧道转发需要同时暂停远端服务
                    if (tunnel.getType() == 2) { // TUNNEL_TYPE_TUNNEL_FORWARD
                        Node outNode = nodeService.getNodeById(tunnel.getOutNodeId());
                        if (outNode != null) {
                            GostUtil.PauseRemoteService(outNode.getIp() + ":" + outNode.getPort(), serviceName, outNode.getSecret());
                        }
                    }
                }
            }
            
            forward.setStatus(0);
            forwardService.updateById(forward);
        }
    }

    /**
     * 查找用户在指定隧道的实际userTunnelId
     */
    private String findActualUserTunnelId(String userId, String tunnelId) {
        UserTunnel userTunnel = userTunnelService.getOne(
            new QueryWrapper<UserTunnel>()
                .eq("user_id", userId)
                .eq("tunnel_id", tunnelId)
        );
        
        return userTunnel != null ? String.valueOf(userTunnel.getId()) : DEFAULT_USER_TUNNEL_ID;
    }

    /**
     * 构建服务名称
     */
    private String buildServiceName(String forwardId, String userId, String userTunnelId) {
        return forwardId + "_" + userId + "_" + userTunnelId;
    }

    /**
     * 获取用户锁对象
     */
    private Object getUserLock(String userId) {
        return USER_LOCKS.computeIfAbsent(userId, k -> new Object());
    }

    /**
     * 获取隧道锁对象
     */
    private Object getTunnelLock(String userTunnelId) {
        return TUNNEL_LOCKS.computeIfAbsent(userTunnelId, k -> new Object());
    }

    /**
     * 流量统计数据类
     */
    private static class FlowStatistics {
        private final long upload;
        private final long download;

        public FlowStatistics(long upload, long download) {
            this.upload = upload;
            this.download = download;
        }

        public long getUpload() {
            return upload;
        }

        public long getDownload() {
            return download;
        }
    }
}
