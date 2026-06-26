package com.runelive.sidekick.http;

/** Raised when an upstream HTTP call fails (non-2xx response or transport error). */
public class HttpException extends RuntimeException
{
	private final int statusCode;

	public HttpException(int statusCode, String message)
	{
		super(message);
		this.statusCode = statusCode;
	}

	public HttpException(int statusCode, String message, Throwable cause)
	{
		super(message, cause);
		this.statusCode = statusCode;
	}

	/** HTTP status code, or {@code 0} for a transport-level failure. */
	public int statusCode()
	{
		return statusCode;
	}
}
