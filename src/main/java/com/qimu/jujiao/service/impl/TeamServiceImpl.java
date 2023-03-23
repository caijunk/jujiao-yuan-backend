package com.qimu.jujiao.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.qimu.jujiao.common.ErrorCode;
import com.qimu.jujiao.exception.BusinessException;
import com.qimu.jujiao.mapper.TeamMapper;
import com.qimu.jujiao.model.entity.Team;
import com.qimu.jujiao.model.entity.User;
import com.qimu.jujiao.model.request.TeamCreateRequest;
import com.qimu.jujiao.model.request.TeamJoinRequest;
import com.qimu.jujiao.model.request.TeamQuery;
import com.qimu.jujiao.model.vo.TeamUserVo;
import com.qimu.jujiao.model.vo.TeamVo;
import com.qimu.jujiao.service.TeamService;
import com.qimu.jujiao.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.qimu.jujiao.utils.StringUtils.stringJsonListToLongSet;

/**
 * @author qimu
 * @description 针对表【team(队伍)】的数据库操作Service实现
 * @createDate 2023-03-08 23:14:16
 */
@Service
@Slf4j
public class TeamServiceImpl extends ServiceImpl<TeamMapper, Team> implements TeamService {

    private static final String SALT = "qimu_team";

    @Resource
    private UserService userService;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String BY_TEAM_IDS = String.format("jujiaoyuan:team:getTeamListByTeamIds:%s", "byTeamIds");
    private static final String TEAMS_KEY = String.format("jujiaoyuan:team:getTeams:%s", "getTeams");

    @Override
    public TeamUserVo getTeamListByTeamIds(Set<Long> teamId, HttpServletRequest request) {
        userService.isLogin(request);
        if (CollectionUtils.isEmpty(teamId)) {
            throw new BusinessException(ErrorCode.NULL_ERROR, "信息有误");
        }
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        TeamUserVo teamsList = (TeamUserVo) valueOperations.get(BY_TEAM_IDS);
        if (teamsList != null) {
            return teamsList;
        }
        // 获取所有队伍
        List<Team> teams = this.list();
        // 过滤后的队伍列表
        List<Team> teamList = teams.stream().filter(team -> {
            for (Long tid : teamId) {
                // 保留当前没有过期的队伍和搜索的队伍
                if (!new Date().after(team.getExpireTime()) && tid.equals(team.getId())) {
                    return true;
                }
            }
            return false;
        }).collect(Collectors.toList());
        TeamUserVo teamUserVo = teamSet(teamList);
        setRedis(BY_TEAM_IDS, teamUserVo);
        return teamUserVo;
    }

    @Override
    public TeamUserVo teamQuery(TeamQuery teamQuery, HttpServletRequest request) {
        userService.isLogin(request);
        String searchText = teamQuery.getSearchText();
        String teamQueryKey = String.format("jujiaoyuan:team:teamQuery:%s", searchText);
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        TeamUserVo teamList = (TeamUserVo) valueOperations.get(teamQueryKey);
        if (teamList != null) {
            return teamList;
        }
        LambdaQueryWrapper<Team> teamLambdaQueryWrapper = new LambdaQueryWrapper<>();
        teamLambdaQueryWrapper.like(Team::getTeamDesc, searchText.trim())
                .or().like(Team::getTeamName, searchText.trim());
        List<Team> teams = this.list(teamLambdaQueryWrapper);
        // 过滤后的队伍列表
        TeamUserVo teamUserVo = teamSet(teams);
        setRedis(teamQueryKey, teamUserVo);
        return teamUserVo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User joinTeam(TeamJoinRequest joinTeam, User loginUser) {
        if (joinTeam == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "加入队伍有误");
        }
        Team team = this.getById(joinTeam.getTeamId());
        Date expireTime = team.getExpireTime();

        // 当前队伍有没有过期
        if (expireTime != null && expireTime.before(new Date())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前队伍已过期");
        }
        // 当前队伍有没有私密
        if (team.getTeamStatus() == 3) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前队伍私有,不可加入");
        }
        // 队伍密码加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + joinTeam.getPassword()).getBytes(StandardCharsets.UTF_8));
        // 当前队伍是加密队伍
        // 不是管理员需要密码
        if (!userService.isAdmin(loginUser) && team.getTeamStatus() == 2) {
            if (StringUtils.isBlank(joinTeam.getPassword()) || !encryptPassword.equals(team.getTeamPassword())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
            }
        }
        Gson gson = new Gson();
        // 当前队伍加入的队员id
        String usersId = team.getUsersId();
        Set<Long> userIdList = stringJsonListToLongSet(usersId);
        // 当前队伍是不是已经满人了
        // 可以补位两个人
        if (userIdList.size() >= team.getMaxNum() + 2) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前队伍人数已满");
        }
        // 当前用户已经加入的队伍
        User user = userService.getById(loginUser);
        String teamIds = user.getTeamIds();
        Set<Long> loginUserTeamIdList = stringJsonListToLongSet(teamIds);

        // 最多加入5个队伍
        if (!userService.isAdmin(user) && loginUserTeamIdList.size() >= 5) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "最多加入5个队伍");
        }
        // 是否已经加入该队伍
        if (userIdList.contains(loginUser.getId()) || loginUserTeamIdList.contains(joinTeam.getTeamId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "已经加入过当前队伍");
        }
        userIdList.add(loginUser.getId());
        String newUserid = gson.toJson(userIdList);
        team.setUsersId(newUserid);

        loginUserTeamIdList.add(joinTeam.getTeamId());
        String loginTeamsId = gson.toJson(loginUserTeamIdList);
        user.setTeamIds(loginTeamsId);

        boolean joinTeamStatus = this.updateById(team);
        boolean loginJoinTeamStatus = userService.updateById(user);

        if (!(joinTeamStatus && loginJoinTeamStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "加入失败");
        }
        redisTemplate.delete(TEAMS_KEY);
        redisTemplate.delete(BY_TEAM_IDS);
        String teamIdKey = String.format("jujiaoyuan:team:getUsersByTeamId:%s", team.getId());
        redisTemplate.delete(teamIdKey);
        return userService.getSafetyUser(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createTeam(TeamCreateRequest teamCreateRequest, User loginUser) {

        if (StringUtils.isAnyBlank(teamCreateRequest.getTeamDesc(), teamCreateRequest.getTeamName(), teamCreateRequest.getAnnounce())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "输入有误");
        }
        if (teamCreateRequest.getTeamName().length() > 16) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍名称超过16个字符");
        }
        if (teamCreateRequest.getTeamDesc().length() > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍描述超过50个字符");
        }
        if (teamCreateRequest.getAnnounce().length() > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍公告超过50个字符");
        }
        // 过期时间在当前日期之前
        if (new Date().after(teamCreateRequest.getExpireTime())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "过期时间不能在当前时间之前");
        }
        if (teamCreateRequest.getMaxNum() == null || teamCreateRequest.getMaxNum() > 10) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍最多只能容纳10人");
        }
        if (teamCreateRequest.getMaxNum() < 5) {
            teamCreateRequest.setMaxNum(5);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍最少要有5人");
        }
        int status = Optional.ofNullable(teamCreateRequest.getTeamStatus()).orElse(0);
        Team team = new Team();
        // 只有队伍状态为加密才需要设置密码
        if (status == 2) {
            if (StringUtils.isBlank(teamCreateRequest.getTeamPassword())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "加密状态,必须设置密码");
            }
            if (teamCreateRequest.getTeamPassword().length() < 6) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "队伍密码长度低于6位");
            }
            if (teamCreateRequest.getTeamPassword().length() > 16) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码最长只能设置16位");
            }
            // 加密
            String encryptPassword = DigestUtils.md5DigestAsHex((SALT + teamCreateRequest.getTeamPassword()).getBytes(StandardCharsets.UTF_8));
            team.setTeamPassword(encryptPassword);
        }
        long id = loginUser.getId();
        User user = userService.getById(id);
        String teamIds = user.getTeamIds();
        Gson gson = new Gson();
        Set<Long> teamIdList = stringJsonListToLongSet(teamIds);
        if (teamIdList.size() >= 5) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "最多只能拥有5个队伍");
        }
        team.setTeamName(teamCreateRequest.getTeamName());
        team.setTeamDesc(teamCreateRequest.getTeamDesc());
        team.setMaxNum(teamCreateRequest.getMaxNum());
        team.setExpireTime(teamCreateRequest.getExpireTime());
        team.setUserId(loginUser.getId());
        team.setUsersId("[]");
        team.setTeamStatus(status);
        team.setCreateTime(new Date());
        team.setUpdateTime(new Date());
        team.setAnnounce(teamCreateRequest.getAnnounce());
        team.setTeamAvatarUrl(teamCreateRequest.getTeamAvatarUrl());
        boolean createTeam = this.save(team);
        if (!createTeam) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "创建队伍失败");
        }

        Team newTeam = this.getById(team);
        String usersId = newTeam.getUsersId();
        Set<Long> usersIdList = stringJsonListToLongSet(usersId);
        usersIdList.add(loginUser.getId());

        // 新队伍队员列表
        String users = gson.toJson(usersIdList);
        newTeam.setUsersId(users);

        teamIdList.add(newTeam.getId());
        // 用户新队伍json数组
        String teams = gson.toJson(teamIdList);
        user.setTeamIds(teams);

        boolean updateUser = userService.updateById(user);
        boolean updateTeam = this.updateById(newTeam);
        if (updateUser && updateTeam) {
            redisTemplate.delete(TEAMS_KEY);
            redisTemplate.delete(BY_TEAM_IDS);
        }
        return updateUser && updateTeam;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean dissolutionTeam(Long teamId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Team team = this.getById(teamId);
        if (!userService.isAdmin(loginUser) && loginUser.getId() != team.getUserId()) {
            throw new BusinessException(ErrorCode.NOT_LOGIN, "暂无权限");
        }
        List<User> users = userService.list();
        users.forEach(user -> {
            Set<Long> teamIds = stringJsonListToLongSet(user.getTeamIds());
            teamIds.remove(team.getId());
            user.setTeamIds(new Gson().toJson(teamIds));
            userService.updateById(user);
        });
        if (this.removeById(team)) {
            redisTemplate.delete(TEAMS_KEY);
            redisTemplate.delete(BY_TEAM_IDS);
            String teamIdKey = String.format("jujiaoyuan:team:getUsersByTeamId:%s", teamId);
            redisTemplate.delete(teamIdKey);
        }
        return this.removeById(team);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean quitTeam(Long teamId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Team team = this.getById(teamId);
        User user = userService.getById(loginUser);
        Set<Long> teamIds = stringJsonListToLongSet(user.getTeamIds());
        teamIds.remove(team.getId());
        Set<Long> userIds = stringJsonListToLongSet(team.getUsersId());
        userIds.remove(user.getId());
        Gson gson = new Gson();
        user.setTeamIds(gson.toJson(teamIds));
        team.setUsersId(gson.toJson(userIds));
        if (userService.updateById(user) && this.updateById(team)) {
            redisTemplate.delete(TEAMS_KEY);
            redisTemplate.delete(BY_TEAM_IDS);
            String teamIdKey = String.format("jujiaoyuan:team:getUsersByTeamId:%s", teamId);
            redisTemplate.delete(teamIdKey);
        }
        return userService.updateById(user) && this.updateById(team);
    }

    @Override
    public TeamUserVo getTeams() {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        TeamUserVo teamList = (TeamUserVo) valueOperations.get(TEAMS_KEY);
        if (teamList != null) {
            return teamList;
        }
        List<Team> teams = this.list();
        TeamUserVo teamUserVo = teamSet(teams);
        setRedis(TEAMS_KEY, teamUserVo);
        return teamUserVo;
    }

    @Override
    public TeamVo getUsersByTeamId(Long teamId, HttpServletRequest request) {
        // 当前用户是否登录
        User loginUser = userService.getLoginUser(request);
        User user = userService.getById(loginUser.getId());
        // 当前用户加入的队伍id
        String userTeamIds = user.getTeamIds();
        Team team = this.getById(teamId);

        String usersId = team.getUsersId();
        // 创建队伍者id
        Long userId = team.getUserId();

        Long tid = team.getId();
        String teamName = team.getTeamName();
        String teamAvatarUrl = team.getTeamAvatarUrl();
        String teamDesc = team.getTeamDesc();
        Integer maxNum = team.getMaxNum();
        Date expireTime = team.getExpireTime();
        Integer teamStatus = team.getTeamStatus();
        Date createTime = team.getCreateTime();
        String announce = team.getAnnounce();
        // 当前用户加入的队伍的id
        Set<Long> userTeamIdSet = stringJsonListToLongSet(userTeamIds);

        Set<Long> usersIdSet = stringJsonListToLongSet(usersId);

        // 当前用户不是管理员
        // 当前用户加入的队伍的ids中不包含传过来的队伍id
        // 当前用户的id不等于队伍的创建者id 说明没权限
        boolean noPermissions = !userService.isAdmin(loginUser) && !userTeamIdSet.contains(teamId) && loginUser.getId() != userId;
        if (noPermissions) {
            throw new BusinessException(ErrorCode.NO_AUTH, "暂无权限查看");
        }
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        String teamIdKey = String.format("jujiaoyuan:team:getUsersByTeamId:%s", teamId);
        TeamVo teams = (TeamVo) valueOperations.get(teamIdKey);
        if (teams != null) {
            return teams;
        }

        Set<User> users = new HashSet<>();
        for (Long id : usersIdSet) {
            users.add(userService.getById(id));
        }
        users = users.stream().map(userService::getSafetyUser).collect(Collectors.toSet());
        User createTeamUser = userService.getById(userId);
        User safetyUser = userService.getSafetyUser(createTeamUser);
        TeamVo teamVo = new TeamVo();
        teamVo.setId(tid);
        teamVo.setTeamName(teamName);
        teamVo.setTeamAvatarUrl(teamAvatarUrl);
        teamVo.setTeamDesc(teamDesc);
        teamVo.setMaxNum(maxNum);
        teamVo.setExpireTime(expireTime);
        teamVo.setTeamStatus(teamStatus);
        teamVo.setCreateTime(createTime);
        teamVo.setAnnounce(announce);
        teamVo.setUser(safetyUser);
        teamVo.setUserSet(users);
        setRedis(teamIdKey, teamVo);
        return teamVo;
    }

    /**
     * 设置 redis 3分钟
     *
     * @param redisKey
     * @param data
     */
    private void setRedis(String redisKey, Object data) {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        try {
            // 解决缓存雪崩
            int i = RandomUtil.randomInt(1, 5);
            valueOperations.set(redisKey, data, 1 + i, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("redis set key error");
        }
    }

    /**
     * 处理返回信息Vo
     *
     * @param teamList
     * @return teamUserVo
     */
    @Override
    public TeamUserVo teamSet(List<Team> teamList) {
        // 过滤过期的队伍
        List<Team> listTeam = teamList.stream()
                .filter(team -> !new Date().after(team.getExpireTime()))
                .collect(Collectors.toList());
        TeamUserVo teamUserVo = new TeamUserVo();
        Set<TeamVo> users = new HashSet<>();
        listTeam.forEach(team -> {
            TeamVo teamVo = new TeamVo();
            String usersId = team.getUsersId();
            teamVo.setId(team.getId());
            teamVo.setTeamName(team.getTeamName());
            teamVo.setTeamAvatarUrl(team.getTeamAvatarUrl());
            teamVo.setTeamDesc(team.getTeamDesc());
            teamVo.setMaxNum(team.getMaxNum());
            teamVo.setExpireTime(team.getExpireTime());
            teamVo.setTeamStatus(team.getTeamStatus());
            teamVo.setCreateTime(team.getCreateTime());
            teamVo.setAnnounce(team.getAnnounce());
            Set<Long> userSet = stringJsonListToLongSet(usersId);
            Set<User> userList = new HashSet<>();
            for (Long id : userSet) {
                userList.add(userService.getById(id));
            }
            User createUser = userService.getById(team.getUserId());
            User safetyUser = userService.getSafetyUser(createUser);
            teamVo.setUser(safetyUser);
            userList = userList.stream().map(userService::getSafetyUser).collect(Collectors.toSet());
            teamVo.setUserSet(userList);
            users.add(teamVo);
        });
        teamUserVo.setTeamSet(users);
        return teamUserVo;
    }
}




