-- The schema as the four Prisma migrations left it (init_schema,
-- made_photo_optional, email_unique, chat_room_index_and_cascade), with the
-- same table, constraint and index names.
--
-- A database Prisma already migrated is not empty, so Flyway baselines it at
-- version 1 (spring.flyway.baseline-on-migrate) and skips this file. Only a
-- fresh database runs it.

CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "password" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "photo" TEXT,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Room" (
    "id" SERIAL NOT NULL,
    "slug" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "adminId" TEXT NOT NULL,

    CONSTRAINT "Room_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "Chat" (
    "id" SERIAL NOT NULL,
    "roomId" INTEGER NOT NULL,
    "message" TEXT NOT NULL,
    "userId" TEXT NOT NULL,

    CONSTRAINT "Chat_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

CREATE UNIQUE INDEX "Room_slug_key" ON "Room"("slug");

-- Every read is "all chats for a room, in drawing order".
CREATE INDEX "Chat_roomId_id_idx" ON "Chat"("roomId", "id");

ALTER TABLE "Room" ADD CONSTRAINT "Room_adminId_fkey"
    FOREIGN KEY ("adminId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "Chat" ADD CONSTRAINT "Chat_roomId_fkey"
    FOREIGN KEY ("roomId") REFERENCES "Room"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "Chat" ADD CONSTRAINT "Chat_userId_fkey"
    FOREIGN KEY ("userId") REFERENCES "User"("id") ON DELETE CASCADE ON UPDATE CASCADE;
