package com.gitnova.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.dto.NegotiationResponse;
import com.gitnova.dto.PushRequest;
import com.gitnova.entity.RepoMember;
import com.gitnova.entity.Repository;
import com.gitnova.mapper.RepoMemberMapper;
import com.gitnova.mapper.RepositoryMapper;
import com.gitnova.service.ObjectNegotiationService;
import com.gitnova.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferControllerTest {

    @AfterEach
    void clearRequestContext() {
        UserContext.clear();
    }

    @Test
    void shouldUseRepositoryOwnerToConstructNegotiationRepoKey() {
        ObjectNegotiationService negotiationService = mock(ObjectNegotiationService.class);
        TransferService transferService = mock(TransferService.class);
        RepositoryMapper repositoryMapper = mock(RepositoryMapper.class);
        RepoMemberMapper repoMemberMapper = mock(RepoMemberMapper.class);
        TransferController controller = new TransferController(
                negotiationService,
                transferService,
                repositoryMapper,
                repoMemberMapper,
                new ObjectMapper()
        );
        Repository repository = new Repository();
        repository.setId(10L);
        repository.setOwnerId(100L);
        PushRequest request = new PushRequest();
        request.setLocalObjects(List.of());
        NegotiationResponse response = new NegotiationResponse(null, List.of());

        UserContext.setUserId(200L); // collaborator, not repository owner
        when(repositoryMapper.selectById(10L)).thenReturn(repository);
        when(repoMemberMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(new RepoMember());
        when(negotiationService.negotiate(eq(10L), eq("100/10"), same(request)))
                .thenReturn(response);

        ApiResponse<NegotiationResponse> actual = controller.negotiate(10L, request);

        assertEquals(200, actual.getCode());
        assertEquals(response, actual.getData());
        verify(negotiationService).negotiate(eq(10L), eq("100/10"), same(request));
    }
}
