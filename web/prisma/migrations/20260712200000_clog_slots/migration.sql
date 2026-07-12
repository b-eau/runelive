-- CreateTable
CREATE TABLE "CollectionLogSlot" (
    "profileId" TEXT NOT NULL,
    "itemId" INTEGER NOT NULL,
    "obtained" BOOLEAN NOT NULL,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "CollectionLogSlot_pkey" PRIMARY KEY ("profileId","itemId")
);

-- CreateIndex
CREATE INDEX "CollectionLogSlot_profileId_obtained_idx" ON "CollectionLogSlot"("profileId", "obtained");

-- AddForeignKey
ALTER TABLE "CollectionLogSlot" ADD CONSTRAINT "CollectionLogSlot_profileId_fkey" FOREIGN KEY ("profileId") REFERENCES "Profile"("id") ON DELETE CASCADE ON UPDATE CASCADE;

