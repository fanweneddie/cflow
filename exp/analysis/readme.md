# Experiment Analysis

I runs some shell scripts to test the effect of my optimization, and the analysis are listed in the current directory:

*  `determinism_analysis.md` contains the analysis of the optimization for a deterministic output.
* `points-to_analysis.md` contains the analysis of the optimization for a points-to analysis, which can have a lower false-negative rate than just ignoring polymorphism.
* `static_type_check_analysis.md` contains the analysis of the implementation of ignoring polymorphism.
* `use_check_analysis.md` contains the analysis of the optimization that checks the use of each field reference in sink.

