package com.aries.test;

import com.aries.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.*;

public class GrpcStressTest {

    public static void main(String[] args) throws Exception {

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 9090)
                .usePlaintext()
                .build();

        RateLimiterServiceGrpc.RateLimiterServiceBlockingStub stub =
                RateLimiterServiceGrpc.newBlockingStub(channel);

        ExecutorService executor = Executors.newFixedThreadPool(50);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                RateLimitRequest request = RateLimitRequest.newBuilder()
                        .setClientId("user1")
                        .build();

                RateLimitResponse response = stub.checkLimit(request);

                System.out.println(response.getIsAllowed());
            });
        }

        executor.shutdown();
    }
}