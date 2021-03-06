package io.choerodon.iam.api.eventhandler;

import static io.choerodon.iam.infra.common.utils.SagaTopic.Application.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.asgard.saga.annotation.SagaTask;
import io.choerodon.core.exception.CommonException;
import io.choerodon.iam.infra.common.utils.AssertHelper;
import io.choerodon.iam.infra.dataobject.ApplicationDO;
import io.choerodon.iam.infra.dataobject.ApplicationExplorationDO;
import io.choerodon.iam.infra.enums.ApplicationCategory;
import io.choerodon.iam.infra.enums.ApplicationType;
import io.choerodon.iam.infra.mapper.ApplicationExplorationMapper;
import io.choerodon.iam.infra.mapper.ApplicationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用监听器
 *
 * @since 0.15.0
 */
@Component
public class ApplicationListener {

    private static final String SUCCESSFUL = "successful";
    private static final String FAILED = "failed";
    private static final String SEPARATOR = "/";

    private final Logger logger = LoggerFactory.getLogger(ApplicationListener.class);

    private ObjectMapper objectMapper = new ObjectMapper();

    private ApplicationMapper applicationMapper;
    private ApplicationExplorationMapper applicationExplorationMapper;
    private AssertHelper assertHelper;

    public ApplicationListener(ApplicationMapper applicationMapper,
                               AssertHelper assertHelper,
                               ApplicationExplorationMapper applicationExplorationMapper) {
        this.applicationMapper = applicationMapper;
        this.assertHelper = assertHelper;
        this.applicationExplorationMapper = applicationExplorationMapper;
    }


    /**
     * devops端创建app失败，发送消息，iam端执行回滚操作，在devops-service后执行
     *
     * @param message
     */
    @SagaTask(code = IAM_SYNC_APP, sagaCode = APP_SYNC, seq = 1, description = "devops发送application集合进行同步")
    public void syncApplications(String message) throws IOException {
        List<ApplicationDO> applications = objectMapper.readValue(message, new TypeReference<List<ApplicationDO>>() {
        });
        logger.info("begin to sync applications, total: {}", applications.size());
        if (applications.isEmpty()) {
            logger.warn("receiving no one application while syncing applications");
            return;
        }
        Map<String, Integer> statisticsMap = new HashMap<>(2);
        statisticsMap.put(SUCCESSFUL, 0);
        statisticsMap.put(FAILED, 0);
        applications.forEach(app -> {
            int successful = statisticsMap.get(SUCCESSFUL);
            int failed = statisticsMap.get(FAILED);
            if (isIllegal(app)) {
                statisticsMap.put(FAILED, ++failed);
                return;
            }
            try {
                applicationMapper.insertSelective(app);
                long appId = app.getId();
                ApplicationExplorationDO example = new ApplicationExplorationDO();
                example.setApplicationId(appId);
                String path = SEPARATOR + appId + SEPARATOR;
                example.setPath(path);
                example.setRootId(appId);
                example.setHashcode(String.valueOf(path.hashCode()));
                example.setEnabled(true);
                applicationExplorationMapper.insertSelective(example);
                statisticsMap.put(SUCCESSFUL, ++successful);
            } catch (Exception e) {
                statisticsMap.put(FAILED, ++failed);
                logger.error("insert application into db failed, application: {}, exception: {} ", app, e);
            }
        });
        logger.info("syncing applications has done, successful: {}, failed: {}", statisticsMap.get(SUCCESSFUL), statisticsMap.get(FAILED));
    }

    private boolean isIllegal(ApplicationDO app) {
        Long organizationId = app.getOrganizationId();
        if (ObjectUtils.isEmpty(organizationId)) {
            logger.error("illegal application because of organization id is empty, application: {}", app);
        } else {
            try {
                assertHelper.organizationNotExisted(organizationId);
            } catch (CommonException e) {
                logger.error("illegal application because of organization does not existed, application: {}", app);
                return true;
            }
        }

        Long projectId = app.getProjectId();
        if (ObjectUtils.isEmpty(projectId)) {
            logger.error("illegal application because of project id is empty, application: {}", app);
        } else {
            try {
                assertHelper.projectNotExisted(projectId);
            } catch (CommonException e) {
                logger.error("illegal application because of project does not existed, application: {}", app);
                return true;
            }
        }

        String name = app.getName();
        if (StringUtils.isEmpty(name)) {
            logger.error("illegal application because of name is empty, application: {}", app);
            return true;
        }

        String code = app.getCode();
        if (StringUtils.isEmpty(code)) {
            logger.error("illegal application because of code is empty, application: {}", app);
            return true;
        }

        if (!ApplicationType.matchCode(app.getApplicationType())) {
            logger.error("illegal application because of type is illegal, application: {}", app);
            return true;
        }
        ApplicationDO example = new ApplicationDO();
        example.setName(name);
        example.setOrganizationId(organizationId);
        example.setProjectId(projectId);
        if (!applicationMapper.select(example).isEmpty()) {
            logger.error("illegal application because of name is duplicated, application: {}", app);
            return true;
        }
        example.setName(null);
        example.setCode(code);
        if (!applicationMapper.select(example).isEmpty()) {
            logger.error("illegal application because of code is duplicated, application: {}", app);
            return true;
        }


        if (ObjectUtils.isEmpty(app.getEnabled())) {
            logger.warn("the enabled of application is null, so set default value true, application: {}", app);
            app.setEnabled(true);
        }
        app.setApplicationCategory(ApplicationCategory.APPLICATION.code());
        return false;
    }
}
