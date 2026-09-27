import { z } from "zod";

export const PointSchema = z.object({
  x: z.number().finite(),
  y: z.number().finite(),
});

export const ShapeSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("rect"),
    x: z.number().finite(),
    y: z.number().finite(),
    width: z.number().finite(),
    height: z.number().finite(),
  }),
  z.object({
    type: z.literal("circle"),
    centerX: z.number().finite(),
    centerY: z.number().finite(),
    radius: z.number().finite().nonnegative(),
  }),
  z.object({
    type: z.literal("pencil"),
    points: z.array(PointSchema).min(1).max(5000),
  }),
  z.object({
    type: z.literal("text"),
    x: z.number().finite(),
    y: z.number().finite(),
    content: z.string().min(1).max(2000),
    fontSize: z.number().finite().positive(),
  }),
]);

export type Point = z.infer<typeof PointSchema>;
export type Shape = z.infer<typeof ShapeSchema>;
