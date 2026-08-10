module.exports = {
  root: true,
  env: { browser: true, es2020: true },
  // 不使用 plugin:react-refresh/recommended：新版本（>=0.4.19）的配置是 flat
  // config 格式（含 name 属性/对象式 plugins），ESLint 8 eslintrc 无法加载，
  // 这里显式声明插件与规则，保持 ESLint 8 兼容。
  extends: [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
    "plugin:react/recommended",
    "plugin:react-hooks/recommended",
    "prettier"
  ],
  ignorePatterns: ["dist", "node_modules"],
  parser: "@typescript-eslint/parser",
  parserOptions: {
    ecmaVersion: "latest",
    sourceType: "module"
  },
  plugins: ["@typescript-eslint", "react-refresh"],
  settings: {
    react: { version: "detect" }
  },
  rules: {
    "react/react-in-jsx-scope": "off",
    "react/prop-types": "off",
    "react-refresh/only-export-components": "error"
  }
};
