package com.tylerknapp.finance.categorize;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.tylerknapp.finance.config.AppProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /** Claude is used only when ANTHROPIC_API_KEY is set; otherwise the pipeline skips straight to Plaid's categories. */
    @Bean
    public AiCategorizer aiCategorizer(AppProperties props,
                                       @Value("${app.ai.model}") String model,
                                       @Value("${app.ai.server-fallbacks}") boolean serverFallbacks) {
        if (!props.anthropicConfigured()) {
            log.info("ANTHROPIC_API_KEY not set; categorization uses rules, transfer detection and Plaid categories only");
            return new AiCategorizer() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public List<Answer> categorize(List<Input> batch, List<Example> examples, Taxonomy taxonomy) {
                    throw new IllegalStateException("AI categorization is not configured");
                }
            };
        }
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(props.anthropicApiKey()).build();
        log.info("Claude categorization enabled (model {})", model);
        return new ClaudeCategorizer(client, model, serverFallbacks);
    }
}
