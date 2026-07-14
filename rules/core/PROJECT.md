# PROJECT.md

# Project Knowledge Base

Version: 1.0

This document is the single source of truth for the project.

Every AI agent MUST read this document before modifying any code.

---

# PROJECT OVERVIEW

Project Name

<Project Name>

Purpose

Describe in one paragraph:

What problem does this project solve?

Who uses it?

Why does it exist?

Never describe implementation.

Describe business purpose.

---

# PROJECT GOALS

Primary Goal

Example

Provide a modern Android terminal environment powered by Shizuku and AI.

Secondary Goals

Fast

Stable

Maintainable

Offline capable

Root optional

Safe

---

# NON-GOALS

The following are intentionally NOT part of this project.

Examples.

Become Linux distribution.

Support every Android version.

Replace Termux.

Rewrite Android framework.

Become general shell manager.

Never allow AI to introduce these accidentally.

---

# TARGET USERS

Primary Users

Power Users

Android Developers

Terminal Users

Root Users

Shizuku Users

AI Coding Users

Secondary Users

Advanced Android Enthusiasts

Never optimize for beginners unless requested.

---

# PROJECT PHILOSOPHY

The project values.

Reliability

↓

Predictability

↓

Maintainability

↓

Performance

↓

Beauty

Performance never comes before correctness.

Beauty never comes before stability.

---

# ENGINEERING PHILOSOPHY

Always choose.

Simple

↓

Stable

↓

Understandable

↓

Maintainable

Instead of.

Complex

↓

Clever

↓

Over-engineered

---

# CURRENT STATUS

Development Stage

Prototype

Alpha

Beta

Production

Current Milestone

<Describe>

Next Milestone

<Describe>

---

# TECHNOLOGY STACK

Language

Kotlin

UI

Jetpack Compose

Platform

Android

Minimum SDK

<>

Target SDK

<>

Build

Gradle

ADB

Supported

Shizuku

Supported

Root

Optional

Termux

Supported

Ubuntu

Supported

---

# PROJECT MODULES

Describe every major module.

Example.

Shell

Responsible for shell execution.

Bridge

Responsible for Android bridge.

Installer

Responsible for environment installation.

Updater

Responsible for update logic.

Bootstrap

Responsible for startup.

AI

Responsible for AI integration.

Terminal

Responsible for terminal rendering.

Every module should have ONE responsibility.

---

# MODULE OWNERSHIP

Every module must answer.

Why does it exist?

Who depends on it?

Who owns it?

What problems does it solve?

---

# DATA FLOW

Example.

UI

↓

ViewModel

↓

Repository

↓

Service

↓

Shell

↓

Android

Never bypass layers.

---

# DEPENDENCY RULES

Dependencies always point downward.

Never reverse dependency direction.

Never introduce circular dependency.

---

# PROJECT CONSTRAINTS

Never remove Shizuku support.

Never require Root.

Never break offline mode.

Never introduce cloud dependency.

Never break Android compatibility.

Never increase permissions unnecessarily.

---

# ARCHITECTURE DECISIONS

Reference.

DECISIONS/

Never redefine architecture.

Read decisions first.

---

# CODING STYLE

Prefer Kotlin idioms.

Prefer immutable data.

Prefer explicit names.

Avoid magic numbers.

Avoid hidden state.

Prefer readability.

---

# ERROR HANDLING

Every failure should.

Explain.

Recover if possible.

Log useful information.

Never silently fail.

---

# PERFORMANCE GOALS

Cold startup.

Memory usage.

Shell startup.

Compose performance.

Installation time.

Background resource usage.

Document measurable targets.

---

# SECURITY GOALS

Never expose secrets.

Validate inputs.

Escape shell commands.

Verify permissions.

Handle unsupported devices.

Fail safely.

---

# DEBUGGING

Always use DEBUG.md.

Never guess.

---

# REVIEW

Always use REVIEW.md.

Never skip.

---

# EXECUTION

Always follow EXECUTION.md.

---

# REFACTOR

Always follow REFACTOR.md.

---

# VERIFY

Always follow VERIFY.md.

---

# DECISION LOG

Important architectural decisions belong in:

DECISIONS/

Never bury them inside chat history.

---

# KNOWN LIMITATIONS

List current limitations honestly.

Examples.

Large file exists intentionally.

Feature incomplete.

Experimental APIs.

Known Android compatibility issues.

Performance bottlenecks.

This prevents AI from "fixing" intentional decisions.

---

# KNOWN TECHNICAL DEBT

Describe.

Why it exists.

Why it has not been fixed.

Priority.

Expected future solution.

Never let AI "clean" technical debt automatically.

---

# CURRENT PRIORITIES

Priority 1

Priority 2

Priority 3

Everything else waits.

---

# OUT OF SCOPE

List features intentionally rejected.

This prevents feature creep.

---

# SUCCESS METRICS

Measure.

Crash Rate

Installation Success

Build Stability

Regression Count

Startup Time

User Satisfaction

NOT

Lines of Code.

---

# AI INSTRUCTIONS

Before making any changes.

Read this file.

Read AGENTS.md.

Read EXECUTION.md.

Read TASK.md.

Read ARCHITECTURE.md.

Understand the project.

Then work.

Never assume.

---

# GOLDEN PRINCIPLE

The project exists to solve user problems.

Not to demonstrate engineering skills.