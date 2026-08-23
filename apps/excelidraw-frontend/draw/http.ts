import { ShapeSchema, type Shape } from "@repo/common/types";
import { api } from "@/lib/api";

/**
 * Loads the shape history for a room. Rows that are not valid shapes (older
 * formats, hand written data) are skipped instead of breaking the canvas.
 */
export async function getExistingShapes(roomId: string): Promise<Shape[]> {
  const res = await api.get(`/chats/${encodeURIComponent(roomId)}`);
  const messages: { message: string }[] = res.data.messages ?? [];

  const shapes: Shape[] = [];

  for (const row of messages) {
    try {
      const parsed = ShapeSchema.safeParse(JSON.parse(row.message)?.shape);
      if (parsed.success) {
        shapes.push(parsed.data);
      }
    } catch {
      // Ignore unparseable rows.
    }
  }

  return shapes;
}
