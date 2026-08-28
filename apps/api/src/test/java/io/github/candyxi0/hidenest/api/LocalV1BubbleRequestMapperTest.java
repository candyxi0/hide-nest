package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1BubbleException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LocalV1BubbleRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exactResolveAndPurgeShapesMapWithoutChangingText() throws Exception {
        String query = "中文\t🙂\r\n第二行";
        var request = LocalV1BubbleRequestMapper.resolve(
                JSON.readTree(
                        "{\"spaceKey\":\"space\",\"roomKey\":\"room\",\"turnKey\":\"turn\",\"queryText\":\"中文\\t🙂\\r\\n第二行\"}"));
        assertEquals(query, request.queryText());
        var purge = LocalV1BubbleRequestMapper.purge(JSON.readTree("{\"spaceKey\":\"space\",\"roomKey\":\"room\"}"));
        assertEquals("room", purge.roomKey());
    }

    @Test
    void nullMissingWrongTypeAndUnknownPolicyFieldsAreRejected() throws Exception {
        for (String body : new String[] {
            "null",
            "{}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":null}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":1}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":\"q\",\"minScore\":0.7}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":\"q\",\"maxResults\":1}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":\"q\",\"bubbleEnabled\":true}",
            "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":\"q\",\"bootstrapCount\":5}"
        }) {
            LocalV1BubbleException exception = assertThrows(
                    LocalV1BubbleException.class, () -> LocalV1BubbleRequestMapper.resolve(JSON.readTree(body)));
            assertEquals(LocalV1BubbleException.Code.REQUEST_SCHEMA_INVALID, exception.code());
        }
    }

    @Test
    void purgeRejectsBroadOrAdditionalTransportShape() throws Exception {
        for (String body :
                new String[] {"{\"spaceKey\":\"s\"}", "{\"spaceKey\":\"s\",\"roomKey\":\"r\",\"pattern\":\"*\"}"}) {
            assertThrows(LocalV1BubbleException.class, () -> LocalV1BubbleRequestMapper.purge(JSON.readTree(body)));
        }
    }
}
