package com.qimu.jujiao.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.qimu.jujiao.model.entity.Chat;
import com.qimu.jujiao.model.entity.User;
import com.qimu.jujiao.model.request.ChatRequest;
import com.qimu.jujiao.model.vo.MessageVo;

import java.util.List;

/**
 * @author qimu
 * @description 针对表【chat(聊天消息表)】的数据库操作Service
 * @createDate 2023-04-11 11:19:33
 */
public interface ChatService extends IService<Chat> {
    /**
     * 获取私聊聊天内容
     *
     * @param chatRequest
     * @param chatType
     * @param loginUser
     * @return
     */
    List<MessageVo> getPrivateChat(ChatRequest chatRequest, int chatType, User loginUser);

    /**
     * 获取大厅聊天纪录
     *
     * @param chatType
     * @param loginUser
     * @return
     */
    List<MessageVo> getHallChat(int chatType, User loginUser);

    /**
     * 聊天记录映射
     *
     * @param fromId
     * @param toId
     * @param text
     * @return
     */
    MessageVo chatResult(Long fromId, Long toId, String text);

    /**
     * 队伍聊天室
     *
     * @param chatRequest
     * @param chatType
     * @param loginUser
     * @return
     */
    List<MessageVo> getTeamChat(ChatRequest chatRequest, int chatType, User loginUser);

    /**
     * 消息处理
     *
     * @param userId
     * @param chatLambdaQueryWrapper
     * @return
     */
    List<MessageVo> returnMessage(Long userId, LambdaQueryWrapper<Chat> chatLambdaQueryWrapper);
}
