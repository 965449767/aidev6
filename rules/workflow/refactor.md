# REFACTOR.md

# AI Refactoring Standard

Version: 1.0

---

# PURPOSE

Refactoring is the highest-risk engineering activity.

The purpose of this document is to prevent unnecessary changes.

Default attitude:

DO NOT REFACTOR.

---

# THE GOLDEN RULE

Working code is more valuable than beautiful code.

Never refactor only because code "looks ugly".

---

# WHEN REFACTOR IS ALLOWED

Refactoring is allowed ONLY if one of the following is true.

□ Duplicate code exists.

□ Existing bug is caused by current structure.

□ Maintenance cost is significantly reduced.

□ Performance bottleneck is proven.

□ Explicit request from human.

Otherwise,

DO NOT REFACTOR.

---

# WHEN REFACTOR IS FORBIDDEN

Never refactor because:

"It is cleaner."

"It is more modern."

"I prefer another pattern."

"I can improve readability."

"I found a better implementation."

These are NOT valid reasons.

---

# LARGE FILE POLICY

Large files are NOT automatically bad.

Examples

800 lines

1200 lines

2000 lines

Do NOT split simply because they are long.

First answer:

Does the current file actually create maintenance problems?

If NO,

stop.

---

# SAFE EXTRACTION ORDER

Only extract in this order.

Level 1 (Very Safe)

Constants

Enums

Data Classes

Configuration

Static Resources

Assets

Pure Extension Functions

Pure Utility Functions

------------------------------------------------

Level 2 (Safe)

Independent Helper Classes

Validation Logic

Formatting

Parsing

Converters

------------------------------------------------

Level 3 (Medium)

Repositories

Managers

ViewModels

Controllers

Services

------------------------------------------------

Level 4 (Dangerous)

Business Logic

Lifecycle

Process Management

Shell

ADB

Build

Bootstrap

Installer

Bridge

Core Services

Self Evolution

These require approval.

---

# FILE LIMIT

Single refactor

Preferred

<=3 files

Maximum

5 files

Beyond that,

split into multiple refactors.

---

# LINE LIMIT

Preferred

<150 changed lines

150~400

Explain.

>400

Split.

---

# BEHAVIOR LOCK

Refactoring must NEVER change:

Program behavior

Execution order

Public APIs

User interaction

Lifecycle

Permissions

Thread model

Exception behavior

Return values

Logging behavior

Build process

---

# FORBIDDEN ACTIONS

Never:

Rename everything.

Move folders.

Replace architecture.

Change package names.

Upgrade libraries.

Rewrite modules.

Replace design patterns.

Merge unrelated files.

Split everything.

Replace callbacks with Flow.

Replace Flow with Rx.

Replace MVVM.

Replace DI.

Without approval.

---

# IMPORT POLICY

Never optimize imports only.

Never reorder imports only.

Never format entire project.

Never change line endings.

Never change encoding.

Never change whitespace across unrelated files.

These destroy Git history.

---

# EXTRACTION RULE

Every extraction must satisfy:

Original behavior identical.

Compile passes.

Unit verification passes.

Rollback possible.

No API changes.

No lifecycle changes.

---

# REFACTOR PIPELINE

Before

↓

Risk Analysis

↓

Candidate Extraction

↓

One Small Refactor

↓

Compile

↓

Verification

↓

Commit

↓

Repeat

Never perform multiple refactors together.

---

# SAFE COMMIT STRATEGY

Good

Commit 1

Extract Constants

Commit 2

Extract Utilities

Commit 3

Extract Helper

Commit 4

Review

Good.

--------------------------------------

Bad

Move everything

Split everything

Rename everything

Rewrite everything

One commit

Never.

---

# CORE MODULE PROTECTION

The following modules are protected.

Examples

BuildBridge

Bootstrap

Terminal

Shell

ADB

Updater

Installer

Bridge

Gradle

Synchronization

Any modification requires approval.

---

# ARCHITECTURE PROTECTION

Architecture belongs to the human.

AI cannot decide:

MVVM

MVI

MVC

Repository

DI

Navigation

Modularization

Folder Structure

These are human decisions.

---

# SELF CHECK

Before submitting a refactor,

AI must answer.

1.

What problem did this refactor solve?

2.

Can I prove the benefit?

3.

What regression risks exist?

4.

Can this be reverted safely?

5.

Would I still recommend this change if I had to maintain this project for two years?

If any answer is weak,

cancel the refactor.

---

# REQUIRED OUTPUT

Every refactor must produce.

## Purpose

## Files Changed

## Risk

Low / Medium / High

## Behavior Change

None

(or explain)

## Verification

Compile

Manual Verification

Regression Check

## Rollback

Git Commit

or

Git Revert

---

# GOLDEN PRINCIPLE

A stable project with imperfect code

is always better

than

a beautiful project

that breaks tomorrow.