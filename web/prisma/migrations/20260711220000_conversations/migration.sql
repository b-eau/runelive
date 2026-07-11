-- Conversations: chat threads per profile. Existing messages are backfilled
-- into a single conversation per profile so history survives the migration.

-- CreateTable
CREATE TABLE "Conversation" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Conversation_pkey" PRIMARY KEY ("id")
);

-- AlterTable (nullable first; backfill below, then enforce NOT NULL)
ALTER TABLE "ChatMessage" ADD COLUMN "conversationId" TEXT;

-- Backfill: one conversation per profile that already has messages, titled
-- from its earliest user message.
INSERT INTO "Conversation" ("id", "profileId", "title", "createdAt", "updatedAt")
SELECT
    'legacy-' || md5(m."profileId"),
    m."profileId",
    COALESCE(
        LEFT((
            SELECT m2."content" FROM "ChatMessage" m2
            WHERE m2."profileId" = m."profileId" AND m2."role" = 'user'
            ORDER BY m2."createdAt" ASC LIMIT 1
        ), 60),
        'Earlier chats'
    ),
    MIN(m."createdAt"),
    MAX(m."createdAt")
FROM "ChatMessage" m
GROUP BY m."profileId";

UPDATE "ChatMessage" SET "conversationId" = 'legacy-' || md5("profileId");

ALTER TABLE "ChatMessage" ALTER COLUMN "conversationId" SET NOT NULL;

-- CreateIndex
CREATE INDEX "Conversation_profileId_updatedAt_idx" ON "Conversation"("profileId", "updatedAt");

-- CreateIndex
CREATE INDEX "ChatMessage_conversationId_createdAt_idx" ON "ChatMessage"("conversationId", "createdAt");

-- AddForeignKey
ALTER TABLE "Conversation" ADD CONSTRAINT "Conversation_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ChatMessage" ADD CONSTRAINT "ChatMessage_conversationId_fkey" FOREIGN KEY ("conversationId") REFERENCES "Conversation"("id") ON DELETE CASCADE ON UPDATE CASCADE;
