package com.runelive.sidekick.llm;

/** Raised when a model request fails (transport error or non-2xx response). */
public class LlmException extends RuntimeException
{
	private final int statusCode;

	public LlmException(int statusCode, String message)
	{
		super(message);
		this.statusCode = statusCode;
	}

	public LlmException(int statusCode, String message, Throwable cause)
	{
		super(message, cause);
		this.statusCode = statusCode;
	}

	public int statusCode()
	{
		return statusCode;
	}
}
