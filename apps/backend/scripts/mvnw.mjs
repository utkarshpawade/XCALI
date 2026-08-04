// Runs the Maven wrapper with the given arguments on any OS, so the
// package.json scripts work under pnpm and turbo on Windows as well.
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);

let child;
if (process.platform === "win32") {
  // .cmd files only run through cmd.exe. The wrapper is addressed by absolute
  // path because cmd may be set not to search the current directory, and the
  // command is one pre-quoted string because Node deprecates shell + args.
  const quote = (arg) => (/^[\w:.=,@/\\-]+$/.test(arg) ? arg : `"${arg.replace(/"/g, '\\"')}"`);
  const command = [`"${path.join(projectDir, "mvnw.cmd")}"`, ...args.map(quote)].join(" ");
  child = spawn(command, { cwd: projectDir, stdio: "inherit", shell: true });
} else {
  // `sh` avoids depending on mvnw's executable bit, which a Windows checkout
  // does not preserve.
  child = spawn("sh", [path.join(projectDir, "mvnw"), ...args], { cwd: projectDir, stdio: "inherit" });
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
