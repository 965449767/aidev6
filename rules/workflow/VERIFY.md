# VERIFY.md

# AI Definition of Done (DoD)

Version: 1.0

---

# PURPOSE

This document defines when a task is truly complete.

Code written

≠

Task completed.

Implementation is only one part of engineering.

A task is finished only after verification.

---

# GOLDEN RULE

Nothing is Done

Until Verified.

---

# DEFINITION OF DONE

A task is complete ONLY IF all required items pass.

Implementation

✓

Compilation

✓

Verification

✓

Review

✓

Regression Check

✓

Documentation

✓

Rollback Possible

✓

Report Generated

✓

Otherwise.

Task = NOT DONE.

---

# LEVEL 1

IMPLEMENTATION

Verify:

Requirement implemented.

No placeholder.

No TODO.

No FIXME.

No unfinished branch.

No disabled code.

---

# LEVEL 2

COMPILATION

Verify.

Project builds.

Modified files compile.

Imports resolved.

Resources resolved.

Manifest valid.

Gradle valid.

If build cannot be executed.

State clearly.

Never assume.

---

# LEVEL 3

FUNCTIONAL VERIFICATION

Verify.

Feature works.

Original workflow preserved.

Inputs accepted.

Outputs correct.

Expected behavior achieved.

Edge cases handled.

---

# LEVEL 4

REGRESSION

Verify nearby behavior.

Nothing unrelated broke.

Navigation works.

Permissions work.

Lifecycle preserved.

Database unchanged.

State preserved.

Background execution unchanged.

Shell behavior unchanged.

---

# LEVEL 5

SELF REVIEW

Execute REVIEW.md.

If confidence is low.

Continue reviewing.

Do not submit.

---

# LEVEL 6

PERFORMANCE

Verify.

No obvious slowdown.

No unnecessary allocation.

No repeated work.

No endless recomposition.

No blocking main thread.

---

# LEVEL 7

MEMORY

Check.

Leaks.

Observer cleanup.

Coroutine cancellation.

Binder cleanup.

Shell session cleanup.

Bitmap release.

Cache.

---

# LEVEL 8

ANDROID

Verify.

Activity.

Fragment.

Compose.

Rotation.

Background.

Foreground.

Dark Mode.

Permission.

Configuration Change.

Android Version.

---

# LEVEL 9

SHIZUKU

Verify.

Permission granted.

Binder alive.

Fallback available.

Failure handled.

Version compatible.

---

# LEVEL 10

ADB

Verify.

Command success.

Exit Code.

stderr.

stdout.

Permission.

Unsupported device.

---

# LEVEL 11

DATABASE

Verify.

Migration.

Read.

Write.

Rollback.

Schema.

No corruption.

---

# LEVEL 12

NETWORK

Verify.

Offline.

Timeout.

Retry.

Cancel.

Serialization.

Error response.

---

# LEVEL 13

SECURITY

Verify.

No token leakage.

No secret logging.

Permission checked.

Input validated.

Shell escaped.

ADB safe.

---

# LEVEL 14

LOGGING

Verify.

Useful.

Searchable.

Context included.

No spam.

No secrets.

---

# LEVEL 15

ROLLBACK

Every task must be reversible.

AI must answer.

How can this change be reverted?

Git Reset

Git Revert

Rollback Script

Manual Restore

At least one must exist.

---

# LEVEL 16

DOCUMENTATION

Update docs only if.

Architecture changed.

Workflow changed.

Configuration changed.

New module added.

Otherwise.

Avoid unnecessary documentation.

---

# CONFIDENCE

Estimate confidence.

★★★★★

Production Ready.

★★★★☆

Ready.

★★★☆☆

Needs Testing.

★★☆☆☆

Needs Review.

★☆☆☆☆

Unsafe.

Never output ★★★★★ without evidence.

---

# ACCEPTANCE REPORT

Every task must output.

## Goal

## Files

## Compile

PASS / FAIL

## Verification

PASS / FAIL

## Regression

PASS / FAIL

## Review

PASS / FAIL

## Rollback

PASS / FAIL

## Confidence

★★★★★

## Remaining Risks

## Recommendation

Ready

or

Needs More Work

---

# AUTOMATIC REJECTION

A task must be rejected if.

Build fails.

Unknown behavior.

Regression detected.

Untested.

Architecture changed unexpectedly.

Core module modified without approval.

Rollback impossible.

Confidence below ★★★☆☆.

---

# NEVER SAY

"It should work."

"It probably works."

"It looks correct."

"It compiles in theory."

Forbidden.

Only report verified facts.

---

# HUMAN HANDOFF

Before finishing.

AI must answer.

What changed?

What should the human verify?

What remains uncertain?

What should be tested next?

Never leave silently.

---

# GOLDEN PRINCIPLE

Verified software

is more valuable

than

unverified software

that merely compiles.