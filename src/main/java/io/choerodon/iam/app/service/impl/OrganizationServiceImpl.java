package io.choerodon.iam.app.service.impl;

import static io.choerodon.iam.infra.common.utils.SagaTopic.Organization.ORG_DISABLE;
import static io.choerodon.iam.infra.common.utils.SagaTopic.Organization.ORG_ENABLE;
import static io.choerodon.iam.infra.common.utils.SagaTopic.Organization.ORG_UPDATE;

import java.util.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.iam.api.dto.*;
import io.choerodon.iam.api.dto.payload.OrganizationPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import io.choerodon.asgard.saga.annotation.Saga;
import io.choerodon.asgard.saga.dto.StartInstanceDTO;
import io.choerodon.asgard.saga.feign.SagaClient;
import io.choerodon.core.convertor.ConvertHelper;
import io.choerodon.core.convertor.ConvertPageHelper;
import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.core.oauth.DetailsHelper;
import io.choerodon.iam.api.dto.payload.OrganizationEventPayload;
import io.choerodon.iam.app.service.OrganizationService;
import io.choerodon.iam.domain.iam.entity.UserE;
import io.choerodon.iam.domain.repository.OrganizationRepository;
import io.choerodon.iam.domain.repository.ProjectRepository;
import io.choerodon.iam.domain.repository.RoleRepository;
import io.choerodon.iam.domain.repository.UserRepository;
import io.choerodon.iam.domain.service.IUserService;
import io.choerodon.iam.infra.dataobject.OrganizationDO;
import io.choerodon.iam.infra.dataobject.ProjectDO;
import io.choerodon.iam.infra.dataobject.RoleDO;
import io.choerodon.iam.infra.feign.AsgardFeignClient;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * @author wuguokai
 */
@Component
public class OrganizationServiceImpl implements OrganizationService {

    private OrganizationRepository organizationRepository;
    private ProjectRepository projectRepository;
    private RoleRepository roleRepository;
    private UserRepository userRepository;
    private AsgardFeignClient asgardFeignClient;

    @Value("${choerodon.devops.message:false}")
    private boolean devopsMessage;

    @Value("${spring.application.name:default}")
    private String serviceName;

    private SagaClient sagaClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private IUserService iUserService;

    private static final String ORG_MSG_NOT_EXIST = "error.organization.not.exist";

    public OrganizationServiceImpl(OrganizationRepository organizationRepository,
                                   SagaClient sagaClient,
                                   ProjectRepository projectRepository,
                                   RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   IUserService iUserService,
                                   AsgardFeignClient asgardFeignClient) {
        this.organizationRepository = organizationRepository;
        this.sagaClient = sagaClient;
        this.projectRepository = projectRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.iUserService = iUserService;
        this.asgardFeignClient = asgardFeignClient;
    }

    @Override
    public OrganizationDTO queryOrganizationById(Long organizationId) {
        OrganizationDO organizationDO = organizationRepository.selectByPrimaryKey(organizationId);
        if (organizationDO == null) {
            throw new CommonException(ORG_MSG_NOT_EXIST, organizationId);
        }
        List<ProjectDO> projects = projectRepository.selectByOrgId(organizationId);
        organizationDO.setProjects(projects);
        organizationDO.setProjectCount(projects.size());
        Long userId = organizationDO.getUserId();
        UserE user = userRepository.selectByPrimaryKey(userId);
        OrganizationDTO dto = ConvertHelper.convert(organizationDO, OrganizationDTO.class);
        dto.setOwnerLoginName(user.getLoginName());
        dto.setOwnerRealName(user.getRealName());
        dto.setOwnerPhone(user.getPhone());
        dto.setOwnerEmail(user.getEmail());
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Saga(code = ORG_UPDATE, description = "iam更新组织", inputSchemaClass = OrganizationPayload.class)
    public OrganizationDTO updateOrganization(Long organizationId, OrganizationDTO organizationDTO, String resourceLevel, Long sourceId) {
        preUpdate(organizationId, organizationDTO);

        OrganizationDO organizationDO = ConvertHelper.convert(organizationDTO, OrganizationDO.class);
        organizationDO = organizationRepository.update(organizationDO);
        if (devopsMessage) {
            OrganizationPayload payload = new OrganizationPayload();
            payload
                    .setId(organizationDO.getId())
                    .setName(organizationDO.getName())
                    .setCode(organizationDO.getCode())
                    .setUserId(organizationDO.getUserId())
                    .setAddress(organizationDO.getAddress())
                    .setImageUrl(organizationDO.getImageUrl());
            try {
                String input = mapper.writeValueAsString(payload);
                sagaClient.startSaga(ORG_UPDATE, new StartInstanceDTO(input, "organization", organizationId + "", resourceLevel, sourceId));
            } catch (JsonProcessingException e) {
                throw new CommonException("error.organization.update.payload.to.string");
            } catch (Exception e) {
                throw new CommonException("error.organization.update.event", e);
            }
        }
        return ConvertHelper.convert(organizationDO, OrganizationDTO.class);
    }

    private void preUpdate(Long organizationId, OrganizationDTO organizationDTO) {
        OrganizationDO organization = organizationRepository.selectByPrimaryKey(organizationId);
        if (ObjectUtils.isEmpty(organization)) {
            throw new CommonException("error.organization.notFound");
        }
        organizationDTO.setId(organizationId);
        //code和创建人不可修改
        organizationDTO.setUserId(organization.getUserId());
        organizationDTO.setCode(organization.getCode());
        if (ObjectUtils.isEmpty(organizationDTO.getEnabled())) {
            organizationDTO.setEnabled(true);
        }
    }

    @Override
    public OrganizationDTO queryOrganizationWithRoleById(Long organizationId) {
        CustomUserDetails customUserDetails = DetailsHelper.getUserDetails();
        if (customUserDetails == null) {
            throw new CommonException("error.user.not.login");
        }
        OrganizationDTO dto = queryOrganizationById(organizationId);
        long userId = customUserDetails.getUserId();

        List<ProjectDO> projects = projectRepository.selectUserProjectsUnderOrg(userId, organizationId, null);
        dto.setProjects(ConvertHelper.convertList(projects, ProjectDTO.class));
        dto.setProjectCount(projects.size());

        List<RoleDO> roles =
                roleRepository.selectUsersRolesBySourceIdAndType(ResourceLevel.ORGANIZATION.value(), organizationId, userId);
        dto.setRoles(ConvertHelper.convertList(roles, RoleDTO.class));
        return dto;
    }

    @Override
    public Page<OrganizationDTO> pagingQuery(OrganizationDTO organizationDTO, PageRequest pageRequest, String param) {
        Page<OrganizationDO> organizationDOPage =
                organizationRepository.pagingQuery(ConvertHelper.convert(
                        organizationDTO, OrganizationDO.class), pageRequest, param);
        return ConvertPageHelper.convertPage(organizationDOPage, OrganizationDTO.class);
    }

    @Override
    @Saga(code = ORG_ENABLE, description = "iam启用组织", inputSchemaClass = OrganizationEventPayload.class)
    public OrganizationDTO enableOrganization(Long organizationId, Long userId) {
        OrganizationDO organization = organizationRepository.selectByPrimaryKey(organizationId);
        if (organization == null) {
            throw new CommonException(ORG_MSG_NOT_EXIST);
        }
        organization.setEnabled(true);
        OrganizationDO organizationDO = updateAndSendEvent(organization, ORG_ENABLE, userId);
        return ConvertHelper.convert(organizationDO, OrganizationDTO.class);
    }

    @Override
    @Saga(code = ORG_DISABLE, description = "iam停用组织", inputSchemaClass = OrganizationEventPayload.class)
    public OrganizationDTO disableOrganization(Long organizationId, Long userId) {
        OrganizationDO organizationDO = organizationRepository.selectByPrimaryKey(organizationId);
        if (organizationDO == null) {
            throw new CommonException(ORG_MSG_NOT_EXIST);
        }
        organizationDO.setEnabled(false);
        return ConvertHelper.convert(updateAndSendEvent(organizationDO, ORG_DISABLE, userId), OrganizationDTO.class);
    }

    private OrganizationDO updateAndSendEvent(OrganizationDO organization, String consumerType, Long userId) {
        OrganizationDO organizationDO = organizationRepository.update(organization);
        if (devopsMessage) {
            OrganizationEventPayload payload = new OrganizationEventPayload();
            payload.setOrganizationId(organization.getId());
            //saga
            try {
                String input = mapper.writeValueAsString(payload);
                sagaClient.startSaga(consumerType, new StartInstanceDTO(input, "organization", payload.getOrganizationId() + ""));
            } catch (Exception e) {
                throw new CommonException("error.organizationService.enableOrDisable.event", e);
            }
            //给asgard发送禁用定时任务通知
            asgardFeignClient.disableOrg(organization.getId());
            // 给组织下所有用户发送通知
            List<Long> userIds = organizationRepository.listMemberIds(organization.getId());
            Map<String, Object> params = new HashMap<>();
            params.put("organizationName", organizationRepository.selectByPrimaryKey(organization.getId()).getName());
            if (ORG_DISABLE.equals(consumerType)) {
                iUserService.sendNotice(userId, userIds, "disableOrganization", params, organization.getId());
            } else if (ORG_ENABLE.equals(consumerType)) {
                iUserService.sendNotice(userId, userIds, "enableOrganization", params, organization.getId());
            }
        }
        return organizationRepository.selectByPrimaryKey(organizationDO.getId());
    }

    @Override
    public void check(OrganizationDTO organization) {
        Boolean checkCode = !StringUtils.isEmpty(organization.getCode());
        if (!checkCode) {
            throw new CommonException("error.organization.code.empty");
        } else {
            checkCode(organization);
        }
    }

    @Override
    public Page<UserDTO> pagingQueryUsersInOrganization(Long organizationId, Long userId, String email, PageRequest pageRequest, String param) {
        return ConvertPageHelper.convertPage(
                userRepository.pagingQueryUsersByOrganizationId(organizationId, userId, email, pageRequest, param), UserDTO.class);
    }

    @Override
    public List<OrganizationDTO> queryByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        } else {
            return ConvertHelper.convertList(organizationRepository.queryByIds(ids), OrganizationDTO.class);
        }
    }

    private void checkCode(OrganizationDTO organization) {
        Boolean createCheck = StringUtils.isEmpty(organization.getId());
        String code = organization.getCode();
        OrganizationDO organizationDO = new OrganizationDO();
        organizationDO.setCode(code);
        if (createCheck) {
            Boolean existed = organizationRepository.selectOne(organizationDO) != null;
            if (existed) {
                throw new CommonException("error.organization.code.exist");
            }
        } else {
            Long id = organization.getId();
            OrganizationDO organizationDO1 = organizationRepository.selectOne(organizationDO);
            Boolean existed = organizationDO1 != null && !id.equals(organizationDO1.getId());
            if (existed) {
                throw new CommonException("error.organization.code.exist");
            }
        }
    }

    @Override
    public List<OrganizationSimplifyDTO> getAllOrgs(PageRequest pageRequest) {
        return organizationRepository.selectAllOrgIdAndName(pageRequest);
    }
}
