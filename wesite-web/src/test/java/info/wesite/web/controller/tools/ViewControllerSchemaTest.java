package info.wesite.web.controller.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import com.alibaba.fastjson2.JSONObject;

class ViewControllerSchemaTest {

    @Test
    void toolSchemaDoesNotClaimRatingsThatUsersCannotSubmitOrSee() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = new ViewController().domainAnalyzer(model);

        JSONObject schema = JSONObject.parseObject((String) model.get("_pageSchema"));
        assertEquals("tools/domain_analyzer", view);
        assertEquals("SoftwareApplication", schema.getString("@type"));
        assertFalse(schema.containsKey("aggregateRating"));
    }
}
