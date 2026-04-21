package com.aries.service;

import com.aries.proto.RateLimitRequest;
import com.aries.proto.RateLimitResponse;
import com.aries.proto.RateLimiterServiceGrpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class GrpcRateLimiterService extends RateLimiterServiceGrpc.RateLimiterServiceImplBase {

    private final RateLimiterService rateLimiterService;

    public GrpcRateLimiterService(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public void checkLimit(RateLimitRequest request,
                           StreamObserver<RateLimitResponse> responseObserver) {

        String clientId = request.getClientId();

        boolean allowed = rateLimiterService.isAllowed(clientId);

        RateLimitResponse response = RateLimitResponse.newBuilder()
                .setIsAllowed(allowed)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}