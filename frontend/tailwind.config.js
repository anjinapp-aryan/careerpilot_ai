/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: { 500: '#5b6cff', 600: '#4753e6', 700: '#3a44c2' },
      },
    },
  },
  plugins: [],
};
