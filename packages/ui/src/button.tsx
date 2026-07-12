"use client";

import { ReactNode } from "react";

interface ButtonProps {
  variant?: "primary" | "outline" | "secondary";
  className?: string;
  onClick?: () => void;
  size?: "lg" | "sm";
  type?: "button" | "submit";
  disabled?: boolean;
  children: ReactNode;
}

const VARIANTS = {
  primary:
    "bg-primary text-primary-foreground shadow-sm hover:bg-primary/90",
  secondary:
    "bg-secondary text-secondary-foreground shadow-sm hover:bg-secondary/80",
  outline:
    "border border-input bg-background shadow-sm hover:bg-accent hover:text-accent-foreground",
} as const;

const SIZES = {
  lg: "px-4 py-2 text-base",
  sm: "px-2 py-1 text-sm",
} as const;

export const Button = ({
  size = "sm",
  variant = "primary",
  className = "",
  onClick,
  type = "button",
  disabled = false,
  children,
}: ButtonProps) => {
  return (
    <button
      type={type}
      disabled={disabled}
      className={`inline-flex items-center justify-center rounded-lg font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50 ${VARIANTS[variant]} ${SIZES[size]} ${className}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
};
