package org.freeswitch.esl.client.inbound;

import com.google.gson.*;

import java.util.*;

public class JsonToMap {
    /**
     * 获取JsonObject
     *
     * @param json
     * @return
     */
    public static JsonObject parseJson(String json) {
        JsonParser parser  = new JsonParser();
        JsonObject jsonObj = parser.parse(json).getAsJsonObject();
        return jsonObj;
    }

    /**
     * 依据json字符串返回Map对象
     *
     * @param json
     * @return
     */
    public static HashMap<String, Object> toMap(String json) {
        return JsonToMap.toMap(JsonToMap.parseJson(json));
    }

    /**
     * 将JSONObjec对象转换成Map-List集合
     *
     * @param json
     * @return
     */
    public static HashMap<String, Object> toMap(JsonObject json) {
        HashMap<String, Object>             map      = new HashMap<>();
        Set<Map.Entry<String, JsonElement>> entrySet = json.entrySet();
        for (Map.Entry<String, JsonElement> entry : entrySet) {
            String key   = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof JsonArray)
                map.put(key, toList((JsonArray) value));
            else if (value instanceof JsonObject)
                map.put(key, toMap((JsonObject) value));
            else if (value instanceof JsonPrimitive) {
                JsonPrimitive obj = (JsonPrimitive) value;
                if (obj.isString()) {
                    map.put(key, obj.getAsString());
                } else {
                    map.put(key, value);
                }
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * 将JSONArray对象转换成List集合
     *
     * @param json
     * @return
     */
    public static List<Object> toList(JsonArray json) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < json.size(); i++) {
            Object value = json.get(i);
            if (value instanceof JsonArray) {
                list.add(toList((JsonArray) value));
            } else if (value instanceof JsonObject) {
                list.add(toMap((JsonObject) value));
            } else {
                list.add(value);
            }
        }
        return list;
    }
}
