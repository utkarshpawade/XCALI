import { Button } from "@repo/ui/button";
import { Card } from "@repo/ui/card";
import { Pencil, Share2, Users2, Sparkles, Github } from "lucide-react";
import Link from "next/link";

const FEATURES = [
  {
    icon: Share2,
    title: "Real-time Collaboration",
    body: "Work together with your team in real time. Share your board instantly with a simple name.",
  },
  {
    icon: Users2,
    title: "Multiplayer Editing",
    body: "Multiple people can edit the same canvas at once and see each other's shapes appear live.",
  },
  {
    icon: Sparkles,
    title: "Pencil, Shapes, Pan & Zoom",
    body: "Draw freehand, add rectangles and circles, then pan and zoom around an infinite canvas.",
  },
];

function App() {
  return (
    <div className="min-h-screen bg-background">
      {/* Hero Section */}
      <header className="relative overflow-hidden">
        <div className="container mx-auto px-4 py-16 sm:px-6 lg:px-8">
          <div className="text-center">
            <h1 className="text-4xl font-bold tracking-tight sm:text-6xl text-foreground">
              Collaborative Whiteboarding
              <span className="text-primary block">Made Simple</span>
            </h1>
            <p className="mx-auto mt-6 max-w-2xl text-lg text-muted-foreground">
              Create, collaborate, and share beautiful diagrams and sketches with
              our intuitive drawing tool.
            </p>
            <div className="mt-10 flex items-center justify-center gap-x-6">
              <Link href="/signin">
                <Button variant="primary" size="lg" className="h-12 px-6">
                  Sign in
                  <Pencil className="ml-2 h-4 w-4" />
                </Button>
              </Link>
              <Link href="/signup">
                <Button variant="outline" size="lg" className="h-12 px-6">
                  Sign up
                </Button>
              </Link>
            </div>
          </div>
        </div>
      </header>

      {/* Features Section */}
      <section className="py-24 bg-muted/50">
        <div className="container mx-auto px-4 sm:px-6 lg:px-8">
          <div className="grid grid-cols-1 gap-12 sm:grid-cols-2 lg:grid-cols-3">
            {FEATURES.map(({ icon: Icon, title, body }) => (
              <Card
                key={title}
                className="rounded-2xl bg-card p-6 border-2 hover:border-primary transition-colors"
              >
                <div className="flex items-center gap-4">
                  <div className="p-2 rounded-lg bg-primary/10">
                    <Icon className="h-6 w-6 text-primary" />
                  </div>
                  <h3 className="text-xl font-semibold">{title}</h3>
                </div>
                <p className="mt-4 text-muted-foreground">{body}</p>
              </Card>
            ))}
          </div>
        </div>
      </section>

      {/* CTA Section */}
      <section className="py-24">
        <div className="container mx-auto px-4 sm:px-6 lg:px-8">
          <div className="bg-primary rounded-3xl p-8 sm:p-16">
            <div className="mx-auto max-w-2xl text-center">
              <h2 className="text-3xl font-bold tracking-tight text-primary-foreground sm:text-4xl">
                Ready to start creating?
              </h2>
              <p className="mx-auto mt-6 max-w-xl text-lg text-primary-foreground/80">
                Sign in, create a board, and share its name with anyone you want
                to draw with.
              </p>
              <div className="mt-10 flex items-center justify-center gap-x-6">
                <Link href="/rooms">
                  <Button size="lg" variant="secondary" className="h-12 px-6">
                    Open Canvas
                    <Pencil className="ml-2 h-4 w-4" />
                  </Button>
                </Link>
                <Link href="/signup">
                  <Button
                    variant="outline"
                    size="lg"
                    className="h-12 px-6 bg-transparent text-primary-foreground border-primary-foreground hover:bg-primary-foreground hover:text-primary"
                  >
                    Create an account
                  </Button>
                </Link>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="border-t">
        <div className="container mx-auto px-4 py-12 sm:px-6 lg:px-8">
          <div className="flex flex-col items-center justify-between gap-6 sm:flex-row">
            <p className="text-sm text-muted-foreground">
              Collaborative Whiteboard - built as a learning project.
            </p>
            <div className="flex space-x-6">
              <a
                href="https://github.com"
                target="_blank"
                rel="noopener noreferrer"
                aria-label="GitHub"
                className="text-muted-foreground hover:text-primary"
              >
                <Github className="h-5 w-5" />
              </a>
            </div>
          </div>
        </div>
      </footer>
    </div>
  );
}

export default App;
