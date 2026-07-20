export interface PasswordRequirement {
  id: string;
  label: string;
  test: (password: string) => boolean;
}

export const PASSWORD_REQUIREMENTS: PasswordRequirement[] = [
  { id: 'length', label: 'Minimum 8 characters', test: (p) => p.length >= 8 },
  { id: 'uppercase', label: 'One uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { id: 'lowercase', label: 'One lowercase letter', test: (p) => /[a-z]/.test(p) },
  { id: 'number', label: 'One number', test: (p) => /[0-9]/.test(p) },
  { id: 'special', label: 'One special character', test: (p) => /[^A-Za-z0-9]/.test(p) },
];

export type PasswordStrength = 'empty' | 'weak' | 'fair' | 'good' | 'strong';

export function getPasswordStrength(password: string): PasswordStrength {
  if (!password) return 'empty';
  const metCount = PASSWORD_REQUIREMENTS.filter((r) => r.test(password)).length;
  if (metCount === PASSWORD_REQUIREMENTS.length && password.length >= 12) return 'strong';
  if (metCount >= 4) return 'good';
  if (metCount >= 2) return 'fair';
  return 'weak';
}
