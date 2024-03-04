module.exports = {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "scope-enum": [
      2,
      "always",
      [
        "deps",
      ],
    ],
    "subject-case": [0, 'never'],
    // No need to restrict the body and footer line length. That only gives issues with URLs etc.
    "body-max-line-length": [0, 'always'],
    "footer-max-line-length": [0, 'always']
  },
  ignores: [
    (message) => message.includes('skip-lint')
  ],
};
