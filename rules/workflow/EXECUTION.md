# EXECUTION.md

# AI Execution Standard Operating Procedure (SOP)

Version: 1.0

---

# PURPOSE

This document defines the mandatory execution workflow for every engineering task.

Every task follows exactly the same process.

Never skip stages.

Never change the order.

---

# GOLDEN RULE

Think First.

Code Later.

Verify Last.

---

# EXECUTION PIPELINE

Receive Task

↓

Understand

↓

Clarify

↓

Plan

↓

Risk Analysis

↓

Implementation

↓

Self Review

↓

Compile

↓

Verification

↓

Regression Check

↓

Documentation

↓

Commit

↓

Report

Task Finished

---

# STAGE 1

UNDERSTAND

Before coding, answer:

What is the user's real goal?

What problem is being solved?

What is NOT requested?

Never assume extra requirements.

---

# STAGE 2

CLARIFY

If anything is unclear,

ask.

Never guess.

Never invent missing requirements.

If confidence < 90%

stop.

---

# STAGE 3

PLAN

Before touching code,

produce:

Goal

Files

Risk

Expected Diff

Verification Plan

Rollback Plan

Only then continue.

---

# STAGE 4

RISK ANALYSIS

Classify.

🟢 Low

Only UI

Text

Icons

Constants

Logging

Documentation

-----------------------

🟡 Medium

Compose

ViewModel

Repository

Permission

Database

Lifecycle

-----------------------

🔴 High

Build

Shell

ADB

Shizuku

Bootstrap

Installer

Gradle

Architecture

Migration

If High

request approval.

---

# STAGE 5

IMPLEMENTATION

Rules.

Minimal Diff.

Minimal Files.

Minimal Risk.

Never optimize.

Never refactor.

Never clean unrelated code.

Only solve current task.

---

# STAGE 6

SELF REVIEW

Run REVIEW.md mentally.

Ask.

Would I approve this PR?

If NO

continue improving.

---

# STAGE 7

COMPILE

Never assume.

Always verify compilation.

If compilation unavailable,

state clearly.

Never lie.

---

# STAGE 8

VERIFICATION

Verify.

Requirement completed

↓

Original behavior unchanged

↓

No new warnings

↓

No obvious regression

↓

No crash

↓

Expected output

---

# STAGE 9

REGRESSION

Check.

Nearby functions.

Nearby classes.

Lifecycle.

Permission.

Navigation.

Thread.

Never leave after fixing only one line.

---

# STAGE 10

DOCUMENTATION

If architecture changed.

If API changed.

If workflow changed.

If debugging discovered new knowledge.

Update docs.

Otherwise,

no unnecessary documentation.

---

# STAGE 11

COMMIT

One Task.

↓

One Commit.

Never combine.

---

# STAGE 12

REPORT

Every completed task must output.

## Goal

## Files Modified

## Risk

## Verification

## Remaining Issues

## Rollback

## Suggested Next Step

---

# INTERRUPTION RULE

Stop immediately if:

Need architecture change.

Need dependency update.

Need Gradle update.

Need migration.

Need more than five files.

Need API changes.

Need deleting core code.

Need touching protected modules.

Then request approval.

---

# FAILURE RULE

If implementation fails.

Never retry blindly.

Instead.

Collect evidence.

Locate problem.

Explain failure.

Suggest alternatives.

Wait.

---

# UNKNOWN RULE

Unknown is acceptable.

Guessing is forbidden.

Use.

"I don't know."

"I need more evidence."

"I cannot verify."

Instead of inventing answers.

---

# CONTEXT MANAGEMENT

Never rely on memory.

Always inspect relevant files before changing them.

Never assume current architecture.

Never assume previous discussions.

Use project as source of truth.

---

# FILE READING RULE

Before editing any file.

Read.

Its purpose.

Its callers.

Its dependencies.

Its responsibility.

Never edit first.

Read first.

---

# MODIFICATION LIMIT

One execution cycle.

Preferred.

≤3 files.

Maximum.

5 files.

Otherwise.

Split task.

---

# HUMAN AUTHORITY

Human decides.

Priority.

Architecture.

Trade-offs.

Product.

AI decides.

Implementation.

Nothing else.

---

# END CONDITION

A task is complete ONLY IF.

Implementation finished.

↓

Review passed.

↓

Compilation verified.

↓

Regression checked.

↓

Report generated.

↓

Rollback possible.

↓

Human can understand what changed.

Otherwise,

the task is NOT complete.

---

# EXECUTION MANTRA

Observe.

Think.

Plan.

Implement.

Review.

Verify.

Report.

Repeat.

Never skip the process.