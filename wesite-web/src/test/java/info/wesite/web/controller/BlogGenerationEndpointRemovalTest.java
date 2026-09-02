package info.wesite.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BlogGenerationEndpointRemovalTest {

    @Test
    void removedGenerationRoutesCannotTriggerPosts() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BlogController()).build();

        mvc.perform(post("/blog/internal/generate")).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/admin/ai/blog/generate")).andExpect(status().isNotFound());
    }

    @Test
    void productionSourcesContainNoManualGenerationController() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java");
        String blogController = Files.readString(
            sourceRoot.resolve(Path.of("info", "wesite", "web", "controller", "BlogController.java")),
            StandardCharsets.UTF_8);
        Path oldController = sourceRoot.resolve(Path.of(
            "info", "wesite", "web", "controller", "api", "AiTaskController.java"));

        assertFalse(blogController.contains("internal/generate"));
        assertFalse(blogController.contains("manualGenerate"));
        assertFalse(Files.exists(oldController));
    }
}
