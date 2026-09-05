package com.gitnova.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitnova.common.UserContext;
import com.gitnova.dto.ApiResponse;
import com.gitnova.dto.NegotiationResponse;
import com.gitnova.dto.PushRequest;
import com.gitnova.entity.Repository;
import com.gitnova.service.ObjectNegotiationService;
import com.gitnova.service.RepositoryAccessService;
import com.gitnova.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        RepositoryAccessService repositoryAccessService = mock(
                RepositoryAccessService.class
        );
        TransferController controller = new TransferController(
                negotiationService,
                transferService,
                repositoryAccessService,
                new ObjectMapper()
        );
        Repository repository = new Repository();
        repository.setId(10L);
        repository.setOwnerId(100L);
        PushRequest request = new PushRequest();
        request.setLocalObjects(List.of());
        NegotiationResponse response = new NegotiationResponse(null, List.of());

        UserContext.setUserId(200L); // collaborator, not repository owner
        when(repositoryAccessService.requireWriteAccess(10L, 200L))
                .thenReturn(repository);
        when(negotiationService.negotiate(eq(10L), eq("100/10"), same(request)))
                .thenReturn(response);

        ApiResponse<NegotiationResponse> actual = controller.negotiate(10L, request);

        assertEquals(200, actual.getCode());
        assertEquals(response, actual.getData());
        verify(negotiationService).negotiate(eq(10L), eq("100/10"), same(request));
    }
}
