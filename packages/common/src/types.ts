import { z } from "zod";

/**
 * The `username` field is stored in the unique `email` column, so it is
 * validated as an email address.
 */
export const CreateUserSchema = z.object({
  username: z.string().email("A valid email address is required"),
  password: z.string().min(6, "Password must be at least 6 characters"),
  name: z.string().min(1, "Name is required").max(50),
});

export const SigninSchema = z.object({
  username: z.string().email("A valid email address is required"),
  password: z.string().min(1, "Password is required"),
});

export const CreateRoomSchema = z.object({
  name: z
    .string()
    .min(3, "Room name must be at least 3 characters")
    .max(20, "Room name must be at most 20 characters")
    .regex(
      /^[a-zA-Z0-9-_]+$/,
      "Room name may only contain letters, numbers, hyphens and underscores",
    ),
});

export type CreateUserInput = z.infer<typeof CreateUserSchema>;
export type SigninInput = z.infer<typeof SigninSchema>;
export type CreateRoomInput = z.infer<typeof CreateRoomSchema>;

/* -------------------------------------------------------------------------- */
/*                             WebSocket messages                             */
/* -------------------------------------------------------------------------- */

/** Messages sent from the browser to the WebSocket server. */
export const ClientMessageSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("join_room"),
    roomId: z.union([z.string(), z.number()]),
  }),
  z.object({
    type: z.literal("leave_room"),
    roomId: z.union([z.string(), z.number()]),
  }),
  z.object({
    type: z.literal("chat"),
    roomId: z.union([z.string(), z.number()]),
    message: z.string().max(200_000),
  }),
]);

export type ClientMessage = z.infer<typeof ClientMessageSchema>;
