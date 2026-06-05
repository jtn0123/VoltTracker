// ESLint flat config (I1) — placed at mobile/android/ so it's the root for the dashboard
// TypeScript source at app/src/main/dashboard-src/js/**. ESLint will not match files outside
// the directory containing the config, so the config lives one level above both `app/` and
// `dashboard-tests/`.
//
// Purpose: catch undefined variables, unused imports, and typos locally before they surface
// as a WebView console error or a Vitest failure 8 minutes later in CI. The rule set is
// deliberately conservative — recommended + a few targeted rules — so it never fights the
// classic-script IIFE module bodies or other legitimate dashboard idioms. (The dashboard
// loads as ordered classic <script> tags, not ES modules — modules don't load over file://.)
//
// Run via `npm --prefix mobile/android/dashboard-tests run lint`. The lefthook pre-commit
// hook invokes the same npm script for staged dashboard source files.

import { createRequire } from 'node:module';

const require = createRequire(new URL('./dashboard-tests/package.json', import.meta.url));
const tseslint = require('typescript-eslint');

export default [
  {
    // Lint only the production dashboard source. The vendored Leaflet bundle under lib/ is
    // intentionally excluded — it's third-party code we don't own.
    files: ['app/src/main/dashboard-src/js/**/*.ts'],
    ignores: ['app/src/main/assets/dashboard/lib/**/*'],
    plugins: {
      '@typescript-eslint': tseslint.plugin,
    },
    languageOptions: {
      parser: tseslint.parser,
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        // Browser globals the dashboard runs against inside the Android WebView.
        window: 'readonly',
        document: 'readonly',
        console: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        AbortController: 'readonly',
        URLSearchParams: 'readonly',
        requestAnimationFrame: 'readonly',
        cancelAnimationFrame: 'readonly',
        KeyboardEvent: 'readonly',
        MouseEvent: 'readonly',
        performance: 'readonly',
        Number: 'readonly',
        String: 'readonly',
        Math: 'readonly',
        Object: 'readonly',
        Array: 'readonly',
        JSON: 'readonly',
        Date: 'readonly',
        Boolean: 'readonly',
        // Leaflet is loaded as a side-effecting global from lib/leaflet/leaflet.js.
        L: 'readonly',
      },
    },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': [
        'warn',
        {
          // Underscore-prefixed identifiers are intentional unused.
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^(_.*|ignored)$',
        },
      ],
      'no-redeclare': 'error',
      'no-dupe-keys': 'error',
      'no-unreachable': 'error',
    },
  },
  {
    files: ['app/src/main/dashboard-src/js/**/*.ts'],
    rules: {
      // TypeScript resolves ambient/interface-only names; core ESLint sees those
      // as runtime identifiers and reports false positives.
      'no-undef': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^(_.*|ignored)$',
        },
      ],
    },
  },
];
