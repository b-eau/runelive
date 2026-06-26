package com.runelive.sidekick.agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Small helpers for building JSON-schema objects for tool inputs. */
final class Schemas
{
	private Schemas()
	{
	}

	/** A single-required-string-property object schema with {@code additionalProperties: false}. */
	static JsonObject singleString(String property, String description)
	{
		JsonObject prop = new JsonObject();
		prop.addProperty("type", "string");
		prop.addProperty("description", description);

		JsonObject properties = new JsonObject();
		properties.add(property, prop);

		JsonArray required = new JsonArray();
		required.add(property);

		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		return schema;
	}

	static String optString(JsonObject input, String key)
	{
		if (input == null || !input.has(key) || input.get(key).isJsonNull())
		{
			return "";
		}
		return input.get(key).getAsString();
	}
}
