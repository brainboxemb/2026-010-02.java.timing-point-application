# Repository specification

## Purpose

This repository provides the public Java implementation of
**SI-01 — Timing Point Application**, plus the shared TimingData Java library,
standalone Development Client and formal black-box system-test support used to develop
and qualify that implementation.

The product requirements and architecture are **not owned here**. They are defined in the
companion Event Timing Software meta repository.

## Typical users

- SI-01 contributors implementing accepted requirements/design;
- maintainers building, qualifying and releasing the Java artifacts;
- engineers using the Development Client to inspect public application boundaries;
- automated verification that runs the packaged application through supported interfaces;
- other Java consumers of the shared TimingData library.

## Why use this repository?

Use it when you need:
- the runnable public Timing Point Application;
- the reusable application core;
- the shared IF-05 TimingData Java representation;
- current implementation source/tests;
- build/test/release evidence;
- public engineering-client and black-box verification support.

## Non-goals

This repository does not:
- own the SIP roadmap;
- redefine IF-03, IF-05 or IF-11;
- replace the SI-01 SSD/SDDs;
- contain private production protocols, credentials or mappings;
- make the Development Client into SI-02;
- use the changelog as current architecture authority.
