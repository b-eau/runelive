-- CreateTable
CREATE TABLE "CombatAchievementState" (
    "profileId" TEXT NOT NULL,
    "points" INTEGER NOT NULL,
    "thresholds" TEXT NOT NULL,
    "easy" BOOLEAN NOT NULL DEFAULT false,
    "medium" BOOLEAN NOT NULL DEFAULT false,
    "hard" BOOLEAN NOT NULL DEFAULT false,
    "elite" BOOLEAN NOT NULL DEFAULT false,
    "master" BOOLEAN NOT NULL DEFAULT false,
    "grandmaster" BOOLEAN NOT NULL DEFAULT false,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CombatAchievementState_pkey" PRIMARY KEY ("profileId")
);

-- CreateTable
CREATE TABLE "CollectionLogState" (
    "profileId" TEXT NOT NULL,
    "obtained" INTEGER NOT NULL,
    "total" INTEGER NOT NULL,
    "sections" TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CollectionLogState_pkey" PRIMARY KEY ("profileId")
);

-- AddForeignKey
ALTER TABLE "CombatAchievementState" ADD CONSTRAINT "CombatAchievementState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "CollectionLogState" ADD CONSTRAINT "CollectionLogState_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

