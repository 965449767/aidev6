# DEBUG.md

# AI Debugging Standard

Version: 1.0

---

# PURPOSE

Debugging is an investigation.

NOT guessing.

NOT coding.

The goal is to discover the ROOT CAUSE before writing any code.

Never modify code before understanding the failure.

---

# THE GOLDEN RULE

Evidence First.

Code Later.

If there is no evidence,

there is no fix.

---

# DEBUG PIPELINE

Every debugging task MUST follow:

Observe

↓

Reproduce

↓

Collect Evidence

↓

Locate

↓

Root Cause Analysis

↓

Minimal Fix

↓

Verification

↓

Regression Check

↓

Commit

Never skip steps.

---

# STEP 1

OBSERVE

Before changing anything, answer:

What happened?

When did it happen?

Can it be reproduced?

Always describe:

Expected behavior

Actual behavior

Difference

---

# STEP 2

REPRODUCE

Never fix bugs that cannot be reproduced.

Determine:

Always

Sometimes

Rare

Random

Device-specific

Version-specific

Permission-specific

Environment-specific

---

# STEP 3

COLLECT EVIDENCE

Evidence may include:

Crash stacktrace

Logcat

Build output

Gradle logs

ADB output

Shell output

Exception message

Screenshot

Screen recording

Database state

Network response

Android version

Device model

App version

Never skip evidence collection.

---

# STEP 4

LOCATE

Locate the exact location.

Not:

"Probably here."

Must identify:

Module

↓

Class

↓

Method

↓

Line Number

↓

Call Path

Never modify unrelated files.

---

# STEP 5

ROOT CAUSE

Always answer:

Why did this happen?

Why here?

Why now?

Why only this case?

Never stop at symptoms.

Example

BAD

NullPointerException

GOOD

ViewModel emitted null because Repository returned empty object after permission denial.

Root cause.

---

# STACKTRACE RULE

Read stacktrace from bottom to top.

Always identify:

Exception Type

↓

Caused by

↓

First project file

↓

Method

↓

Exact line

↓

Caller

↓

Root cause

Never ignore "Caused by".

---

# LOGCAT RULE

Read Logcat in this order:

FATAL

ERROR

WARN

INFO

DEBUG

Never start with INFO.

Always locate first ERROR.

---

# BUILD FAILURE

Before changing Gradle,

identify:

Compile error

Dependency error

Manifest error

Resource error

KSP/KAPT error

Signing error

SDK mismatch

Never guess.

---

# COMPOSE DEBUG

Always check:

remember

rememberSaveable

MutableState

StateFlow

SharedFlow

Recomposition

LaunchedEffect

DisposableEffect

SideEffect

SnapshotState

Lifecycle

---

# LIFECYCLE DEBUG

Check:

Activity

Fragment

ViewModel

Compose

Coroutine Scope

LifecycleOwner

Configuration Change

Destroy

Recreate

---

# COROUTINE DEBUG

Check:

Dispatcher

Cancellation

Exception

Scope

Job

SupervisorJob

Flow

SharedFlow

StateFlow

Never blame coroutine without evidence.

---

# PERMISSION DEBUG

Always verify:

Manifest

Runtime Permission

Shizuku

Root

ADB

SELinux

Android Version

Target SDK

Permission denial logs

---

# SHELL DEBUG

Collect:

Command

Arguments

Exit Code

stdout

stderr

Execution Time

Permission

Never ignore exit code.

---

# SHIZUKU DEBUG

Verify:

Service Connected

Permission Granted

Binder Alive

Binder Dead

User Service

Package State

Version Compatibility

Never assume Shizuku is available.

---

# ADB DEBUG

Verify:

Device Connected

ADB State

Shell Permission

Package Installed

Current User

Android Version

Exit Code

---

# NETWORK DEBUG

Collect:

Request

Response

Headers

Status Code

Timeout

Retry

JSON

Serialization

Never debug network without logs.

---

# DATABASE DEBUG

Verify:

Migration

Schema

Primary Key

Foreign Key

Query

Returned Rows

Transaction

Room Version

---

# FILE DEBUG

Verify:

Exists

Readable

Writable

Encoding

Permission

Path

Current Directory

---

# AI GUESSING POLICY

Forbidden words:

Probably

Maybe

Seems

Should

Might

Likely

Without evidence.

Every assumption must be marked.

---

# FIX POLICY

Always choose:

Smallest fix.

Never rewrite module.

Never refactor while debugging.

Never optimize while debugging.

Fix ONE problem.

Only.

---

# VERIFICATION

After fixing,

verify:

Original bug fixed

No new crashes

Compile passes

Related feature works

Logs clean

No regression

---

# DEBUG REPORT

Every debugging session must produce:

## Problem

## Reproduction

## Evidence

## Root Cause

## Fix

## Verification

## Remaining Risks

## Prevention

---

# IF ROOT CAUSE IS UNKNOWN

Never fake confidence.

Instead say:

"I have insufficient evidence."

Then request:

Logcat

Stacktrace

Reproduction steps

Build output

Environment

Do not continue guessing.

---

# GOLDEN PRINCIPLE

The first correct diagnosis is worth more

than ten incorrect fixes.

Never trade certainty for speed.