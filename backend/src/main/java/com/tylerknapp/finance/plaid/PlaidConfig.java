package com.tylerknapp.finance.plaid;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import com.tylerknapp.finance.config.AppProperties;
import com.tylerknapp.finance.config.NotConfiguredException;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlaidConfig {
    private static final Logger log = LoggerFactory.getLogger(PlaidConfig.class);

    @Bean
    public PlaidGateway plaidGateway(AppProperties props) {
        AppProperties.Plaid p = props.plaid();
        if (!p.configured()) {
            log.info("Plaid keys not set; Plaid features are disabled until PLAID_CLIENT_ID and PLAID_SECRET are provided");
            return (PlaidGateway) Proxy.newProxyInstance(PlaidGateway.class.getClassLoader(),
                    new Class<?>[] {PlaidGateway.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "UnconfiguredPlaidGateway";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw new UnsupportedOperationException(method.getName());
                            };
                        }
                        throw new NotConfiguredException("Plaid keys are not set (PLAID_CLIENT_ID, PLAID_SECRET)");
                    });
        }
        boolean sandbox = "sandbox".equals(p.env());
        ApiClient client = new ApiClient(Map.of("clientId", p.clientId(), "secret", p.secret()));
        client.setPlaidAdapter(sandbox ? ApiClient.Sandbox : ApiClient.Production);
        client.setTimeout(60);
        log.info("Plaid client ready for env={}", p.env());
        return new PlaidSdkGateway(client.createService(PlaidApi.class), sandbox);
    }
}
