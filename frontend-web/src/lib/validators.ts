import { z } from "zod";

export const loginSchema = z.object({
  email: z.string().email("Enter a valid email address"),
  password: z.string().min(1, "Password is required"),
});

export const signupSchema = z.object({
  fullName: z.string().min(2, "Name must contain at least 2 characters").max(120),
  email: z.string().email("Enter a valid email address"),
  password: z
    .string()
    .min(8, "Password must contain at least 8 characters")
    .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, "Password must contain one letter and one number"),
});

export const createRoomSchema = z.object({
  name: z.string().min(2, "Room name must contain at least 2 characters").max(120),
  description: z.string().max(500).optional(),
  visibility: z.enum(["PRIVATE", "PUBLIC"]),
  templateId: z.string().optional(),
});

export const joinRoomSchema = z.object({
  inviteCode: z.string().min(6, "Invite code is required"),
});

export type LoginValues = z.infer<typeof loginSchema>;
export type SignupValues = z.infer<typeof signupSchema>;
export type CreateRoomValues = z.infer<typeof createRoomSchema>;
export type JoinRoomValues = z.infer<typeof joinRoomSchema>;
