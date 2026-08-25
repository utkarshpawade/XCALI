import { ReactNode } from "react";

export function IconButton({
  icon,
  onClick,
  activated,
  label,
}: {
  icon: ReactNode;
  onClick: () => void;
  activated: boolean;
  label: string;
}) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={activated}
      onClick={onClick}
      className={`rounded-lg p-2 transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/40 ${
        activated ? "bg-white/15 text-primary" : "text-white/80"
      }`}
    >
      {icon}
    </button>
  );
}
