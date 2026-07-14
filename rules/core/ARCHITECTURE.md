# ARCHITECTURE.md

# AI Architecture Governance Standard

Version: 1.0

---

# PURPOSE

Architecture belongs to humans.

Implementation belongs to AI.

This document defines the boundary.

---

# THE GOLDEN RULE

AI executes architecture.

AI does NOT invent architecture.

---

# RESPONSIBILITY

Human owns:

Architecture

Project Direction

Technology Selection

Dependency Strategy

Module Boundaries

Package Structure

Coding Style

Roadmap

Business Decisions

AI owns:

Implementation

Bug Fixes

Optimization

Documentation

Testing

Review

Verification

Nothing more.

---

# ARCHITECTURE AUTHORITY

AI has NO authority to decide:

MVVM

MVI

MVC

Repository Pattern

Clean Architecture

Hexagonal

DDD

Modularization

Package Structure

Dependency Injection

Navigation

State Management

Persistence Strategy

Synchronization Strategy

Thread Model

Without explicit approval.

---

# NEVER SAY

"I replaced MVVM because..."

"I modernized the architecture."

"I reorganized the packages."

"I upgraded the project structure."

"I adopted a cleaner pattern."

Forbidden.

---

# BEFORE SUGGESTING ARCHITECTURE

AI must answer.

Is architecture blocking the task?

YES

↓

Continue.

NO

↓

Do not propose architecture changes.

---

# MODULE BOUNDARIES

Modules are contracts.

Never move responsibilities across modules.

Example.

Good

UI

↓

ViewModel

↓

Repository

↓

Service

↓

System

Bad

UI

↓

System

Skipping layers.

---

# SINGLE RESPONSIBILITY

One module.

One responsibility.

One reason to change.

Never create "万能工具类".

Never create "CommonManager".

Never create "HelperHelper".

Avoid God Objects.

---

# DEPENDENCY DIRECTION

Always.

UI

↓

ViewModel

↓

Repository

↓

Service

↓

System

Never reverse dependencies.

System should not depend on UI.

Repository should not depend on Activity.

Utility should not depend on ViewModel.

---

# IMPORT RULE

Dependencies should point inward.

Never create circular dependencies.

Never import modules only for convenience.

---

# FEATURE ISOLATION

Each feature should own:

UI

Logic

Model

Storage

Configuration

Testing

Documentation

Avoid hidden coupling.

---

# PUBLIC API

Every public API is a contract.

Changing it requires approval.

Never:

Rename

Remove

Reorder

Without migration.

---

# INTERNAL API

Prefer internal APIs.

Expose minimum surface.

Hide implementation details.

---

# STATE MANAGEMENT

Single Source of Truth.

Avoid duplicated state.

Avoid hidden mutable state.

Avoid global mutable state.

Never synchronize manually if architecture already solves it.

---

# CONFIGURATION

Configuration should be centralized.

Never duplicate:

URLs

Paths

Keys

Timeouts

Flags

Permissions

Magic values

---

# LIFECYCLE OWNERSHIP

Each resource must have exactly one owner.

Examples.

Coroutine

ViewModel

Activity

Fragment

Service

Binder

Shell Session

Database

Never multiple owners.

---

# SHELL ARCHITECTURE

Shell execution should pass through one abstraction.

Do not scatter shell commands across the project.

Do not hardcode shell execution.

---

# SHIZUKU

One entry point.

One permission layer.

One compatibility layer.

Never duplicate binder management.

---

# ADB

ADB interaction must be centralized.

Never call adb logic directly from UI.

Never duplicate command execution.

---

# BUILD

Build system is protected.

Never modify:

Gradle

Plugin

Signing

Version Catalog

Build Pipeline

Without approval.

---

# LARGE FILES

Large file.

↓

Does NOT mean.

Bad architecture.

Before splitting.

Ask.

Does the file violate responsibility?

If NO.

Keep it.

---

# CODE OWNERSHIP

Every class should answer.

Who owns me?

Why do I exist?

Who depends on me?

If unclear.

Architecture problem.

---

# AI DECISION LIMIT

AI may decide.

Local implementation.

Local optimization.

Bug fixes.

Documentation.

Minor extraction.

Nothing beyond.

---

# ARCHITECTURE CHANGE REQUEST

If AI believes architecture should change.

Produce.

Current Design

↓

Problem

↓

Evidence

↓

Alternative

↓

Benefits

↓

Risks

↓

Migration Cost

↓

Rollback Plan

Wait.

Never implement automatically.

---

# REVIEW

Before finishing.

Answer.

Did I move responsibilities?

Did I create coupling?

Did I increase complexity?

Did I change architecture?

Did I change ownership?

If YES.

Stop.

Request approval.

---

# GOLDEN PRINCIPLE

Architecture is expensive.

Changing architecture costs more than writing code.

Therefore.

Architecture changes require human decisions.

Always.