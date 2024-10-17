import { AuthForm } from "@/components/auth";

export const metadata = { title: "Sign in" };
export default function Login() {
  return <AuthForm mode="login" />;
}
