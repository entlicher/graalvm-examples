# IGraalVM Jupyter Kernel

IGraalVM is a Jupyter kernel implementation. It allows to run all interactive GraalVM languages in a single notebook with variables sharing.

## Install

Run `python3 install.py` to install the GraalVM Jupyter Kernel. Installs the JVM variant by default. Use either `--graalvm` option to point to the GraalVM installation directory, or export `GRAALVM_HOME` environment variable prior running the install. The kernel is installed into the user location by default.

### Java + Guest Languages

By default (when `--native` option is not specified), `GraalVM` kernel is installed. It runs the in JVM mode and Java language can be evaluated via JShell. All interactive languages installed in the GraalVM can be used for evaluation.

### Native Guest Languages

Use `--native=<list of language IDs>` to build and install `GraalVM Native` - a native image of the Jupyter kernel with the set of guest languages included. E.g. to include JavaScript and Ruby, use: `--native=js,ruby`. This kernel can not evaluate Java code.

## Usage

After kernel installation run the Jupyter Notebook and create a new notebook with either `GraalVM` or `GraalVM Native` kernel. The initial language in `GraalVM` is `java`, the initial language in `GraalVM Native` is the first installed guest language in the alphabetic order.

Use the shebang sequence to change the languages at the very begining of the cell code. Tab shows completion with available languages. E.g. to switch to Ruby, write: `#!ruby `.

Newly created global variables are shared among languages, unless variables of the same name were present in the fresh initialization of the target language. There is a comment about imported variables on language switch. E.g. when variables `a`, `b`, `c` are created in JavaScript, only `a` and `b` are imported into R, because `c` is a built-in function in R:
```
#!js
var a = 10
var b = 20
var c = 30
```
```
#!R
x <- c(a, b)
x
```
```
Imported variables: a, b
[1] 10 20
```

### Graphs

Currently graphs can be drawn in R. `svg()` output is activated automatically and if `grDevices:::svg.off()` provides some SVG image after the cell execution, it's drawn to the notebook. E.g.:
```
data <- rnorm(100, sd=15)+1:100
plot(data, main="A simple scatterplot", xlab="A random variable plotted", ylab="Some rnorm value", col="steelblue")
```
plots the graph into the Jupyter notebook.


## TODO

- Variables sharing between Java and Guest languages.
- Library installations in guest languages.
- Variables introspection.
- Explore a possibility to prepend cells with the current language (important mainly for the very first cell).
- Graphical output in other guest languages.
- Completion of global objects and object properties, explore integration with LSP
- Test if native languages could be run via compile by LLVM Toolchain and eval bitcode via LLVM.
- Test Espresso for Java execution in native image.

