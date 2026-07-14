# TASK.md

# AI Task Execution Standard

Version: 1.0

---

# PURPOSE

This document defines how every AI agent executes engineering tasks.

The objective is:

- predictable progress
- minimal regression
- controllable changes
- easy rollback

NOT maximum coding speed.

---

# THE GOLDEN RULE

One Task

↓

One Goal

↓

One Commit

Never combine unrelated work.

---

# TASK SIZE

Preferred duration:

15~60 minutes.

Maximum:

2 hours.

If estimated work exceeds two hours,

the task MUST be split.

---

# TASK SPLITTING

AI must always decompose large requests.

Example

BAD

Implement Chat System.

GOOD

Task 1

Create data models.

↓

Task 2

Implement repository.

↓

Task 3

Implement service.

↓

Task 4

Create ViewModel.

↓

Task 5

Build UI.

↓

Task 6

Testing.

---

# TASK TEMPLATE

Every task must include:

## Goal

What will be achieved.

---

## Files

Which files are modified.

---

## Risk

Low

Medium

High

---

## Expected Diff

Approximate lines changed.

---

## Verification

How success will be verified.

---

## Rollback

How to revert safely.

---

# MAXIMUM CHANGE

Default limits.

Files

<=3

Preferred.

Files

<=5

Acceptable.

Files

>5

Approval required.

---

Lines Changed

<150

Preferred.

150~400

Need explanation.

>400

Must split.

---

# CORE MODULES

The following modules are considered critical.

Examples:

Build

Bootstrap

Shell

ADB

Bridge

Updater

Installer

Self Evolution

Gradle

Project Configuration

Changes to these modules require approval.

---

# RISK CLASSIFICATION

Low

Examples

UI

Text

Icons

Logging

Documentation

Constants

Utility Functions

Safe Extraction

---

Medium

Compose

ViewModel

Repository

Database Query

Permissions

Settings

Lifecycle

---

High

Authentication

Build System

Project Structure

Shell

ADB

Shizuku

Bridge

Process

Installer

Migration

Core Business Logic

---

# EXECUTION ORDER

Never code immediately.

Always follow:

Understand

↓

Plan

↓

Identify files

↓

Estimate risks

↓

Wait if needed

↓

Implement

↓

Compile

↓

Review

↓

Verify

↓

Commit

---

# STOP CONDITIONS

AI must STOP immediately if:

Architecture change required.

More than five files modified.

API breaking changes.

Database migration.

Dependency changes.

Large refactor.

Build pipeline changes.

Permission model changes.

---

# WHEN STOPPING

AI must report:

Why it stopped.

What blocks progress.

Possible solutions.

Recommended solution.

Estimated risks.

Then wait.

---

# FORBIDDEN

Never:

"Since I was already here..."

No.

Never modify unrelated code.

Never clean neighboring files.

Never rename for beauty.

Never reorganize folders.

Never rewrite architecture.

Never upgrade libraries automatically.

Never introduce new frameworks.

---

# FEATURE DEVELOPMENT

Every feature must be divided.

Requirement

↓

Design

↓

Backend

↓

Frontend

↓

Integration

↓

Verification

↓

Documentation

↓

Commit

---

# BUG FIX

Bug fixing process.

Observe

↓

Reproduce

↓

Locate

↓

Root Cause

↓

Minimal Fix

↓

Regression Check

↓

Commit

Never jump directly to fixing.

---

# REFACTOR

Refactoring is NOT development.

Never mix:

Bug Fix

+

Feature

+

Refactor

One commit.

One purpose.

---

# REVIEW

Every completed task must include:

Summary

Files modified

Reason

Risk

Verification

Rollback

Remaining work

---

# COMMIT MESSAGE

Use format:

type(scope): summary

Examples

feat(chat): support markdown rendering

fix(shell): avoid null session crash

refactor(settings): extract validation helper

docs(task): update execution standard

---

# SUCCESS

A task is considered complete only if:

Code compiles.

Behavior preserved.

Verification passed.

Review completed.

Rollback possible.

Documentation updated if necessary.

Commit generated.

Otherwise,

the task is NOT complete.