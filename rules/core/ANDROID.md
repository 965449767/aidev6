# ANDROID.md

# AI Android Engineering Standard

Version: 1.0

---

# PURPOSE

This document defines Android engineering rules for AI agents.

Priority.

Correctness

↓

Lifecycle

↓

Compatibility

↓

Performance

↓

Readability

Never sacrifice correctness for optimization.

---

# GOLDEN RULE

Android is lifecycle driven.

Every implementation must respect lifecycle.

Never assume objects live forever.

---

# PLATFORM

Target Platform

Android

Language

Kotlin

UI

Jetpack Compose

Architecture

Defined by PROJECT.md

Never replace technology without approval.

---

# ANDROID VERSIONS

Always verify compatibility.

Minimum SDK

<Project>

↓

Target SDK

<Project>

↓

Latest Android

Never use APIs without compatibility checks.

Always prefer AndroidX.

---

# UI

Prefer Compose.

Do not introduce XML unless explicitly requested.

UI should remain stateless whenever possible.

Business logic never belongs inside Composable.

---

# COMPOSE

Composable should.

Render state.

Emit events.

Nothing more.

Never execute business logic.

Never execute shell commands.

Never access repositories directly.

---

# STATE

Single Source of Truth.

UI

↓

ViewModel

↓

Repository

↓

System

Never duplicate state.

Avoid mutable global state.

---

# REMEMBER

Always verify.

remember

rememberSaveable

StateFlow

SharedFlow

MutableState

SnapshotState

Avoid unnecessary recomposition.

---

# EFFECTS

Choose correctly.

LaunchedEffect

DisposableEffect

SideEffect

produceState

derivedStateOf

Never misuse effects.

---

# VIEWMODEL

ViewModel owns.

Business state.

Screen state.

Coroutine scope.

Never reference Activity.

Never reference Context unless Application.

---

# ACTIVITY

Activity should.

Host UI.

Request permissions.

Handle navigation entry.

Avoid business logic.

---

# FRAGMENT

Avoid introducing Fragment if Compose-only project.

If Fragment exists.

Respect lifecycle.

Avoid duplicate logic.

---

# COROUTINES

Never block Main Thread.

Choose Dispatcher carefully.

Main

UI

IO

Disk

Network

Default

CPU

Cancel jobs correctly.

Never leak coroutines.

---

# FLOW

Prefer StateFlow for UI state.

Prefer SharedFlow for events.

Never expose MutableStateFlow publicly.

---

# LIFECYCLE

Always verify.

Create

Start

Resume

Pause

Stop

Destroy

Configuration change

Background

Foreground

Rotation

Never assume lifecycle order.

---

# PERMISSIONS

Permission flow.

Manifest

↓

Runtime

↓

Special Permission

↓

Shizuku

↓

Root

Always check permission before execution.

Provide graceful fallback.

---

# SHIZUKU

Treat Shizuku as optional.

Verify.

Installed

↓

Permission

↓

Binder

↓

Version

↓

Alive

↓

Service

Never assume availability.

Fallback must exist.

---

# ROOT

Root is optional.

Never require Root if Shizuku or normal APIs work.

Detect safely.

Never force Root.

---

# ADB

ADB support should.

Detect device.

Detect shell.

Check exit code.

Handle unsupported state.

Never hardcode assumptions.

---

# SHELL

Never execute shell directly from UI.

Always route through Shell Layer.

Every command must.

Log

↓

Execute

↓

Check Exit Code

↓

Handle stderr

↓

Return Result

Never ignore failures.

---

# TERMINAL

Terminal rendering.

Separate from shell execution.

Never mix rendering with execution logic.

---

# FILE SYSTEM

Always verify.

Exists

Readable

Writable

Permission

Encoding

Storage location

Avoid hardcoded paths.

---

# STORAGE

Prefer.

App Storage

↓

SAF

↓

MediaStore

↓

External Storage

Never use legacy storage unnecessarily.

---

# DATABASE

Room preferred.

Migration required.

Never break schema silently.

Use transactions when necessary.

---

# NETWORK

Always.

Timeout

Retry

Cancellation

Serialization

Offline handling

Meaningful errors

Never block UI.

---

# SERVICES

Foreground Service only when required.

Respect Android restrictions.

Handle process death.

---

# BACKGROUND WORK

Prefer.

WorkManager

↓

Foreground Service

↓

AlarmManager

Avoid custom background loops.

---

# PERFORMANCE

Measure before optimizing.

Check.

Startup

Memory

Compose

Allocation

Frame drops

Shell startup

Do not optimize blindly.

---

# LOGGING

Logs should include.

Module

Operation

Error

Context

Never log.

Passwords

Tokens

Secrets

Private data

---

# ERROR HANDLING

Every failure should.

Explain.

Recover if possible.

Provide fallback.

Never crash silently.

---

# TESTING

Verify.

Different Android versions.

Different OEM ROMs.

Permission denied.

No Shizuku.

No Root.

Offline.

Low memory.

Rotation.

Background.

---

# REVIEW

Every Android implementation must review.

Lifecycle.

Compose.

Threading.

Permission.

Memory.

Performance.

Compatibility.

---

# FORBIDDEN

Never.

Block Main Thread.

Leak Activity.

Leak Context.

Ignore lifecycle.

Ignore permissions.

Ignore exit codes.

Hardcode SDK assumptions.

Force Root.

Assume Shizuku.

Use reflection without reason.

---

# OUTPUT

Every Android task must report.

Modules affected.

Lifecycle impact.

Permission impact.

Compatibility impact.

Performance impact.

Verification completed.

---

# GOLDEN PRINCIPLE

Android is not desktop.

Lifecycle correctness is more important than clever code.