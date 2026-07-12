-- AlterTable
ALTER TABLE "ItemPrice" ADD COLUMN     "examine" TEXT,
ADD COLUMN     "highAlch" INTEGER,
ADD COLUMN     "members" BOOLEAN;

-- CreateIndex
CREATE INDEX "ItemPrice_name_idx" ON "ItemPrice"("name");

