package com.runelive.sidekick.web;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.runelive.sidekick.context.PlayerNotFoundException;
import com.runelive.sidekick.http.HttpException;
import com.runelive.sidekick.llm.LlmException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal embedded HTTP server (JDK {@code com.sun.net.httpserver}, no extra dependency) that serves
 * the single-page chat UI and the {@code /api/chat} endpoint. This is the only platform-specific
 * layer of the harness; in the RuneLite plugin it is replaced by a panel/overlay that calls
 * {@link ChatService} directly.
 */
@Slf4j
public class WebServer
{
	private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

	static
	{
		CONTENT_TYPES.put("html", "text/html; charset=utf-8");
		CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
		CONTENT_TYPES.put("css", "text/css; charset=utf-8");
		CONTENT_TYPES.put("svg", "image/svg+xml");
		CONTENT_TYPES.put("ico", "image/x-icon");
	}

	private final int port;
	private final Executor executor;
	private final Gson gson;
	private final ChatService chatService;
	private final String model;
	private final String defaultPlayer;

	private HttpServer server;

	public WebServer(int port, Executor executor, Gson gson, ChatService chatService, String model, String defaultPlayer)
	{
		this.port = port;
		this.executor = executor;
		this.gson = gson;
		this.chatService = chatService;
		this.model = model;
		this.defaultPlayer = defaultPlayer;
	}

	public void start() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress(port), 0);
		server.setExecutor(executor);
		server.createContext("/api/chat", new ChatHandler());
		server.createContext("/api/health", new HealthHandler());
		server.createContext("/", new StaticHandler());
		server.start();
		log.info("OSRS Sidekick web UI available at http://localhost:{}", boundPort());
	}

	public int boundPort()
	{
		return server != null ? server.getAddress().getPort() : port;
	}

	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
		}
	}

	private final class ChatHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			try
			{
				if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()))
				{
					sendJson(exchange, 405, ChatDtos.ChatResponse.error("Use POST."));
					return;
				}
				String body = readBody(exchange);
				ChatDtos.ChatRequest request;
				try
				{
					request = gson.fromJson(body, ChatDtos.ChatRequest.class);
				}
				catch (JsonSyntaxException e)
				{
					sendJson(exchange, 400, ChatDtos.ChatResponse.error("Invalid JSON request."));
					return;
				}

				ChatDtos.ChatResponse response = chatService.handle(request);
				int status = response.error == null ? 200 : 400;
				sendJson(exchange, status, response);
			}
			catch (PlayerNotFoundException e)
			{
				sendJson(exchange, 404, ChatDtos.ChatResponse.error(e.getMessage()
					+ " — make sure the name is spelled exactly and the account is tracked on the hiscores."));
			}
			catch (LlmException e)
			{
				log.warn("LLM error: {}", e.getMessage());
				sendJson(exchange, 502, ChatDtos.ChatResponse.error("The AI service had a problem: " + e.getMessage()));
			}
			catch (HttpException e)
			{
				log.warn("Upstream error: {}", e.getMessage());
				sendJson(exchange, 502, ChatDtos.ChatResponse.error("A data service had a problem: " + e.getMessage()));
			}
			catch (RuntimeException e)
			{
				log.error("Unexpected chat error", e);
				sendJson(exchange, 500, ChatDtos.ChatResponse.error("Something went wrong handling that message."));
			}
		}
	}

	private final class HealthHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			Map<String, Object> health = new LinkedHashMap<>();
			health.put("status", "ok");
			health.put("model", model);
			health.put("defaultPlayer", defaultPlayer);
			sendJson(exchange, 200, health);
		}
	}

	private final class StaticHandler implements HttpHandler
	{
		@Override
		public void handle(HttpExchange exchange) throws IOException
		{
			String path = exchange.getRequestURI().getPath();
			if (path == null || path.equals("/"))
			{
				path = "/index.html";
			}
			// Confine to the bundled web assets — reject traversal attempts.
			if (path.contains(".."))
			{
				sendText(exchange, 400, "Bad request");
				return;
			}
			String resource = "web" + path;
			try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource))
			{
				if (in == null)
				{
					sendText(exchange, 404, "Not found");
					return;
				}
				byte[] bytes = in.readAllBytes();
				exchange.getResponseHeaders().set("Content-Type", contentType(path));
				exchange.sendResponseHeaders(200, bytes.length);
				try (OutputStream os = exchange.getResponseBody())
				{
					os.write(bytes);
				}
			}
		}
	}

	private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException
	{
		byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}

	private static void sendText(HttpExchange exchange, int status, String text) throws IOException
	{
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream os = exchange.getResponseBody())
		{
			os.write(bytes);
		}
	}

	private static String readBody(HttpExchange exchange) throws IOException
	{
		try (InputStream in = exchange.getRequestBody())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String contentType(String path)
	{
		int dot = path.lastIndexOf('.');
		String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
		return CONTENT_TYPES.getOrDefault(ext, "text/plain; charset=utf-8");
	}
}
