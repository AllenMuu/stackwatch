package com.stackwatch.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.stackwatch.config.IncidentProperties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IncidentDisabledStartupTest {

    @Autowired
    IncidentProperties incidentProperties;

    @Autowired
    ObjectProvider<DataSource> dataSourceProvider;

    @Test
    void startsWithoutAnIncidentDatasourceByDefault() {
        assertThat(incidentProperties.enabled()).isFalse();
        assertThat(dataSourceProvider.getIfAvailable()).isNull();
    }
}
