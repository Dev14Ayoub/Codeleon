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

export type LoginValues = z.infer<typeof loginSchema>;
export type SignupValues = z.infer<typeof signupSchema>;
