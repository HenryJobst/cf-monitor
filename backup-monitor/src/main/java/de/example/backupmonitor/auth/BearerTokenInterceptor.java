package de.example.backupmonitor.auth;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

public class BearerTokenInterceptor implements ClientHttpRequestInterceptor {

    private final String managerId;
    private final CfTokenServiceRegistry tokenRegistry;

    public BearerTokenInterceptor(String managerId, CfTokenServiceRegistry tokenRegistry) {
        this.managerId = managerId;
        this.tokenRegistry = tokenRegistry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request,
                                        byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().setBearerAuth(tokenRegistry.getToken(managerId));
        return execution.execute(request, body);
    }
}
