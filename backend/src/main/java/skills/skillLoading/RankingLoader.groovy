package skills.skillLoading

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import skills.storage.repos.UserAchievedLevelRepo
import skills.storage.repos.UserPointsRepo
import skills.storage.model.UserAchievement
import skills.storage.model.UserPoints
import skills.skillLoading.model.UsersPerLevel
import skills.skillLoading.model.SkillsRanking
import skills.skillLoading.model.SkillsRankingDistribution

@Component
@Slf4j
class RankingLoader {

    @Autowired
    UserPointsRepo userPointsRepository

    @Autowired
    UserAchievedLevelRepo achievedLevelRepository

    @Autowired
    skills.services.LevelDefinitionStorageService levelDefinitionStorageService

    SkillsRanking getUserSkillsRanking(String projectId, String userId, String subjectId = null){
        UserPoints usersPoints = userPointsRepository.findByProjectIdAndUserIdAndSkillIdAndDay(projectId, userId, subjectId, null)
        return doGetUserSkillsRanking(projectId, usersPoints, subjectId)
    }

    private  SkillsRanking doGetUserSkillsRanking(String projectId, UserPoints usersPoints, String subjectId = null) {
        int numUsers = userPointsRepository.countByProjectIdAndSkillIdAndDay(projectId, subjectId, null)
        // always calculate total number of users
        SkillsRanking ranking
        if (usersPoints) {
            int numUsersWithLessScore = subjectId ? userPointsRepository.calculateNumUsersWithLessScore(projectId, subjectId, usersPoints.points)
                    : userPointsRepository.calculateNumUsersWithLessScore(projectId, usersPoints.points)
            int position = numUsers - numUsersWithLessScore
            ranking = new SkillsRanking(numUsers: numUsers, position: position)
        } else {
            // last one
            ranking = new SkillsRanking(numUsers: numUsers+1, position: numUsers+1)
        }

        return ranking
    }

    SkillsRankingDistribution getRankingDistribution(String projectId, String userId, String subjectId = null) {
        UserPoints usersPoints = userPointsRepository.findByProjectIdAndUserIdAndSkillIdAndDay(projectId, userId, subjectId, null)
        SkillsRanking skillsRanking = doGetUserSkillsRanking(projectId, usersPoints, subjectId)

        List<UsersPerLevel> usersPerLevel = getUserCountsPerLevel(projectId, false, subjectId)

        List<UserAchievement> myLevels = achievedLevelRepository.findAllByUserIdAndProjectIdAndSkillId(userId, projectId, subjectId)
        int myLevel = myLevels ? myLevels.collect({it.level}).max() : 0

        Integer pointsToPassNextUser = -1
        Integer pointsAnotherUserToPassMe = -1

        if(usersPoints?.points){
            List<UserPoints> next = findHighestUserPoints(projectId, usersPoints, subjectId)
            pointsToPassNextUser = next ? next.first().points - usersPoints.points : -1

            List<UserPoints> previous = findLowestUserPoints(projectId, usersPoints, subjectId)
            pointsAnotherUserToPassMe = previous ? usersPoints.points - previous.first().points : -1
        }

        return new SkillsRankingDistribution(totalUsers: skillsRanking.numUsers, myPosition: skillsRanking.position,
                myLevel: myLevel, myPoints: usersPoints?.points ?: 0, usersPerLevel: usersPerLevel,
                pointsToPassNextUser: pointsToPassNextUser, pointsAnotherUserToPassMe: pointsAnotherUserToPassMe)
    }

    @CompileStatic
    private List<UserPoints> findLowestUserPoints(String projectId, UserPoints usersPoints, String subjectId) {
        List<UserPoints> previous = userPointsRepository.findByProjectIdAndSkillIdAndPointsLessThanAndDayIsNull(projectId, subjectId, usersPoints.points, new PageRequest(0, 1, Sort.Direction.DESC, "points"))
        previous
    }

    @CompileStatic
    private List<UserPoints> findHighestUserPoints(String projectId, UserPoints usersPoints, String subjectId) {
        List<UserPoints> next = userPointsRepository.findByProjectIdAndSkillIdAndPointsGreaterThanAndDayIsNull(projectId, subjectId, usersPoints.points, new PageRequest(0, 1, Sort.Direction.ASC, "points"))
        next
    }

    List<UsersPerLevel> getUserCountsPerLevel(String projectId, boolean includeZeroLevel = false, String subjectId = null) {
        List<skills.controller.result.model.LevelDefinitionRes> levels = levelDefinitionStorageService.getLevels(projectId, subjectId)
        List<UsersPerLevel> usersPerLevel = !levels ? [] : levels.sort({
            it.level
        }).collect { skills.controller.result.model.LevelDefinitionRes levelMeta ->
            Integer numUsers = achievedLevelRepository.countByProjectIdAndSkillIdAndLevel(projectId, subjectId, levelMeta.level)
            new UsersPerLevel(level: levelMeta.level, numUsers: numUsers ?: 0)
        }

        // when level completed by a user a UserAchievement record is stored,
        // a user that achieved level 1, 2 and 3 will have three UserAchievement records, therefore
        // the sql logic ends up double counting for lower levels; as a fix let's remove
        // number of users of higher levels from lower levels
        usersPerLevel = usersPerLevel.sort({ it.level })
        usersPerLevel.eachWithIndex { UsersPerLevel entry, int i ->
            if (i + 1 < usersPerLevel.size()) {
                entry.numUsers -= usersPerLevel[i + 1].numUsers
            }
        }

        if(includeZeroLevel){
            Integer numUsers = achievedLevelRepository.countByProjectIdAndSkillIdAndLevel(projectId, subjectId, 0)
            usersPerLevel.add(0, new UsersPerLevel(level: 0, numUsers: numUsers ?: 0))
        }

        return usersPerLevel
    }
}