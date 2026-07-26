import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  {
    ignores: ['dist/**', 'node/**', 'node_modules/**', 'target/**', 'coverage/**']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      'no-unused-vars': ['warn', {argsIgnorePattern: '^_', varsIgnorePattern: '^_'}],
      'no-empty': 'warn',
      'no-useless-assignment': 'warn',
      'no-useless-escape': 'warn',
      'vue/multi-word-component-names': 'off',
      // Configuration editors intentionally mutate a parent-owned draft object.
      'vue/no-mutating-props': 'off',
      'vue/no-v-html': 'off'
    }
  },
  {
    files: ['**/*.spec.js', 'vitest.config.js'],
    languageOptions: {
      globals: globals.vitest
    }
  }
]
