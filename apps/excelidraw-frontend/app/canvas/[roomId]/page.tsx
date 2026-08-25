import { RoomCanvas } from "@/components/RoomCanvas";

// In Next 15 `params` is a promise and must be awaited.
export default async function CanvasPage({
  params,
}: {
  params: Promise<{ roomId: string }>;
}) {
  const { roomId } = await params;

  return <RoomCanvas roomId={roomId} />;
}
