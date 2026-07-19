import { ChatRoom } from "../../../components/ChatRoom";

// In Next 15 `params` is a promise and must be awaited.
export default async function RoomPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;

  return <ChatRoom slug={slug} />;
}
