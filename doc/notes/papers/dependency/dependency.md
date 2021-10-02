# Understanding and Discovering Software Configuration Dependencies in Cloud and Datacenter Systems

[toc]

This note is the summary of [Understanding and Discovering Software Configuration Dependencies in Cloud and Datacenter Systems](https://www.cs.cornell.edu/~legunsen/pubs/ChenETAL20CDep.pdf).

## Introduction

The dependent configuration parameters should be considered together: setting one of them could affect the others.

Based on our study, we define and formalize five types of configuration dependencies with the common code patterns that they manifest.

`cDep` uses a novel and intuitive idea to discover configuration dependencies.

## Background

### Feature of Software configuration:

* Software configuration dependencies could be complex and even across software components. 

* Failures to understand or satisfy configuration dependencies can have negative impacts. 

* Additionally, the configuration dependencies were not always well documented.

### Configuration usage

* Loading: Configurations are loaded by reading from an external file or database and **storing parameter values in program variables**. For example,  Hadoop projects load configurations using getter methods that take a parameter and return a value.
* Propagation and transformation: propagated by assignment statements and transformed by arithmetic or string operations. Usually **inter-procedural**.
* Usage: Eventually, parameter values are used in statements that change program behavior(as **sink**).

### Configuration dependency

Conceptually, there are two types of configuration dependency: **functional** and **behavioral**.

#### functional

A parameter value is influenced by other parameter values.

A functional configuration dependency can be defined as $(M,f)$.

Let $P$ be the set of all parameters. $M$ maps a parameter $p \in P$ to a non-empty set of parameters $Q \subseteq P$ if the value or scope of $p$ depends on a function $f$ from the value of parameters in $Q$. 

For example, let $Q = \{ q_1,...,q_n \}.$ Then $(p \rightarrow Q, f)$ is a configuration dependency if the value or scope of $c(p)$ is determined by $f(c(q_1),...,c(q_n))$, where $c$ is a getter method.

#### behavioral

A set of parameter values combine to influence a particular system behavior.

A behavioral configuration dependency is a function $g:R \rightarrow \{ true,fase \}$. Here, $R$ is a set of values of parameters s.t. $R \subseteq P$.  $g$ returns $true$ if there is a method in the program that takes  $R = \{ c(r_1),...,c(r_n) \}$ as arguments and return a non-zero exit code.

### Data Collection and Analysis

we manually collected configuration dependencies from two text-based data sources: **configuration metadata** and **user manuals**.

However, those sources may miss important info or be outdated. Therefore, we manually validated every collected configuration dependency by understanding how the dependency occurs in the code.

#### Collecting Configuration Dependencies

We prioritized completeness over precision -- our heuristics-based text analysis is effective in discovering configuration dependencies but also introduces false positives. All collected data are subsequently manually inspected and validated.

##### Structured Configuration Metadata

We rarely found the configuration dependency information in these structured configuration metadata.

Here is the heuristic to search for potential configuration dependencies: If the description of one parameter mentions another parameter, there is a likely dependency between both parameters. 

##### Unstructured Manual Pages

We use such heuristic: if two parameters are mentioned in the same paragraph, they may be dependent and we liberally track them as such.

##### Validation and Analysis

Two inspectors manually analyze dependencies.

### Configuration Dependency Types

#### Functional Dependency

There are 4 types of functional configuration dependencies.

##### Control Dependency

whether a dependent parameter $p$ value can be used or not depends on the value of other parameters -- $f(Q)$ determines whether $p$ is in scope.

The most common form of control dependency is that $\{ q_1,...,q_n \}$ enables or disables the execution of the only parts of code where $p$ is used. That is, $c(p)$ is used only when $c(q_1) \land ... \land c(q_n)$ is true.

There are two code patterns for this case:

* Branch condition: $c(p)$ is used in one branch and the condition depends on $Q	$.
* Object creation: $c(p)$ is used in a method of a particular subclass, which depends on $Q$.

##### Default Value Dependency

The default value of the dependent parameter $p$ is a function of $Q$ iff $p$ is not currently assigned a value. That is,
$$
c(p) = h(c(q_1),...,c(q_n))
$$
when $c(p) == null$.

There are two code patterns for this case:

* In-file substitution: One parameter value is explicitly used as the default value for the dependent parameter in the configuration file.
* In-code substitution: During execution, if the value of the dependent parameter is null, it is set to the value of another parameter.

##### Overwrite Dependency

When multiple components are used together, some values for parameters defined in one component may be overwritten to be consistent with the parameter values in another component.

Code patterns:

* Explicit overwrites: The variable holding the dependent parameter’s value is directly re-assigned.
* Implicit overwrites: Multiple parameters are used to set the environmental variables and different environmental variables possess different priorities which form the overwriting relation implicitly

##### Value Relationship Dependency

The value of the dependent parameter $p$ is constrained by the values of parameters in $Q$. There are 3 kinds of constraints:

* Numeric
* Logical
* Set

#### Behavioral Dependency

In a behavioral configuration dependency, there is no dependent parameter $p$ whose value or scope depends on the values of other parameters. However, the values of multiple parameters in a set $P = \{  p_1, ...,p_n \}$ "co-operate" to influence the behavior of the system.

##### Code patterns

* $P$ is the arguments of a library/system/method call

* The result of an arithmetic operation on elements in $P$ is an argument to the library/system/method call

#### Discussion

##### Variables in Dependencies

Some configuration dependencies could also include variables whose values can only be evaluated at runtime.(Static analysis is in vain)

##### Dependencies that we do not cover

Resource competition is difficult to capture within the target software, without knowledge of the deployment environment. Therefore, we do not consider them in this paper.

### Dependency handling in practice

We focus only on value relationship dependency types which have clear definitions of violations which occur when parameter values do not hold constraints.

We rarely found checking code or feedback messages in the program.

