# REVIEW.md

# AI Code Review Standard

Version: 1.0

---

# PURPOSE

Coding is only half of engineering.

Review determines quality.

Every implementation MUST be reviewed before submission.

Never skip review.

---

# THE GOLDEN RULE

Assume your own code is wrong.

Your job is to prove it correct.

Not the opposite.

---

# REVIEW PIPELINE

Implementation

↓

Self Review

↓

Risk Review

↓

Regression Review

↓

Performance Review

↓

Architecture Review

↓

Verification

↓

Approval

Never submit directly.

---

# REVIEW MINDSET

Do NOT ask:

"Does this compile?"

Ask:

"Would I merge this into production?"

---

# REVIEW CHECKLIST

Every review MUST inspect:

□ Correctness

□ Safety

□ Maintainability

□ Simplicity

□ Readability

□ Consistency

□ Regression Risk

□ Android Best Practice

□ Performance

□ Future Maintenance

---

# CORRECTNESS

Verify:

Requirements satisfied

Edge cases handled

Null safety

Exceptions handled

Return values correct

Input validation

Output validation

No unfinished logic

---

# REGRESSION

Always ask:

Can this break existing behavior?

Can another module be affected?

Did execution order change?

Did lifecycle change?

Did timing change?

Did threading change?

Did permissions change?

If YES,

document it.

---

# ANDROID REVIEW

Verify:

Lifecycle

Compose State

remember

rememberSaveable

Coroutine Scope

Flow Collection

Activity Leak

Fragment Leak

ViewModel Leak

Navigation

Permission

Configuration Change

Rotation

Background

Foreground

---

# COMPOSE REVIEW

Check:

State Hoisting

Immutable State

remember usage

rememberSaveable usage

DerivedState

LaunchedEffect

DisposableEffect

SideEffect

Snapshot consistency

Recomposition count

Avoid unnecessary recomposition.

---

# THREAD REVIEW

Verify:

Main Thread

IO Thread

Default Dispatcher

Synchronization

Shared Mutable State

Race Conditions

Blocking Calls

Never block Main Thread.

---

# NULL SAFETY

Check:

Nullable values

Safe calls

Elvis operator

lateinit

Lazy initialization

Platform types

Never assume non-null.

---

# ERROR HANDLING

Verify:

Exceptions logged

Meaningful messages

Recovery strategy

Fallback behavior

Retry policy

Graceful degradation

Never swallow exceptions.

---

# LOGGING

Logs should:

Explain failure

Contain context

Avoid spam

Avoid sensitive data

Be searchable

Never remove useful logs.

---

# PERFORMANCE

Check:

Repeated allocation

Repeated computation

Repeated recomposition

Database query count

Network requests

Blocking IO

Large object creation

Memory usage

---

# MEMORY

Review:

Leak possibility

Reference lifecycle

Coroutine cancellation

Observer removal

Disposable cleanup

Bitmap release

Cache size

---

# SECURITY

Verify:

Permission checks

Input validation

Sensitive data

Token leakage

Command injection

Path traversal

Shell execution

ADB command safety

Shizuku permission

Never expose secrets.

---

# SHELL REVIEW

Verify:

Exit Code checked

stderr checked

stdout validated

Command escaped

Arguments escaped

Permission handled

Timeout handled

---

# SHIZUKU REVIEW

Verify:

Permission request

Binder state

Fallback behavior

Unsupported devices

Version compatibility

---

# DATABASE REVIEW

Check:

Migration

Indexes

Transactions

Query efficiency

Primary Key

Foreign Key

Room version

---

# NETWORK REVIEW

Verify:

Timeout

Retry

Failure handling

Serialization

HTTP Code

Offline handling

Cancellation

---

# FILE REVIEW

Verify:

File existence

Permissions

Encoding

Path safety

Overwrite risk

Cleanup

---

# CODE QUALITY

Avoid:

Magic Numbers

Duplicate Logic

Dead Code

Unused Imports

Unused Variables

Long Functions

Huge Classes

Hidden Side Effects

Complex Branches

---

# COMPLEXITY

If a function exceeds:

100 lines

Ask:

Can it be simplified?

If class exceeds:

1000 lines

DO NOT split automatically.

Refer to REFACTOR.md.

---

# ARCHITECTURE

Review:

Dependency direction

Module boundary

Coupling

Cohesion

Public API

Internal API

Responsibility

Never redesign architecture during review.

---

# AI SELF QUESTION

Before submission,

answer:

What is most likely wrong?

What edge case did I ignore?

What assumption did I make?

What could break?

Would another engineer understand this?

Would I maintain this after one year?

---

# RISK SCORE

Every review must output.

Risk Level

🟢 Low

🟡 Medium

🔴 High

Reason.

---

# CONFIDENCE SCORE

Estimate confidence.

★★★★★

Very High

★★★★☆

High

★★★☆☆

Medium

★★☆☆☆

Low

★☆☆☆☆

Very Low

Never fake confidence.

---

# REVIEW REPORT

Output:

## Summary

## Files

## Risks

## Potential Regression

## Verification

## Confidence

## Remaining Concerns

## Recommendation

Merge

or

Need Revision

---

# BLOCK MERGE

Review MUST reject merge if:

Build fails

Unknown behavior

Architecture modified

Regression possible

Untested changes

Core modules affected

Evidence insufficient

Never approve uncertain code.

---

# GOLDEN PRINCIPLE

A feature completed without review

is NOT completed.

Review is part of development.

Not an optional step.