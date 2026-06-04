# [OPEN] Debug Session: login-submit-error

## Symptom
- Frontend `http://localhost:5173/login` clicks login and shows "系统执行出错".

## Session
- sessionId: `login-submit-error`
- status: OPEN

## Hypotheses
1. Frontend login request URL or proxy target is incorrect.
2. Backend login endpoint throws a runtime exception.
3. Frontend request payload does not match backend contract.
4. Frontend response parsing fails after a nominally successful response.

## Evidence
- Pending runtime reproduction and request/response capture.

## Next Steps
1. Inspect frontend login page, auth service, and API base configuration.
2. Inspect backend login endpoint and exception path.
3. Reproduce and capture browser network evidence.
