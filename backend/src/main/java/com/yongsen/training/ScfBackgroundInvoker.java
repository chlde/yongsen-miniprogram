package com.yongsen.training;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.scf.v20180416.ScfClient;
import com.tencentcloudapi.scf.v20180416.models.InvokeRequest;
import com.tencentcloudapi.scf.v20180416.models.InvokeResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Official SCF Invoke API; transport ambiguity is safe because execution is fenced by job ID. */
@Component
public class ScfBackgroundInvoker implements BackgroundInvoker {
    private final Environment environment;
    private final CloudCredentials credentials;
    private final Transport transport;

    @FunctionalInterface
    interface Transport {
        InvokeResponse invoke(ScfClient client, InvokeRequest request)
                throws TencentCloudSDKException;
    }

    @Autowired
    public ScfBackgroundInvoker(Environment environment, CloudCredentials credentials) {
        this(environment, credentials, ScfClient::Invoke);
    }

    ScfBackgroundInvoker(
            Environment environment, CloudCredentials credentials, Transport transport) {
        this.environment = environment;
        this.transport = transport;
        this.credentials = credentials;
        if ("scf".equals(mode())) {
            for (String key :
                    new String[] {
                        "TRAINING_SCF_REGION",
                        "TRAINING_SCF_WORKER_FUNCTION",
                        "TRAINING_TENCENT_SECRET_ID",
                        "TRAINING_TENCENT_SECRET_KEY"
                    }) {
                if (environment.getProperty(key, "").isBlank()) {
                    throw new IllegalStateException("Required SCF configuration missing: " + key);
                }
            }
        }
    }

    private String mode() {
        return environment.getProperty(
                "training.background.mode",
                environment.getProperty("TRAINING_BACKGROUND_MODE", "local"));
    }

    @Override
    public void invoke(String jobId) {
        if (!"scf".equals(mode())) {
            return;
        }
        CloudCredentials.Value current = credentials.current();
        Credential credential =
                new Credential(current.secretId(), current.secretKey(), current.sessionToken());
        HttpProfile http = new HttpProfile();
        http.setEndpoint("scf.tencentcloudapi.com");
        http.setConnTimeout(5);
        http.setReadTimeout(10);
        http.setWriteTimeout(10);
        ClientProfile profile = new ClientProfile();
        profile.setHttpProfile(http);
        ScfClient client =
                new ScfClient(credential, environment.getProperty("TRAINING_SCF_REGION"), profile);
        InvokeRequest request = new InvokeRequest();
        request.setFunctionName(environment.getProperty("TRAINING_SCF_WORKER_FUNCTION"));
        request.setNamespace(environment.getProperty("TRAINING_SCF_WORKER_NAMESPACE", "default"));
        request.setInvocationType("Event");
        request.setClientContext(Data.json(Data.map("jobId", jobId)));
        try {
            InvokeResponse response = transport.invoke(client, request);
            if (response == null
                    || response.getRequestId() == null
                    || response.getRequestId().isBlank()) {
                throw new BusinessException(
                        "SCF_DISPATCH_UNKNOWN", "SCF dispatch was not confirmed", 503);
            }
        } catch (TencentCloudSDKException exception) {
            // Provider messages can include request context; expose only a stable internal code.
            throw new BusinessException(
                    "SCF_DISPATCH_FAILED", "SCF dispatch failed; work remains recoverable", 503);
        }
    }
}
