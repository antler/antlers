# Antlers

A swift, robust templating system for clojure

## Introduction

Antlers grew out of a plain mustache library called [Stencil](http://github.com/davidsantiago/stencil), but under the strain of heavy use acquired many features that now no longer align with the original mustache philosophy (in particular, lambdas no longer behave like mustache lambdas, though there are many new features as well).  Consider it a superset of mustache, with lambdas that make sense.  

## Usage

To simply render a string as a template, use `render-string`:

    (require '[antlers.core :as antlers])

    (antlers/render-string 
     "Roaming the open {{land}}"
     {:land "tundra!"})

    --> "Roaming the open tundra!"

To render the same template over and over again, use render-file (which caches the parsed AST that can subsequently be reused repeatedly):

    (antlers/render-file
     "roaming"
     {:land "taiga!"})

    --> "Roaming the open taiga!"

This will find a file called "roaming" anywhere in your classpath.  

To cache the AST for a string, you can register a template with a key and then call `render-file` using that key.  It will behave as if it was loaded from a file:

    (use 'antlers.parser)
    (register-template "roaming" "Roaming the open {{land}}")
    (antlers/render-file "roaming" {:land "tundra!"})

    --> "Roaming the open tundra!"

## Additional features

## License

Eclipse Public License
