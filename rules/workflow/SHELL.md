# SHELL.md

# AI Shell & System Execution Standard

Version: 1.0

---

# PURPOSE

This document defines how AI interacts with the Android system.

Everything that executes outside the JVM is considered dangerous.

Shell execution is a privileged operation.

Treat every command as a potential failure.

---

# GOLDEN RULE

Never trust the shell.

Always verify.

---

# EXECUTION PIPELINE

Request

↓

Permission Check

↓

Environment Check

↓

Command Build

↓

Execute

↓

Collect stdout

↓

Collect stderr

↓

Collect Exit Code

↓

Analyze

↓

Return Structured Result

Never skip any stage.

---

# SHELL LAYER

Application

↓

ViewModel

↓

Repository

↓

Shell Service

↓

Shell Executor

↓

System

UI must NEVER execute shell directly.

---

# EXECUTION OBJECT

Every shell execution should return.

Command

Arguments

Working Directory

Environment

Start Time

End Time

Duration

stdout

stderr

Exit Code

Permission Mode

Execution Result

Never return only String.

---

# PERMISSION LEVELS

Level 0

Normal Android API

Preferred.

--------------------

Level 1

App Process Shell

--------------------

Level 2

ADB Shell

--------------------

Level 3

Shizuku

--------------------

Level 4

Root

Highest Risk.

Never jump directly to higher privilege.

---

# PREFERENCE

Always choose.

Android API

↓

Shizuku

↓

ADB

↓

Root

Never use Root if Android API is sufficient.

---

# SHIZUKU

Before execution verify.

Installed

↓

Permission Granted

↓

Binder Connected

↓

Service Running

↓

Version Compatible

↓

Alive

If any check fails.

Fallback.

Never crash.

---

# ROOT

Treat Root as optional.

Verify.

su exists

↓

Permission granted

↓

Interactive shell

↓

Exit Code

Never assume Root availability.

---

# ADB

Verify.

Device connected

↓

ADB state

↓

User

↓

Shell permission

↓

Command availability

↓

Exit Code

Never assume adb shell behaves identically across devices.

---

# COMMAND CONSTRUCTION

Never concatenate user input.

Always separate.

Command

Arguments

Escape special characters.

Avoid shell injection.

Never execute raw user strings.

---

# WORKING DIRECTORY

Never assume current directory.

Always specify.

Working Directory.

---

# ENVIRONMENT

Verify.

PATH

HOME

TMPDIR

ANDROID_DATA

ANDROID_ROOT

ANDROID_STORAGE

TERM

Do not rely on implicit environment variables.

---

# EXIT CODE

Exit Code must always be checked.

0

Success.

Non-zero

Failure.

Never ignore Exit Code.

---

# STDERR

stderr is meaningful.

Never discard it.

Always capture.

Always return.

---

# STDOUT

stdout should be structured whenever possible.

Avoid parsing human-readable output if machine-readable output exists.

---

# TIMEOUT

Every command requires timeout.

Never allow infinite execution.

Provide cancellation.

---

# CANCELLATION

Every long-running process must support.

Cancel.

Kill.

Cleanup.

Never leak processes.

---

# PROCESS MANAGEMENT

Track.

PID

Start Time

Running State

Termination Reason

Exit Code

Never lose process ownership.

---

# SESSION

Interactive sessions should have.

Create

Reuse

Close

Cleanup

Never leave zombie sessions.

---

# LOGGING

Log.

Command

Duration

Exit Code

Permission Mode

Error

Never log.

Passwords

Tokens

Private Keys

Secrets

---

# FILE OPERATIONS

Before operating.

Verify.

Exists

Readable

Writable

Owner

Permission

Available Space

Avoid destructive operations.

---

# DELETE

Deletion is dangerous.

Always verify.

Target exists.

Correct path.

Not root directory.

Not system directory.

Never use recursive deletion without explicit approval.

---

# MOVE

Verify.

Source

Destination

Permission

Overwrite Risk

Rollback Possibility

---

# COPY

Verify.

Destination exists.

Space available.

Permission.

Checksum if necessary.

---

# PACKAGE MANAGEMENT

Before installing.

Verify.

APK exists.

Architecture.

Version.

Signature.

Permission.

Storage.

Never overwrite silently.

---

# SYSTEM PROPERTIES

Read safely.

Never assume property exists.

Handle missing values.

---

# DEVICE COMPATIBILITY

Verify.

Android Version

ABI

Architecture

SELinux

Manufacturer

ROM

Kernel

Do not assume Pixel behavior applies everywhere.

---

# ERROR HANDLING

Every failure must include.

Command

Exit Code

stderr

Likely Cause

Suggested Action

Never report only.

"Failed."

---

# FALLBACK STRATEGY

Every privileged operation should define.

Primary

Fallback

Failure Result

Recovery

Example.

Shizuku

↓

ADB

↓

Android API

↓

Inform User

---

# SECURITY

Never execute.

User generated shell.

Downloaded scripts.

Unknown binaries.

Never disable SELinux.

Never modify system partition automatically.

Never escalate privilege automatically.

---

# REVIEW

Every shell implementation must verify.

Exit Code checked.

stderr handled.

Timeout exists.

Cancellation exists.

Permission checked.

Fallback exists.

Logging exists.

Cleanup exists.

---

# OUTPUT FORMAT

Every shell operation should return.

