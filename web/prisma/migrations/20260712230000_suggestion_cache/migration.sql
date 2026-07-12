-- CreateTable
CREATE TABLE "SuggestionCache" (
    "profileId" TEXT NOT NULL,
    "context" TEXT NOT NULL,
    "payload" TEXT NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "SuggestionCache_pkey" PRIMARY KEY ("profileId","context")
);

-- AddForeignKey
ALTER TABLE "SuggestionCache" ADD CONSTRAINT "SuggestionCache_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

