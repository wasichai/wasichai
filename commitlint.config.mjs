// conventional commits. release-please reads them to pick the next version.
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // the imported history uses long, sentence-like subjects
    'header-max-length': [2, 'always', 120],
    'subject-case': [0]
  }
}
