#!/usr/bin/env bash
set -euo pipefail

# Droplet pre-commit secret & forbidden-file guardrail.
# Install locally via: ln -sf ../../scripts/pre-commit-secret-check.sh .git/hooks/pre-commit

STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACM)
if [[ -z "${STAGED_FILES}" ]]; then
  exit 0
fi

# 1. Block forbidden sensitive filenames
FORBIDDEN_PATTERN='(^|/)(local\.properties|secrets\.properties|signing\.properties|keystore\.properties|\.env(\..*)?|google-services\.json|service-account.*\.json|.*\.(jks|keystore|p12|pfx|pem|key|crt|cer|drp|db|sqlite|sqlite3))$'
BAD_FILES=$(echo "${STAGED_FILES}" | grep -E "${FORBIDDEN_PATTERN}" || true)
if [[ -n "${BAD_FILES}" ]]; then
  echo "[BLOCKED] Sensitive or forbidden files staged for commit:"
  echo "${BAD_FILES}"
  exit 1
fi

# 2. Run gitleaks on staged changes if installed
if command -v gitleaks >/dev/null 2>&1; then
  gitleaks protect --staged --redact --config .gitleaks.toml
  exit 0
fi

# 3. Fallback regex check when gitleaks binary is unavailable locally
DIFF_ADDED=$(git diff --cached -U0 | grep -E '^\+[^+]' || true)
SECRET_REGEX='(-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{35}|https?://[^/\s:@]+:[^/\s:@]+@)'
if echo "${DIFF_ADDED}" | grep -Eq "${SECRET_REGEX}"; then
  echo "[BLOCKED] Potential secret, private key header, or credentialed URL detected in staged diff."
  echo "Install gitleaks and inspect 'git diff --cached' before committing."
  exit 1
fi

exit 0
