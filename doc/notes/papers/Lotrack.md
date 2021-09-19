# Tracking Load-time Configuration Options

[toc]

This is the note of paper [Tracking Load-time Configuration Options](https://www.bodden.de/pubs/lkb14tracking.pdf).

## Introduction

For load-time parameters, identifying the code fragments that implement an option manually requires tedious effort.

`Lotrack` aims at identifying all code that is included if and only if a specific configuration option or combination of configuration options is selected.

To increase precision, we exploit the insight that configuration options are typically used differently from other values in the code: **Values for configuration options are often passed along unmodified and are used in simple conditions, making their tracking comparatively easy and precise.**

Technically, `Lotrack` extends a **context, flow, object and field-sensitive taint analysis** to build a configuration map describing how code fragments depend on configuration options.



## Problem

Our goal is to trace configuration options to the code fragments implementing them.

Technically, we seek to establish a configuration map which maps every code fragment to a configuration constraint describing in which configurations the code fragment may be executed in the program, that is, which configuration options or combinations of options need to be selected or deselected.

Here are the assumptions:

1. Configuration options are set at program load time and do not change during the execution of the program.
2. The API calls to load configuration values are known and can be identified syntactically; the possible values of configuration options are finite and known.

## Approach

Static taint analysis

 About how to handle native functions and environment interactions, we allow false negatives and only create taints for results of native-method calls or environment interactions if they have been parameterized with a tainted value.

To track configuration values, `Lotrack` implements several extensions to the taint analysis. In particular, `Lotrack` tracks a taint for each possible value of a variable and tracks a **constraint** under which configuration this variable has this value. Second, `Lotrack` does not propagate all taints directly, but analyzes, restricts, and merges constraints at control-flow decisions.

The analysis creates a taint for every possible value because options usually have small finite domains.

The taint information denoted by the variable and the tracked value together with the constraint constitute a **fact**. A fact is no longer propagated, however, if the corresponding constraint is unsatisfiable.

If a control-flow decision depends on a tainted value, we derive constraints for the control-flow branches by evaluating the branching condition.