-- CreateSchema
CREATE SCHEMA IF NOT EXISTS "public";

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Session" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Session_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MagicLink" (
    "token" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "usedAt" TIMESTAMP(3),

    CONSTRAINT "MagicLink_pkey" PRIMARY KEY ("token")
);

-- CreateTable
CREATE TABLE "OsrsAccount" (
    "id" TEXT NOT NULL,
    "userId" TEXT NOT NULL,
    "accountHash" TEXT NOT NULL,
    "displayName" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "OsrsAccount_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Profile" (
    "id" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,
    "kind" TEXT NOT NULL DEFAULT 'STANDARD',
    "accountType" TEXT NOT NULL DEFAULT 'REGULAR',
    "combatLevel" INTEGER,
    "lastSyncedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Profile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "LinkCode" (
    "code" TEXT NOT NULL,
    "pollSecret" TEXT NOT NULL,
    "accountHash" TEXT NOT NULL,
    "displayName" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "apiToken" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "claimedAt" TIMESTAMP(3),

    CONSTRAINT "LinkCode_pkey" PRIMARY KEY ("code")
);

-- CreateTable
CREATE TABLE "ApiToken" (
    "token" TEXT NOT NULL,
    "accountId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "lastUsedAt" TIMESTAMP(3),
    "revokedAt" TIMESTAMP(3),

    CONSTRAINT "ApiToken_pkey" PRIMARY KEY ("token")
);

-- CreateTable
CREATE TABLE "Event" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "type" TEXT NOT NULL,
    "payload" TEXT NOT NULL,
    "dedupeKey" TEXT,
    "occurredAt" TIMESTAMP(3) NOT NULL,
    "receivedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Event_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "SkillState" (
    "profileId" TEXT NOT NULL,
    "skill" TEXT NOT NULL,
    "xp" BIGINT NOT NULL,
    "level" INTEGER NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "SkillState_pkey" PRIMARY KEY ("profileId","skill")
);

-- CreateTable
CREATE TABLE "QuestState" (
    "profileId" TEXT NOT NULL,
    "quest" TEXT NOT NULL,
    "state" TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "QuestState_pkey" PRIMARY KEY ("profileId","quest")
);

-- CreateTable
CREATE TABLE "DiaryState" (
    "profileId" TEXT NOT NULL,
    "area" TEXT NOT NULL,
    "tier" TEXT NOT NULL,
    "completed" BOOLEAN NOT NULL DEFAULT false,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DiaryState_pkey" PRIMARY KEY ("profileId","area","tier")
);

-- CreateTable
CREATE TABLE "ContainerState" (
    "profileId" TEXT NOT NULL,
    "container" TEXT NOT NULL,
    "items" TEXT NOT NULL,
    "value" BIGINT NOT NULL DEFAULT 0,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ContainerState_pkey" PRIMARY KEY ("profileId","container")
);

-- CreateTable
CREATE TABLE "KillCountState" (
    "profileId" TEXT NOT NULL,
    "boss" TEXT NOT NULL,
    "kc" INTEGER NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "KillCountState_pkey" PRIMARY KEY ("profileId","boss")
);

-- CreateTable
CREATE TABLE "XpSample" (
    "profileId" TEXT NOT NULL,
    "skill" TEXT NOT NULL,
    "date" TIMESTAMP(3) NOT NULL,
    "xp" BIGINT NOT NULL,

    CONSTRAINT "XpSample_pkey" PRIMARY KEY ("profileId","skill","date")
);

-- CreateTable
CREATE TABLE "BankValueSample" (
    "profileId" TEXT NOT NULL,
    "date" TIMESTAMP(3) NOT NULL,
    "value" BIGINT NOT NULL,

    CONSTRAINT "BankValueSample_pkey" PRIMARY KEY ("profileId","date")
);

-- CreateTable
CREATE TABLE "KcSample" (
    "profileId" TEXT NOT NULL,
    "boss" TEXT NOT NULL,
    "date" TIMESTAMP(3) NOT NULL,
    "kc" INTEGER NOT NULL,

    CONSTRAINT "KcSample_pkey" PRIMARY KEY ("profileId","boss","date")
);

-- CreateTable
CREATE TABLE "Goal" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "notes" TEXT,
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Goal_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ChatMessage" (
    "id" TEXT NOT NULL,
    "profileId" TEXT NOT NULL,
    "role" TEXT NOT NULL,
    "content" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ChatMessage_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ItemPrice" (
    "itemId" INTEGER NOT NULL,
    "name" TEXT NOT NULL,
    "price" INTEGER NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ItemPrice_pkey" PRIMARY KEY ("itemId")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE INDEX "Session_userId_idx" ON "Session"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "OsrsAccount_accountHash_key" ON "OsrsAccount"("accountHash");

-- CreateIndex
CREATE INDEX "OsrsAccount_userId_idx" ON "OsrsAccount"("userId");

-- CreateIndex
CREATE UNIQUE INDEX "Profile_accountId_kind_key" ON "Profile"("accountId", "kind");

-- CreateIndex
CREATE INDEX "ApiToken_accountId_idx" ON "ApiToken"("accountId");

-- CreateIndex
CREATE UNIQUE INDEX "Event_dedupeKey_key" ON "Event"("dedupeKey");

-- CreateIndex
CREATE INDEX "Event_profileId_type_occurredAt_idx" ON "Event"("profileId", "type", "occurredAt");

-- CreateIndex
CREATE INDEX "XpSample_profileId_date_idx" ON "XpSample"("profileId", "date");

-- CreateIndex
CREATE INDEX "Goal_profileId_status_idx" ON "Goal"("profileId", "status");

-- CreateIndex
CREATE INDEX "ChatMessage_profileId_createdAt_idx" ON "ChatMessage"("profileId", "createdAt");

-- AddForeignKey
ALTER TABLE "Session" ADD CONSTRAINT "Session_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "OsrsAccount" ADD CONSTRAINT "OsrsAccount_userId_fkey" FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Profile" ADD CONSTRAINT "Profile_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "OsrsAccount"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ApiToken" ADD CONSTRAINT "ApiToken_accountId_fkey" FOREIGN KEY ("accountId") REFERENCES "OsrsAccount"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Event" ADD CONSTRAINT "Event_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "SkillState" ADD CONSTRAINT "SkillState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "QuestState" ADD CONSTRAINT "QuestState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "DiaryState" ADD CONSTRAINT "DiaryState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ContainerState" ADD CONSTRAINT "ContainerState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "KillCountState" ADD CONSTRAINT "KillCountState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "XpSample" ADD CONSTRAINT "XpSample_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "BankValueSample" ADD CONSTRAINT "BankValueSample_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "KcSample" ADD CONSTRAINT "KcSample_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Goal" ADD CONSTRAINT "Goal_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ChatMessage" ADD CONSTRAINT "ChatMessage_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

