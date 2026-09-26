// conventional commits. release-please reads them to pick the next version.
export default {
  extends: ['@commitlint/config-conventional'],
  // dependabot bodies carry long release-note lines; its header is still checked by the pr title job
  ignores: [(message) => message.includes('Signed-off-by: dependabot[bot]')],
  rules: {
    // the imported history uses long, sentence-like subjects
    'header-max-length': [2, 'always', 120],
    'subject-case': [0]
  }
}
