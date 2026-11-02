package com.funnywork;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admin-web;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class AdminWebIntegrationTest {
    @Autowired MockMvc http;
    @Test void publicShellDoesNotExposeAdministrativeData() throws Exception {
        http.perform(get("/admin/index.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("connect-src 'self'")));
        String shell=http.perform(get("/admin/index.html")).andReturn().getResponse().getContentAsString();
        var assets=java.util.regex.Pattern.compile("(?:src|href)=\"(/admin/assets/[^\"]+[.](?:js|css))\"").matcher(shell);
        int count=0;
        while(assets.find()){http.perform(get(assets.group(1))).andExpect(status().isOk());count++;}
        org.junit.jupiter.api.Assertions.assertTrue(count>=2);
        for(String path:new String[]{"/api/v1/me","/api/v1/nodes","/api/v1/tenant/scheduling-metrics","/api/v1/computer-controls","/api/v1/tool-templates"})
            http.perform(get(path)).andExpect(status().isUnauthorized());
        http.perform(get("/admin/unpublished-file")).andExpect(status().isNotFound());
    }
}
