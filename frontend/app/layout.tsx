import type { Metadata } from "next";
import { Nav, SessionProvider } from "@/components/auth";
import "./globals.css";

export const metadata: Metadata = { title: { default: "CodeArena", template: "%s · CodeArena" }, description: "Practice programming problems with automated evaluation" };

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="min-h-screen">
        <SessionProvider>
          <Nav />
          <main>{children}</main>
        </SessionProvider>
      </body>
    </html>
  );
}
