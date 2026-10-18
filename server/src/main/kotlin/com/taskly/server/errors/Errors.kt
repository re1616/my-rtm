package com.taskly.server.errors

/** 401 — missing, invalid or expired credentials. */
class UnauthorizedException(message: String) : RuntimeException(message)

/** 403 — authenticated, but the resource or scope belongs to someone else. */
class ForbiddenException(message: String) : RuntimeException(message)

/** 404 — the resource does not exist for this user. */
class NotFoundException(message: String) : RuntimeException(message)

/** 409 — optimistic-locking or id conflict. */
class ConflictException(message: String) : RuntimeException(message)

/** 429 — rate limit exceeded. */
class TooManyRequestsException(message: String, val retryAfterSeconds: Long = 1) : RuntimeException(message)

/** 426 — client version too old, must upgrade. */
class UpgradeRequiredException(message: String) : RuntimeException(message)
