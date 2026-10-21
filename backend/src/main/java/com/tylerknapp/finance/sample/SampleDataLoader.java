package com.tylerknapp.finance.sample;

import com.tylerknapp.finance.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** On startup with SAMPLE_DATA=true, loads the sample data (once, and only while no real accounts exist). */
@Component
public class SampleDataLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);

    private final SampleDataService service;
    private final AppProperties props;

    public SampleDataLoader(SampleDataService service, AppProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.sampleData().enabled()) {
            return;
        }
        Path dir = Path.of(props.sampleData().dir());
        if (!Files.isDirectory(dir)) {
            log.warn("SAMPLE_DATA is on but {} doesn't exist; skipping", dir.toAbsolutePath());
            return;
        }
        try {
            service.load(dir);
        } catch (Exception e) {
            log.error("Loading sample data failed: {}", e.getMessage());
        }
    }
}
